package io.github.nchalexz.aaperfmon.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UdsValueSpecTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `big endian word scales from the source formula`() {
        // OBDb 1057: raw / 1000 = bar.
        val spec = UdsValueSpec(bitLength = 16, div = 1000.0)
        assertEquals(1.664, spec.decode(bytes(0x06, 0x80))!!, 0.000001)
    }

    @Test
    fun `little endian word is read byte swapped`() {
        // DSG 2104 variant 2: 16-bit little-endian, 0x0052 = 82 °C.
        val spec = UdsValueSpec(bitLength = 16, signed = true, littleEndian = true)
        assertEquals(82.0, spec.decode(bytes(0x52, 0x00))!!, 0.000001)
    }

    @Test
    fun `signed value keeps the sign`() {
        // 10FF: signed word / 100 = mg/stroke.
        val spec = UdsValueSpec(bitLength = 16, signed = true, div = 100.0)
        assertEquals(-1.0, spec.decode(bytes(0xFF, 0x9C))!!, 0.000001)
    }

    @Test
    fun `thirty two bit current is not truncated`() {
        // 2A09: signed 32-bit / 1000 = A.
        val spec = UdsValueSpec(bitLength = 32, signed = true, div = 1000.0)
        assertEquals(12.5, spec.decode(bytes(0x00, 0x00, 0x30, 0xD4))!!, 0.000001)
    }

    @Test
    fun `bit offset reads a second value from the same DID`() {
        // 1118: два 16-битных значения в одном ответе, младшим байтом вперёд.
        val second = UdsValueSpec(
            bitOffset = 16,
            bitLength = 16,
            signed = false,
            littleEndian = true,
        )
        assertEquals(88.0, second.decode(bytes(0x5A, 0x00, 0x58, 0x00))!!, 0.000001)
    }

    @Test
    fun `short answer is no answer`() {
        val spec = UdsValueSpec(bitLength = 16)
        assertNull(spec.decode(bytes(0x01)))
    }

    @Test
    fun `add and multiplier follow the description`() {
        // 2A07: raw / 1000 + 4 = V.
        val spec = UdsValueSpec(bitLength = 16, div = 1000.0, add = 4.0)
        assertEquals(14.52, spec.decode(bytes(0x29, 0x18))!!, 0.000001)
    }
}
