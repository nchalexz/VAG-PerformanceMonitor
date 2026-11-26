package io.github.nchalexz.aaperfmon.telemetry

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdPayloadTest {

    @Test
    fun `payload with can header and iso tp is extracted`() {
        val lines = listOf("7E8 06 41 0C 1F 40")
        assertArrayEquals(byteArrayOf(0x1F, 0x40), ObdPayload.mode01(lines, 0x0C))
    }

    @Test
    fun `payload without header is extracted`() {
        val lines = listOf("41 0D 64")
        assertArrayEquals(byteArrayOf(0x64), ObdPayload.mode01(lines, 0x0D))
    }

    @Test
    fun `payload glued without spaces is extracted`() {
        assertArrayEquals(byteArrayOf(0x64), ObdPayload.mode01(listOf("410D64"), 0x0D))
    }

    @Test
    fun `negative answer is not mistaken for data`() {
        assertNull(ObdPayload.mode01(listOf("NO DATA"), 0x0C))
        assertNull(ObdPayload.mode01(listOf("CAN ERROR"), 0x0C))
        assertNull(ObdPayload.mode01(emptyList(), 0x0C))
    }

    @Test
    fun `missing pid is not extracted`() {
        assertNull(ObdPayload.mode01(listOf("7E8 06 41 0D 64"), 0x0C))
    }

    @Test
    fun `uds did payload is extracted`() {
        val lines = listOf("7E8 10 0A 62 F1 90 57 56 57")
        assertArrayEquals(
            byteArrayOf(0x57, 0x56, 0x57),
            ObdPayload.uds22(lines, 0xF190),
        )
    }
}
