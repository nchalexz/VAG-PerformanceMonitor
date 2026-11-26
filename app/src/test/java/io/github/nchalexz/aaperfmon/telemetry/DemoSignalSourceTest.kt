package io.github.nchalexz.aaperfmon.telemetry

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoSignalSourceTest {

    @Test
    fun `demo model is internally consistent`() = runTest {
        val source = DemoSignalSource(clock = { 12_345L })
        suspend fun value(id: String): Double {
            val definition = SignalCatalog.get(id) ?: error("нет $id")
            val reading = source.read(definition)
            assertEquals(ReadingState.OK, reading.state)
            return reading.value!!
        }

        val speed = value(SignalCatalog.Ids.SPEED)
        val rpm = value(SignalCatalog.Ids.RPM)
        val map = value(SignalCatalog.Ids.MAP)
        val baro = value(SignalCatalog.Ids.BARO)
        val torque = value(SignalCatalog.Ids.TORQUE_NM)
        val power = value(SignalCatalog.Ids.POWER_KW)
        val gear = value(SignalCatalog.Ids.GEAR)

        assertTrue(speed in 0.0..160.0)
        assertTrue(rpm >= 650.0)
        assertEquals(map - baro, value(SignalCatalog.Ids.BOOST), 0.001)
        assertEquals(torque * rpm / 9549.0, power, 0.01)
        assertTrue(gear in 0.0..7.0)
    }

    @Test
    fun `demo starts on the mockup values`() = runTest {
        val source = DemoSignalSource(clock = { 0L })
        suspend fun value(id: String): Double =
            source.read(SignalCatalog.get(id)!!).value!!

        assertEquals(96.0, value(SignalCatalog.Ids.SPEED), 0.001)
        assertEquals(1840.0, value(SignalCatalog.Ids.RPM), 0.001)
        assertEquals(5.0, value(SignalCatalog.Ids.GEAR), 0.001)
        assertEquals(48.0, value(SignalCatalog.Ids.LOAD), 0.001)
        assertEquals(86.0, value(SignalCatalog.Ids.COOLANT), 0.001)
        assertEquals(95.0, value(SignalCatalog.Ids.OIL_TEMP), 0.001)
        assertEquals(62.0, value(SignalCatalog.Ids.BOOST), 0.001)
        assertEquals(42.5, value(SignalCatalog.Ids.MAF), 0.001)
        assertEquals(14.6, value(SignalCatalog.Ids.VOLTAGE), 0.001)
        assertEquals(58.0, value(SignalCatalog.Ids.FUEL_LEVEL), 0.001)
    }

    @Test
    fun `demo also feeds VAG signals`() = runTest {
        val source = DemoSignalSource(clock = { 0L })
        suspend fun value(id: String): Double =
            source.read(SignalCatalog.get(id)!!).value!!

        assertEquals(1.62, value("vag.7e0.1057.boost"), 0.001)
        assertEquals(82.0, value("vag.7e1.2104.atf_temp"), 0.001)
        assertEquals(5.0, value("vag.7e1.210f.gear"), 0.001)
        assertEquals(6.4, value("vag.7e0.114e.soot_measured"), 0.001)
        assertEquals(95.0, value("vag.7e0.11be.oil_temp"), 0.001)
    }

    @Test
    fun `unknown signal is reported unsupported`() = runTest {
        val source = DemoSignalSource()
        val reading = source.read(
            SignalDefinition(
                id = "unknown.signal",
                nameRu = "Неизвестный",
                nameEn = "Unknown",
                category = SignalCategory.ENGINE,
            ),
        )
        assertEquals(ReadingState.UNSUPPORTED, reading.state)
    }

    @Test
    fun `demo is labelled as demo`() {
        assertEquals("ДЕМО", DemoSignalSource().label)
        assertNotNull(SignalCatalog.get(SignalCatalog.Ids.RPM))
    }
}
