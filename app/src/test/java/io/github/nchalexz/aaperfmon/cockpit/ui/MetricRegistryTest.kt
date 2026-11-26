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
        assertEquals(MetricState.WAITING, reading.state)
        assertEquals("—", reading.text)
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
