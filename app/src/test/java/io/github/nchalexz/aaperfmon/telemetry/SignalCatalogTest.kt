package io.github.nchalexz.aaperfmon.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalCatalogTest {

    @Test
    fun `identifiers are unique`() {
        val duplicates = SignalCatalog.all.groupBy { it.id }.filterValues { it.size > 1 }
        assertTrue("Повторяющиеся id: ${duplicates.keys}", duplicates.isEmpty())
    }

    @Test
    fun `measured signals have pid and decoder`() {
        assertTrue(SignalCatalog.measured.isNotEmpty())
        for (definition in SignalCatalog.measured) {
            assertNotNull("${definition.id}: нет PID", definition.pid)
            assertNotNull("${definition.id}: нет декодера", definition.decode)
            assertTrue("${definition.id}: нулевая частота", definition.pollHz > 0.0)
        }
    }

    @Test
    fun `computed signals reference existing inputs`() {
        for (definition in SignalCatalog.computed) {
            val spec = definition.computed!!
            assertEquals("${definition.id}: ожидалось два входа", 2, spec.inputs.size)
            for (input in spec.inputs) {
                assertNotNull("${definition.id}: нет входа $input", SignalCatalog.get(input))
            }
        }
    }

    @Test
    fun `every dashboard widget points to a catalog signal`() {
        for (dashboard in Dashboards.all) {
            for (widget in dashboard.widgets) {
                val definition = SignalCatalog.get(widget.signalId)
                assertNotNull("${dashboard.id}/${widget.id}: нет сигнала", definition)
                assertTrue("${dashboard.id}: пустой диапазон", widget.max > widget.min)
            }
        }
    }

    @Test
    fun `standard frames decode through the catalog`() {
        val rpm = SignalCatalog.get(SignalCatalog.Ids.RPM)!!
        assertEquals(1726.0, rpm.decode!!.decode(byteArrayOf(0x1A, 0xF8.toByte()))!!, 0.001)

        val speed = SignalCatalog.get(SignalCatalog.Ids.SPEED)!!
        assertEquals(100.0, speed.decode!!.decode(byteArrayOf(100))!!, 0.001)

        val coolant = SignalCatalog.get(SignalCatalog.Ids.COOLANT)!!
        assertEquals(90.0, coolant.decode!!.decode(byteArrayOf(130.toByte()))!!, 0.001)

        val voltage = SignalCatalog.get(SignalCatalog.Ids.VOLTAGE)!!
        assertEquals(13.8, voltage.decode!!.decode(byteArrayOf(0x35, 0xE8.toByte()))!!, 0.001)
    }

    @Test
    fun `boost is the fast lane and the temperatures are sampled at 2 Hz`() {
        val boost = SignalCatalog.get(SignalCatalog.Ids.MAP)!!
        assertEquals(20.0, boost.pollHz, 0.0)
        assertTrue("MAP должен идти вне очереди", boost.fast)
        assertEquals(2.0, SignalCatalog.get(SignalCatalog.Ids.COOLANT)!!.pollHz, 0.0)
        assertEquals(2.0, SignalCatalog.get(SignalCatalog.Ids.OIL_TEMP)!!.pollHz, 0.0)
        assertEquals(2.0, SignalCatalog.get(SignalCatalog.Ids.INTAKE_AIR)!!.pollHz, 0.0)
    }

    @Test
    fun `verification is not overclaimed`() {
        // Стандартные PID не могут быть CONFIRMED без живой машины.
        val confirmed = SignalCatalog.all.filter { it.verification == Verification.CONFIRMED }
        assertTrue("Неожиданно подтверждённые сигналы: ${confirmed.map { it.id }}", confirmed.isEmpty())
    }
}
