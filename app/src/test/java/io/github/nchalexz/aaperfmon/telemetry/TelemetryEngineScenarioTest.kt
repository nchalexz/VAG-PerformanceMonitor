package io.github.nchalexz.aaperfmon.telemetry

import io.github.nchalexz.aaperfmon.cockpit.ui.MetricRegistry
import io.github.nchalexz.aaperfmon.cockpit.ui.MetricState
import io.github.nchalexz.aaperfmon.cockpit.ui.readMetric
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сценарии из требований к телеметрии, проверенные на поддельном источнике.
 *
 * Ни один из этих тестов не подтверждает совместимость с автомобилем:
 * они проверяют правила отображения, сроки актуальности и поведение
 * планировщика при ошибках. Реальная частота измеряется на машине.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryEngineScenarioTest {

    /** Источник с программируемым ответом и задержкой. */
    private class FakeSource(
        private val delayMs: Long = 0,
        private val answer: (String, Long) -> Reading,
    ) : SignalSource {
        override val label = "тест"

        var currentTime: () -> Long = { 0L }
        val calls = mutableListOf<String>()
        val counts = mutableMapOf<String, Int>()
        var maxConcurrent = 0
        private var active = 0

        override suspend fun read(definition: SignalDefinition): Reading {
            active++
            maxConcurrent = maxOf(maxConcurrent, active)
            try {
                if (delayMs > 0) delay(delayMs)
                calls += definition.id
                counts[definition.id] = (counts[definition.id] ?: 0) + 1
                return answer(definition.id, currentTime())
            } finally {
                active--
            }
        }
    }

    private fun TestScope.engine(source: FakeSource): TelemetryEngine {
        source.currentTime = { testScheduler.currentTime }
        return TelemetryEngine(
            source = source,
            scope = this,
            clock = { testScheduler.currentTime },
        )
    }

    private fun definition(id: String): SignalDefinition = SignalCatalog.get(id)!!

    @Test
    fun `successful read produces a valid value`() = runTest {
        val source = FakeSource { id, _ ->
            if (id == SignalCatalog.Ids.RPM) Reading(1_840.0, ReadingState.OK)
            else Reading(null, ReadingState.UNSUPPORTED)
        }
        val engine = engine(source)
        engine.setDashboard(listOf(SignalCatalog.Ids.RPM))
        engine.start()
        advanceTimeBy(300)
        runCurrent()

        val value = engine.values.value[SignalCatalog.Ids.RPM]!!
        assertEquals(1_840.0, value.value!!, 0.001)
        assertEquals(ReadingState.OK, value.state)
        assertEquals(
            SignalStatus.VALID,
            value.statusAt(testScheduler.currentTime, definition(SignalCatalog.Ids.RPM)),
        )
        engine.stop()
    }

    @Test
    fun `parameter that was never read shows never read`() = runTest {
        val source = FakeSource { _, _ -> Reading(null, ReadingState.UNSUPPORTED) }
        val engine = engine(source)
        engine.setDashboard(listOf(SignalCatalog.Ids.RPM))
        engine.start()
        advanceTimeBy(1_000)
        runCurrent()

        val value = engine.values.value[SignalCatalog.Ids.RPM]!!
        assertFalse(value.hasEverBeenRead)
        assertEquals(
            SignalStatus.NEVER_READ,
            value.statusAt(testScheduler.currentTime, definition(SignalCatalog.Ids.RPM)),
        )
        engine.stop()
    }

    @Test
    fun `single timeout does not clear the value`() = runTest {
        var succeed = true
        val source = FakeSource { id, _ ->
            if (id == SignalCatalog.Ids.RPM && succeed) {
                succeed = false
                Reading(42.0, ReadingState.OK)
            } else {
                Reading(null, ReadingState.ERROR, ReadingFailure.TIMEOUT)
            }
        }
        val engine = engine(source)
        engine.setDashboard(listOf(SignalCatalog.Ids.RPM))
        engine.start()
        advanceTimeBy(400)
        runCurrent()

        val fresh = engine.values.value[SignalCatalog.Ids.RPM]!!
        assertEquals(42.0, fresh.value!!, 0.001)
        assertEquals(
            SignalStatus.VALID,
            fresh.statusAt(testScheduler.currentTime, definition(SignalCatalog.Ids.RPM)),
        )
        assertTrue(fresh.consecutiveFailures > 0)

        engine.stop()
        advanceTimeBy(6_000)
        runCurrent()
        val stale = engine.values.value[SignalCatalog.Ids.RPM]!!
        assertEquals(42.0, stale.value!!, 0.001)
        assertEquals(
            SignalStatus.STALE,
            stale.statusAt(testScheduler.currentTime, definition(SignalCatalog.Ids.RPM)),
        )
    }

    @Test
    fun `frequent parameter becomes stale after five seconds`() = runTest {
        val source = FakeSource { _, _ -> Reading(1_000.0, ReadingState.OK) }
        val engine = engine(source)
        engine.setDashboard(listOf(SignalCatalog.Ids.RPM))
        engine.start()
        advanceTimeBy(200)
        runCurrent()
        engine.stop()

        val value = engine.values.value[SignalCatalog.Ids.RPM]!!
        val definition = definition(SignalCatalog.Ids.RPM)
        advanceTimeBy(4_000)
        assertEquals(SignalStatus.VALID, value.statusAt(testScheduler.currentTime, definition))
        advanceTimeBy(1_200)
        assertEquals(SignalStatus.STALE, value.statusAt(testScheduler.currentTime, definition))
    }

    @Test
    fun `rare parameter becomes stale after thirty seconds`() = runTest {
        val source = FakeSource { _, _ -> Reading(58.0, ReadingState.OK) }
        val engine = engine(source)
        engine.setDashboard(listOf(SignalCatalog.Ids.FUEL_LEVEL))
        engine.start()
        advanceTimeBy(200)
        runCurrent()
        engine.stop()

        val value = engine.values.value[SignalCatalog.Ids.FUEL_LEVEL]!!
        val definition = definition(SignalCatalog.Ids.FUEL_LEVEL)
        assertEquals(SignalPriority.P2, definition.priority)
        advanceTimeBy(25_000)
        assertEquals(SignalStatus.VALID, value.statusAt(testScheduler.currentTime, definition))
        advanceTimeBy(6_000)
        assertEquals(SignalStatus.STALE, value.statusAt(testScheduler.currentTime, definition))
    }

    @Test
    fun `value recovers after a series of errors`() = runTest {
        val source = FakeSource { _, now ->
            when {
                now < 1_000 -> Reading(42.0, ReadingState.OK)
                now < 6_000 -> Reading(null, ReadingState.ERROR, ReadingFailure.TIMEOUT)
                else -> Reading(43.0, ReadingState.OK)
            }
        }
        val engine = engine(source)
        engine.setDashboard(listOf(SignalCatalog.Ids.RPM))
        engine.start()
        advanceTimeBy(20_000)
        runCurrent()

        val value = engine.values.value[SignalCatalog.Ids.RPM]!!
        assertEquals(43.0, value.value!!, 0.001)
        assertEquals(0, value.consecutiveFailures)
        assertEquals(
            SignalStatus.VALID,
            value.statusAt(testScheduler.currentTime, definition(SignalCatalog.Ids.RPM)),
        )
        engine.stop()
    }

    @Test
    fun `zero is a valid value, not a dash`() = runTest {
        val source = FakeSource { _, _ -> Reading(0.0, ReadingState.OK) }
        val engine = engine(source)
        engine.setDashboard(listOf(SignalCatalog.Ids.RPM))
        engine.start()
        advanceTimeBy(300)
        runCurrent()

        val value = engine.values.value[SignalCatalog.Ids.RPM]!!
        assertEquals(0.0, value.value!!, 0.0)
        val reading = readMetric(
            MetricRegistry.rpm,
            engine.values.value,
            testScheduler.currentTime,
            canShowData = true,
        )
        assertEquals(MetricState.OK, reading.state)
        assertEquals("0", reading.text)
        engine.stop()
    }

    @Test
    fun `complete connection loss makes values stale`() = runTest {
        val source = FakeSource { _, now ->
            if (now < 1_000) Reading(86.0, ReadingState.OK)
            else Reading(null, ReadingState.ERROR, ReadingFailure.TRANSPORT)
        }
        val engine = engine(source)
        engine.setDashboard(listOf(SignalCatalog.Ids.COOLANT, SignalCatalog.Ids.RPM))
        engine.start()
        advanceTimeBy(700)
        runCurrent()
        assertEquals(
            SignalStatus.VALID,
            engine.values.value[SignalCatalog.Ids.COOLANT]!!
                .statusAt(testScheduler.currentTime, definition(SignalCatalog.Ids.COOLANT)),
        )

        advanceTimeBy(7_000)
        runCurrent()
        for (id in listOf(SignalCatalog.Ids.COOLANT, SignalCatalog.Ids.RPM)) {
            assertEquals(
                SignalStatus.STALE,
                engine.values.value[id]!!.statusAt(testScheduler.currentTime, definition(id)),
            )
        }
        assertTrue(engine.metrics.snapshot(testScheduler.currentTime).transportErrors > 0)
        engine.stop()
    }

    @Test
    fun `slow adapter is polled one request at a time`() = runTest {
        val source = FakeSource(delayMs = 400) { _, _ -> Reading(1.0, ReadingState.OK) }
        val engine = engine(source)
        engine.setDashboard(
            listOf(
                SignalCatalog.Ids.RPM,
                SignalCatalog.Ids.SPEED,
                SignalCatalog.Ids.COOLANT,
                SignalCatalog.Ids.FUEL_LEVEL,
            ),
        )
        engine.start()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals("Запросы ушли параллельно", 1, source.maxConcurrent)
        listOf(
            SignalCatalog.Ids.RPM,
            SignalCatalog.Ids.SPEED,
            SignalCatalog.Ids.COOLANT,
            SignalCatalog.Ids.FUEL_LEVEL,
        ).forEach { id ->
            assertTrue("Параметр $id не опрашивался", (source.counts[id] ?: 0) > 0)
        }
        val rpm = engine.schedule().first { it.signalId == SignalCatalog.Ids.RPM }
        assertTrue("Интервал не учёл задержку: ${rpm.effectiveIntervalMs}",
            rpm.effectiveIntervalMs >= 460L)
        assertTrue(engine.metrics.snapshot(testScheduler.currentTime).averageLatencyMs >= 400.0)
        engine.stop()
    }

    @Test
    fun `unsupported did is rechecked rarely, not every cycle`() = runTest {
        val source = FakeSource { _, _ ->
            Reading(null, ReadingState.UNSUPPORTED, ReadingFailure.NEGATIVE)
        }
        val engine = engine(source)
        val signal = "vag.7e0.1057.boost"
        engine.setDashboard(listOf(signal))
        engine.start()
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(
            "Слишком частые перепроверки: ${source.counts[signal]}",
            (source.counts[signal] ?: 0) in 1..3,
        )
        val value = engine.values.value[signal]!!
        assertEquals(ReadingState.UNSUPPORTED, value.state)
        assertEquals(
            SignalStatus.NEVER_READ,
            value.statusAt(testScheduler.currentTime, definition(signal)),
        )
        engine.stop()
    }

    @Test
    fun `queue does not grow without bound under overload`() = runTest {
        val ids = listOf(
            SignalCatalog.Ids.RPM,
            SignalCatalog.Ids.SPEED,
            SignalCatalog.Ids.MAP,
            SignalCatalog.Ids.BARO,
            SignalCatalog.Ids.COOLANT,
            SignalCatalog.Ids.OIL_TEMP,
            SignalCatalog.Ids.LOAD,
            SignalCatalog.Ids.THROTTLE,
            SignalCatalog.Ids.FUEL_RATE,
            SignalCatalog.Ids.FUEL_LEVEL,
            SignalCatalog.Ids.VOLTAGE,
            SignalCatalog.Ids.INTAKE_AIR,
        )
        val source = FakeSource(delayMs = 300) { _, _ -> Reading(1.0, ReadingState.OK) }
        val engine = engine(source)
        engine.setDashboard(ids)
        engine.start()
        // Медленный адаптер: 12 параметров, 300 мс на запрос. За 30 секунд
        // редкие параметры обязаны дождаться опроса, а очередь — не расти.
        advanceTimeBy(30_000)
        runCurrent()

        val snapshot = engine.metrics.snapshot(testScheduler.currentTime)
        assertTrue("Очередь разрослась: ${snapshot.queueDepth}", snapshot.queueDepth <= ids.size)
        assertTrue("Слишком много запросов: ${snapshot.requests}", snapshot.requests <= 140)
        ids.forEach { id ->
            assertTrue("Параметр $id не дождался опроса", (source.counts[id] ?: 0) > 0)
        }
        engine.stop()
    }

    @Test
    fun `several subscribers share one schedule`() = runTest {
        val source = FakeSource { _, _ -> Reading(2.0, ReadingState.OK) }
        val engine = engine(source)
        engine.setDashboard(listOf(SignalCatalog.Ids.RPM))
        engine.setBackground(listOf(SignalCatalog.Ids.COOLANT, SignalCatalog.Ids.RPM))
        engine.setCatalog(SignalCatalog.all.map { it.id })
        engine.setCatalogEnabled(true)
        engine.start()
        advanceTimeBy(3_000)
        runCurrent()

        assertNotNull(engine.values.value[SignalCatalog.Ids.RPM])
        assertNotNull(engine.values.value[SignalCatalog.Ids.COOLANT])
        // Каталог тоже получает шину, но с меньшим приоритетом.
        assertTrue((source.counts[SignalCatalog.Ids.FUEL_LEVEL] ?: 0) > 0)
        // Один и тот же сигнал не опрашивается дважды в одном такте:
        // подписки приборки и профиля сливаются в одну очередь.
        assertEquals(1, engine.schedule().count { it.signalId == SignalCatalog.Ids.RPM })
        engine.stop()
    }
}
