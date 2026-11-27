package io.github.nchalexz.aaperfmon.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Эталонные значения из SAE J1979 / ISO 15031-5 и открытого набора OBDb.
 *
 * Это проверка маппинга, а не совместимости: байты ответа заданы вручную,
 * а ожидаемое число взято из стандарта. Если кто-то поменяет формулу,
 * смещение или единицу, тест упадёт.
 */
class SignalMappingTest {

    private fun decode(id: String, vararg payload: Int): Double {
        val definition = requireNotNull(SignalCatalog.get(id)) { "Нет сигнала $id" }
        val decoder = requireNotNull(definition.decode) { "Нет декодера у $id" }
        return requireNotNull(decoder.decode(ByteArray(payload.size) { payload[it].toByte() })) {
            "Не декодировалось: $id"
        }
    }

    @Test
    fun `engine speed follows the standard quarter rpm formula`() {
        // 0x1AF8 = 6904 → 1726 об/мин.
        assertEquals(1726.0, decode(SignalCatalog.Ids.RPM, 0x1A, 0xF8), 0.001)
        assertEquals(0.0, decode(SignalCatalog.Ids.RPM, 0x00, 0x00), 0.0)
    }

    @Test
    fun `speed is one byte in km per hour`() {
        assertEquals(100.0, decode(SignalCatalog.Ids.SPEED, 0x64), 0.0)
        assertEquals(0.0, decode(SignalCatalog.Ids.SPEED, 0x00), 0.0)
    }

    @Test
    fun `temperatures use the minus forty offset`() {
        assertEquals(83.0, decode(SignalCatalog.Ids.COOLANT, 0x7B), 0.0)
        assertEquals(90.0, decode(SignalCatalog.Ids.OIL_TEMP, 0x82), 0.0)
        assertEquals(40.0, decode(SignalCatalog.Ids.INTAKE_AIR, 0x50), 0.0)
        assertEquals(30.0, decode(SignalCatalog.Ids.AMBIENT, 0x46), 0.0)
        // 0x00 — это −40 °C, а не «нет данных».
        assertEquals(-40.0, decode(SignalCatalog.Ids.COOLANT, 0x00), 0.0)
    }

    @Test
    fun `pressures are in kilopascals`() {
        assertEquals(100.0, decode(SignalCatalog.Ids.MAP, 0x64), 0.0)
        assertEquals(101.0, decode(SignalCatalog.Ids.BARO, 0x65), 0.0)
        // PID 0x0A: 3 кПа на отсчёт.
        assertEquals(300.0, decode(SignalCatalog.Ids.FUEL_PRESSURE, 0x64), 0.0)
        // PID 0x23: 10 кПа на отсчёт.
        assertEquals(42_000.0, decode(SignalCatalog.Ids.RAIL_PRESSURE, 0x10, 0x68), 0.001)
    }

    @Test
    fun `percentages use the 255 divisor`() {
        assertEquals(50.196, decode(SignalCatalog.Ids.LOAD, 0x80), 0.001)
        assertEquals(50.196, decode(SignalCatalog.Ids.THROTTLE, 0x80), 0.001)
        assertEquals(50.196, decode(SignalCatalog.Ids.PEDAL, 0x80), 0.001)
        assertEquals(50.196, decode(SignalCatalog.Ids.FUEL_LEVEL, 0x80), 0.001)
        // PID 0x43 — два байта: 0x0080 = 128 → 50,196 %.
        assertEquals(50.196, decode(SignalCatalog.Ids.ABS_LOAD, 0x00, 0x80), 0.001)
    }

    @Test
    fun `air flow and fuel rate use their standard scales`() {
        // 0x1A90 = 6800 → 68,00 г/с.
        assertEquals(68.0, decode(SignalCatalog.Ids.MAF, 0x1A, 0x90), 0.001)
        // 0x0AF0 = 2800 → 140,00 л/ч.
        assertEquals(140.0, decode(SignalCatalog.Ids.FUEL_RATE, 0x0A, 0xF0), 0.001)
    }

