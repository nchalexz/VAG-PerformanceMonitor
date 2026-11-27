package io.github.nchalexz.aaperfmon.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка расписания опроса без адаптера и без корутин.
 *
 * Все сценарии из требований к телеметрии, которые касаются порядка,
 * повторных попыток и перегрузки, проверяются здесь на управляемых часах.
 */
class TelemetrySchedulerTest {

    private var now = 0L

    private fun scheduler(config: TelemetryScheduler.Config = TelemetryScheduler.Config()) =
        TelemetryScheduler(clock = { now }, config = config)

    @Test
    fun `p0 is served before p2 when both are due`() {
        val scheduler = scheduler()
        scheduler.setDemand(
            listOf(
                TelemetryScheduler.Demand(SignalCatalog.Ids.FUEL_LEVEL),
                TelemetryScheduler.Demand(SignalCatalog.Ids.RPM),
            ),
        )
        assertEquals(SignalCatalog.Ids.RPM, scheduler.nextDue(now)!!.id)
    }

    @Test
    fun `demand is deduplicated and the foreground tier wins`() {
        val scheduler = scheduler()
        scheduler.setDemand(
            listOf(
                TelemetryScheduler.Demand(
                    SignalCatalog.Ids.RPM,
                    TelemetryScheduler.Tier.BACKGROUND,
                ),
                TelemetryScheduler.Demand(
                    SignalCatalog.Ids.RPM,
                    TelemetryScheduler.Tier.FOREGROUND,
                ),
            ),
        )
        assertEquals(1, scheduler.size())
        val snapshot = scheduler.snapshot(SignalCatalog.Ids.RPM)!!
        assertEquals(SignalPriority.P0, snapshot.priority)
        // Фоновый минимум интервала не должен применяться к экрану.
        assertEquals(500L, snapshot.effectiveIntervalMs)
    }

    @Test
    fun `background and catalogue are slower than the screen`() {
        val scheduler = scheduler()
        scheduler.setDemand(
            listOf(
                TelemetryScheduler.Demand(
                    SignalCatalog.Ids.RPM,
                    TelemetryScheduler.Tier.BACKGROUND,
                ),
            ),
        )
        assertTrue(scheduler.snapshot(SignalCatalog.Ids.RPM)!!.effectiveIntervalMs >= 1_000L)

        scheduler.setDemand(
            listOf(
                TelemetryScheduler.Demand(
                    SignalCatalog.Ids.FUEL_LEVEL,
                    TelemetryScheduler.Tier.CATALOG,
                ),
            ),
        )
        val catalogue = scheduler.snapshot(SignalCatalog.Ids.FUEL_LEVEL)!!
        assertEquals(SignalPriority.P2, catalogue.priority)
        assertTrue(catalogue.effectiveIntervalMs >= 1_000L)
    }

    @Test
    fun `timeouts back off exponentially and stop growing`() {
        val scheduler = scheduler()
        scheduler.setDemand(listOf(TelemetryScheduler.Demand(SignalCatalog.Ids.RPM)))

        scheduler.record(SignalCatalog.Ids.RPM, ReadingState.ERROR, 200L, now)
        val first = scheduler.nextAttemptAt(SignalCatalog.Ids.RPM)
        assertTrue("Первая пауза короткая: $first", first >= 1_000L)

        now = first
        scheduler.record(SignalCatalog.Ids.RPM, ReadingState.ERROR, 200L, now)
        val second = scheduler.nextAttemptAt(SignalCatalog.Ids.RPM)
        assertTrue("Пауза должна расти: $second", second - now > first - 0L)

        now = second
        scheduler.record(SignalCatalog.Ids.RPM, ReadingState.ERROR, 200L, now)
        val third = scheduler.nextAttemptAt(SignalCatalog.Ids.RPM)
        assertTrue("Пауза ограничена сверху", third - now <= 15_000L)
    }

    @Test
    fun `unsupported parameter is rechecked instead of dropped`() {
        val scheduler = scheduler()
        scheduler.setDemand(
            listOf(
                TelemetryScheduler.Demand(SignalCatalog.Ids.RPM),
                TelemetryScheduler.Demand(SignalCatalog.Ids.FUEL_LEVEL),
            ),
        )
        scheduler.record(
            SignalCatalog.Ids.RPM,
            ReadingState.UNSUPPORTED,
            150L,
            now,
            ReadingFailure.NEGATIVE,
        )
        scheduler.record(
            SignalCatalog.Ids.FUEL_LEVEL,
            ReadingState.UNSUPPORTED,
            150L,
            now,
            ReadingFailure.NEGATIVE,
        )
        // Горячий параметр перепроверяется раньше редкого, но оба — не чаще
        // раза в 15–30 секунд.
        assertEquals(15_000L, scheduler.nextAttemptAt(SignalCatalog.Ids.RPM))
        assertEquals(30_000L, scheduler.nextAttemptAt(SignalCatalog.Ids.FUEL_LEVEL))
        now = 30_000L
        assertNotNull(scheduler.nextDue(now))
    }

    @Test
    fun `successful read restores the base interval`() {
        val scheduler = scheduler()
        scheduler.setDemand(listOf(TelemetryScheduler.Demand(SignalCatalog.Ids.RPM)))
        scheduler.record(SignalCatalog.Ids.RPM, ReadingState.ERROR, 200L, now)
        now = 1_000L
        scheduler.record(SignalCatalog.Ids.RPM, ReadingState.OK, 200L, now)
        val snapshot = scheduler.snapshot(SignalCatalog.Ids.RPM)!!
        assertEquals(0, snapshot.consecutiveFailures)
        // Целевой интервал оборотов теперь 2 Гц, задержка 200 мс его не растягивает.
        assertEquals(500L, snapshot.effectiveIntervalMs)
        assertEquals(1_500L, scheduler.nextAttemptAt(SignalCatalog.Ids.RPM))
    }

