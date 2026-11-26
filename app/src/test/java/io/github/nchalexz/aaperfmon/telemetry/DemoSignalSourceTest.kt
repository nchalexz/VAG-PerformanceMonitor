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