    @Test
    fun `voltage is millivolts in two bytes`() {
        assertEquals(14.0, decode(SignalCatalog.Ids.VOLTAGE, 0x36, 0xB0), 0.001)
    }

    @Test
    fun `torque percentages use the minus one hundred twenty five bias`() {
        assertEquals(0.0, decode(SignalCatalog.Ids.TORQUE_PCT, 125), 0.0)
        assertEquals(100.0, decode(SignalCatalog.Ids.TORQUE_PCT, 225), 0.0)
        assertEquals(376.0, decode(SignalCatalog.Ids.TORQUE_REF, 0x01, 0x78), 0.001)
    }

    @Test
    fun `fuel trims and lambda follow the standard formulas`() {
        assertEquals(0.0, decode(SignalCatalog.Ids.TRIM_SHORT, 128), 0.001)
        assertEquals(25.0, decode(SignalCatalog.Ids.TRIM_SHORT, 160), 0.001)
        assertEquals(-25.0, decode(SignalCatalog.Ids.TRIM_SHORT, 96), 0.001)
        assertEquals(1.0, decode(SignalCatalog.Ids.LAMBDA, 0x80, 0x00), 0.000001)
    }

    @Test
    fun `calculated boost subtracts atmospheric pressure`() {
        val map = SignalCatalog.get(SignalCatalog.Ids.MAP)!!
        val baro = SignalCatalog.get(SignalCatalog.Ids.BARO)!!
        val boost = SignalCatalog.get(SignalCatalog.Ids.BOOST)!!
        assertEquals(ComputedKind.BOOST_KPA, boost.computed!!.kind)
        assertEquals(listOf(map.id, baro.id), boost.computed!!.inputs)
        // 200 кПа во впуске при 101 кПа снаружи — это 99 кПа избытка.
        assertEquals(99.0, 200.0 - 101.0, 0.001)
    }

    @Test
    fun `vag boost did is absolute bar with thousand divisor`() {
        val definition = SignalCatalog.get("vag.7e0.1057.boost")!!
        // 0x0654 = 1620 → 1,62 бар по формуле OBDb (mul 1, div 1000).
        assertEquals(1.62, definition.uds!!.decode(byteArrayOf(0x06.toByte(), 0x54.toByte()))!!, 0.000001)
        assertEquals(2.5, definition.uds!!.decode(byteArrayOf(0x09.toByte(), 0xC4.toByte()))!!, 0.000001)
    }

    @Test
    fun `vag charge pressure uses the 0_08 scale`() {
        val definition = SignalCatalog.get("vag.7e0.2029.charge_specified")!!
        // 0x4F1A = 20250 → 1620,0 гПа.
        assertEquals(1_620.0, definition.uds!!.decode(byteArrayOf(0x4F.toByte(), 0x1A.toByte()))!!, 0.001)
    }

    @Test
    fun `gear did maps park reverse neutral and forward gears`() {
        val definition = SignalCatalog.get("vag.7e1.210f.gear")!!
        // Смещение −2 из OBDb: 0 → −2 (P), 1 → −1 (R), 2 → 0 (N), 3 → 1.
        assertEquals(-2.0, definition.uds!!.decode(byteArrayOf(0x00.toByte()))!!, 0.0)
        assertEquals(-1.0, definition.uds!!.decode(byteArrayOf(0x01.toByte()))!!, 0.0)
        assertEquals(0.0, definition.uds!!.decode(byteArrayOf(0x02.toByte()))!!, 0.0)
        assertEquals(1.0, definition.uds!!.decode(byteArrayOf(0x03.toByte()))!!, 0.0)
        assertEquals("P", definition.format(-2.0))
        assertEquals("R", definition.format(-1.0))
        assertEquals("N", definition.format(0.0))
        assertEquals("1", definition.format(1.0))
    }

    @Test
    fun `every measured obd signal decodes a plausible zero payload`() {
        for (definition in SignalCatalog.measured) {
            val value = definition.decode?.decode(ByteArray(8))
            assertNotNull("Не декодируется: ${definition.id}", value)
            assertTrue("Не конечное число: ${definition.id}", value!!.isFinite())
        }
    }
}