    @Test
    fun `slow adapter raises intervals and keeps p0 moving`() {
        val scheduler = scheduler()
        scheduler.setDemand(
            listOf(
                TelemetryScheduler.Demand(SignalCatalog.Ids.RPM),
                TelemetryScheduler.Demand(SignalCatalog.Ids.FUEL_LEVEL),
            ),
        )
        scheduler.record(SignalCatalog.Ids.RPM, ReadingState.OK, 400L, 0L)
        val rpm = scheduler.snapshot(SignalCatalog.Ids.RPM)!!
        assertTrue(
            "Интервал должен учитывать задержку: ${rpm.effectiveIntervalMs}",
            rpm.effectiveIntervalMs >= 460L,
        )

        scheduler.record(SignalCatalog.Ids.FUEL_LEVEL, ReadingState.OK, 400L, 0L)
        val rare = scheduler.snapshot(SignalCatalog.Ids.FUEL_LEVEL)!!
        // При перегруженном адаптере редкий параметр замедляется, а не
        // забивает очередь.
        assertTrue("Редкий параметр не замедлился: ${rare.effectiveIntervalMs}",
            rare.effectiveIntervalMs > 2_000L)
    }

    @Test
    fun `waiting p2 is promoted instead of starved`() {
        val scheduler = scheduler()
        scheduler.setDemand(
            listOf(
                TelemetryScheduler.Demand(SignalCatalog.Ids.RPM),
                TelemetryScheduler.Demand(SignalCatalog.Ids.FUEL_LEVEL),
            ),
        )
        scheduler.record(SignalCatalog.Ids.FUEL_LEVEL, ReadingState.OK, 150L, 0L)
        var t = 0L
        repeat(61) {
            now = t
            scheduler.record(SignalCatalog.Ids.RPM, ReadingState.OK, 150L, t)
            t += 100L
        }
        now = 9_200L
        // P2 ждёт больше девяти секунд: его очередь наступает, хотя
        // быстрая полоса (обороты) всегда готова к опросу.
        assertEquals(SignalCatalog.Ids.FUEL_LEVEL, scheduler.nextDue(now)!!.id)
    }

    @Test
    fun `boost input gets the fast lane under load`() {
        val scheduler = scheduler()
        scheduler.setDemand(
            listOf(
                TelemetryScheduler.Demand(SignalCatalog.Ids.RPM),
                TelemetryScheduler.Demand(SignalCatalog.Ids.SPEED),
                TelemetryScheduler.Demand(SignalCatalog.Ids.MAP),
                TelemetryScheduler.Demand(SignalCatalog.Ids.COOLANT),
            ),
        )
        val counts = mutableMapOf<String, Int>()
        var t = 0L
        repeat(60) {
            val definition = scheduler.nextDueBatch(t).firstOrNull() ?: return@repeat
            counts[definition.id] = (counts[definition.id] ?: 0) + 1
            scheduler.record(definition.id, ReadingState.OK, 100L, t)
            t += 100L
        }
        val fast = counts[SignalCatalog.Ids.MAP] ?: 0
        val slow = counts[SignalCatalog.Ids.RPM] ?: 0
        assertTrue("Быстрая полоса не работает: $counts", fast > slow)
    }

    @Test
    fun `batch collects only dids of the same block`() {
        val scheduler = scheduler()
        val boost = SignalCatalog.get("vag.7e0.1057.boost")!!
        val charge = SignalCatalog.get("vag.7e0.2029.charge_specified")!!
        // Не передача: она P1 и увела бы пакет на свой блок.
        val inputSpeed = SignalCatalog.get("vag.7e1.2102.input_speed")!!
        scheduler.setDemand(
            listOf(
                TelemetryScheduler.Demand(boost.id),
                TelemetryScheduler.Demand(charge.id),
                TelemetryScheduler.Demand(inputSpeed.id),
            ),
        )
        val batch = scheduler.nextDueBatch(now)
        assertTrue(
            "Пакет должен состоять из DID одного блока: ${batch.map { it.id }}",
            batch.all { it.txId == 0x7E0 },
        )
        assertTrue("Пакет из двух DID", batch.size == 2)
        assertTrue(batch.mapNotNull { it.did }.toSet().size == 2)
    }

    @Test
    fun `obd pids are never batched`() {
        val scheduler = scheduler()
        scheduler.setDemand(
            listOf(
                TelemetryScheduler.Demand(SignalCatalog.Ids.RPM),
                TelemetryScheduler.Demand(SignalCatalog.Ids.SPEED),
            ),
        )
        assertEquals(1, scheduler.nextDueBatch(now).size)
    }

    @Test
    fun `queue depth reflects due parameters`() {
        val scheduler = scheduler()
        scheduler.setDemand(
            listOf(
                TelemetryScheduler.Demand(SignalCatalog.Ids.RPM),
                TelemetryScheduler.Demand(SignalCatalog.Ids.SPEED),
            ),
        )
        assertEquals(2, scheduler.queueDepth(now))

        scheduler.record(SignalCatalog.Ids.RPM, ReadingState.OK, 100L, now)
        scheduler.record(SignalCatalog.Ids.SPEED, ReadingState.OK, 100L, now)
        assertEquals(0, scheduler.queueDepth(now))

        now = 600L
        assertEquals(2, scheduler.queueDepth(now))
    }
}
