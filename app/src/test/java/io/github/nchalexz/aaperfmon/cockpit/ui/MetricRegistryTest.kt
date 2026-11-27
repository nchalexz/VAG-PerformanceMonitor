package io.github.nchalexz.aaperfmon.cockpit.ui

import io.github.nchalexz.aaperfmon.telemetry.ReadingState
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.SignalValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricRegistryTest {

    @Test
    fun `main screen keeps three gauges and five tiles`() {
        assertEquals(3, MetricRegistry.gauges.size)
        assertEquals(5, MetricRegistry.mainTiles.size)
    }

    @Test
    fun `gauges show fast values and tiles show slow ones`() {
        assertEquals(
            listOf("engine.rpm", "vehicle.speed", "engine.boost_kpa"),
            MetricRegistry.gauges.map { it.signalId },
        )
        assertEquals(
            listOf("engine.coolant_c", "engine.oil_c", "vehicle.gear", "fuel.l100_km", "fuel.level_pct"),
            MetricRegistry.mainTiles.map { it.signalId },
        )
    }

    @Test
    fun `tachometer scale is in rpms, not in fractions`() {
        assertEquals(0.0, MetricRegistry.rpm.scaleMin, 0.0)
        assertEquals(8000.0, MetricRegistry.rpm.scaleMax, 0.0)
        assertEquals(4000.0, MetricRegistry.rpm.display(4000.0), 0.0)
    }

    @Test
    fun `boost is shown in bar with a european comma`() {
        assertEquals("0,62", MetricRegistry.boost.format(62.0))
        assertEquals("1,90", MetricRegistry.boost.format(190.0))
    }

    @Test
    fun `oil gauge can fall back to a VAG DID`() {
        val now = 1_000L
        val values = mapOf(
            "vag.7e0.11be.oil_temp" to SignalValue(95.0, now, ReadingState.OK, "тест"),
        )
        val reading = readMetric(MetricRegistry.oil, values, now, canShowData = true)
        assertEquals(MetricState.OK, reading.state)
        assertEquals("95", reading.text)
    }

    @Test
    fun `oil gauge reports no data instead of zero`() {
        val reading = readMetric(MetricRegistry.oil, emptyMap(), 1_000L, canShowData = true)
        // Нет ни одного успешного чтения — это N/A, а не прочерк.
        assertEquals(MetricState.NEVER_READ, reading.state)
        assertEquals("N/A", reading.text)
    }

    @Test
    fun `gear shows selector positions and forward gears`() {
        val now = 1_000L
        fun text(raw: Double): String = readMetric(
            MetricRegistry.gear,
            mapOf("vehicle.gear" to SignalValue(raw, now, ReadingState.OK, "тест")),
            now,
            canShowData = true,
        ).text

        // Смещение из OBDb: 0 после смещения — нейтраль, отрицательные —
        // парковка и задний ход. На автомобиле ещё не сверялось.
        assertEquals("P", text(-2.0))
        assertEquals("R", text(-1.0))
        assertEquals("N", text(0.0))
        assertEquals("5", text(5.0))
        assertEquals("—", text(99.0))
    }

    @Test
    fun `vag gear did is formatted as a selector position`() {
        val definition = SignalCatalog.get("vag.7e1.210f.gear")!!
        assertEquals("R", definition.format(-1.0))
        assertEquals("N", definition.format(0.0))
        assertEquals("3", definition.format(3.0))
    }

    @Test
    fun `gearbox tab is populated by VAG signals`() {
        val gearbox = MetricRegistry.category(DisplayCategory.GEARBOX, includeVirtual = true)
        assertTrue(gearbox.any { it.signalId.startsWith("vag.") })
    }

    @Test
    fun `every metric points to an existing signal`() {
        for (spec in MetricRegistry.all()) {
            assertNotNull("Нет ${spec.signalId}", SignalCatalog.get(spec.signalId))
        }
    }
}
