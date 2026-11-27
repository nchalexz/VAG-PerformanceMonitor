package io.github.nchalexz.aaperfmon.telemetry

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryEngineTest {

    private class FakeSource(
        private val readings: Map<String, Reading>,
    ) : SignalSource {
        override val label = "тест"
        val order = mutableListOf<String>()
        val counts = mutableMapOf<String, Int>()

        override suspend fun read(definition: SignalDefinition): Reading {
            order += definition.id
            counts[definition.id] = (counts[definition.id] ?: 0) + 1
            return readings[definition.id] ?: Reading(null, ReadingState.UNSUPPORTED)
        }
    }

    @Test
    fun `dashboard signals are polled and computed values appear`() = runTest {
        val source = FakeSource(
            mapOf(
                SignalCatalog.Ids.MAP to Reading(150.0, ReadingState.OK),
                SignalCatalog.Ids.BARO to Reading(100.0, ReadingState.OK),
                SignalCatalog.Ids.RPM to Reading(3000.0, ReadingState.OK),
                SignalCatalog.Ids.TORQUE_PCT to Reading(50.0, ReadingState.OK),
                SignalCatalog.Ids.TORQUE_REF to Reading(400.0, ReadingState.OK),
            ),
        )
        val engine = TelemetryEngine(
            source = source,
            scope = this,
            clock = { testScheduler.currentTime },
        )
        engine.setDashboard(
            listOf(
                SignalCatalog.Ids.MAP,
                SignalCatalog.Ids.BARO,
                SignalCatalog.Ids.RPM,
                SignalCatalog.Ids.TORQUE_PCT,
                SignalCatalog.Ids.TORQUE_REF,
            ),
        )
        engine.start()
        advanceTimeBy(2_000)
        runCurrent()

        val values = engine.values.value
        assertEquals(150.0, values[SignalCatalog.Ids.MAP]!!.value!!, 0.001)
        assertEquals(50.0, values[SignalCatalog.Ids.BOOST]!!.value!!, 0.001)
        assertEquals(200.0, values[SignalCatalog.Ids.TORQUE_NM]!!.value!!, 0.001)
        assertEquals(
            200.0 * 3000.0 / 9549.0,
            values[SignalCatalog.Ids.POWER_KW]!!.value!!,
            0.01,
        )
        engine.stop()
    }

    @Test
    fun `dashboard is polled before the catalogue`() = runTest {
        val source = FakeSource(
            mapOf(SignalCatalog.Ids.RPM to Reading(1000.0, ReadingState.OK)),
        )
        val engine = TelemetryEngine(
            source = source,
            scope = this,
            clock = { testScheduler.currentTime },
        )
        engine.setDashboard(listOf(SignalCatalog.Ids.RPM))
        engine.setCatalog(SignalCatalog.all.map { it.id })
        engine.setCatalogEnabled(true)
        engine.start()
        advanceTimeBy(100)
        runCurrent()

        assertTrue("Источник не опрашивался", source.order.isNotEmpty())
        // Первым идёт быстрый сигнал приборки (обороты или давление впуска),
        // а не параметр каталога.
        assertTrue(
            "Приборка не получила первый слот: ${source.order.first()}",
            source.order.first() in setOf(
                SignalCatalog.Ids.RPM,
                SignalCatalog.Ids.MAP,
            ),
        )
        engine.stop()
    }

    @Test
    fun `value becomes stale without fresh reads`() = runTest {
        val source = FakeSource(
            mapOf(SignalCatalog.Ids.RPM to Reading(1000.0, ReadingState.OK)),
        )
        val engine = TelemetryEngine(
            source = source,
            scope = this,
            clock = { testScheduler.currentTime },
        )
        engine.setDashboard(listOf(SignalCatalog.Ids.RPM))
        engine.start()
        advanceTimeBy(500)
        runCurrent()
        engine.stop()

        val value = engine.values.value[SignalCatalog.Ids.RPM]
        assertNotNull(value)
        val definition = SignalCatalog.get(SignalCatalog.Ids.RPM)!!
        advanceTimeBy(60_000)
        assertTrue(value!!.isStale(testScheduler.currentTime, definition))
    }

    @Test
    fun `fuel consumption is computed from rate and speed`() = runTest {
        val source = FakeSource(
            mapOf(
                SignalCatalog.Ids.FUEL_RATE to Reading(5.0, ReadingState.OK),
                SignalCatalog.Ids.SPEED to Reading(100.0, ReadingState.OK),
            ),
        )
        val engine = TelemetryEngine(
            source = source,
            scope = this,
            clock = { testScheduler.currentTime },
        )
        engine.setDashboard(listOf(SignalCatalog.Ids.FUEL_L100))
        engine.start()
        advanceTimeBy(2_000)
        runCurrent()
        engine.stop()

        assertEquals(5.0, engine.values.value[SignalCatalog.Ids.FUEL_L100]!!.value!!, 0.001)
    }

    @Test
    fun `fuel consumption is undefined at standstill`() = runTest {
        val source = FakeSource(
            mapOf(
                SignalCatalog.Ids.FUEL_RATE to Reading(5.0, ReadingState.OK),
                SignalCatalog.Ids.SPEED to Reading(0.0, ReadingState.OK),
            ),
        )
        val engine = TelemetryEngine(
            source = source,
            scope = this,
            clock = { testScheduler.currentTime },
        )
        engine.setDashboard(listOf(SignalCatalog.Ids.FUEL_L100))
        engine.start()
        advanceTimeBy(2_000)
        runCurrent()
        engine.stop()

        val value = engine.values.value[SignalCatalog.Ids.FUEL_L100]!!
        assertEquals(ReadingState.UNSUPPORTED, value.state)
        assertEquals(null, value.value)
    }

    @Test
    fun `unsupported signal is retried with backoff`() = runTest {
        val source = FakeSource(emptyMap())
        val engine = TelemetryEngine(
            source = source,
            scope = this,
            clock = { testScheduler.currentTime },
        )
        engine.setDashboard(listOf(SignalCatalog.Ids.RPM))
        engine.start()
        advanceTimeBy(5_000)
        runCurrent()
        engine.stop()

        val attempts = source.counts[SignalCatalog.Ids.RPM] ?: 0
        assertTrue("Слишком много повторов: $attempts", attempts in 1..12)
        assertEquals(
            ReadingState.UNSUPPORTED,
            engine.values.value[SignalCatalog.Ids.RPM]!!.state,
        )
    }
}
