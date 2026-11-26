package io.github.nchalexz.aaperfmon.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValueSpecTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `rpm decoded from two bytes with quarter scale`() {
        val spec = ValueSpec(length = 2, scale = 0.25)
        assertEquals(1726.0, spec.decode(bytes(0x1A, 0xF8))!!, 0.0001)
    }

    @Test
    fun `coolant uses minus forty bias`() {
        val spec = ValueSpec(length = 1, bias = -40.0)
        assertEquals(50.0, spec.decode(bytes(90))!!, 0.0001)
    }

    @Test
    fun `signed value decodes as negative`() {
        val spec = ValueSpec(length = 2, signed = true)
        assertEquals(-1.0, spec.decode(bytes(0xFF, 0xFF))!!, 0.0001)
    }

    @Test
    fun `short response is not zero but null`() {
        val spec = ValueSpec(length = 2)
        assertNull(spec.decode(bytes(0x1A)))
    }

    @Test
    fun `scale and bias combine`() {
        val spec = ValueSpec(length = 2, scale = 0.1, bias = -40.0)
        assertEquals(60.0, spec.decode(bytes(0x03, 0xE8))!!, 0.0001)
    }

    @Test
    fun `little endian order honoured`() {
        val spec = ValueSpec(length = 2, littleEndian = true)
        assertEquals(0x3412.toDouble(), spec.decode(bytes(0x12, 0x34))!!, 0.0001)
    }
}
