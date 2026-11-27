package io.github.nchalexz.aaperfmon.telemetry

import io.github.nchalexz.aaperfmon.core.Elm327
import io.github.nchalexz.aaperfmon.core.Transport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка маршрута OBD ↔ UDS на поддельном адаптере.
 *
 * Это не подтверждение совместимости: здесь проверяется разбор ответа,
 * смена заголовка и то, что отрицательный ответ не превращается в ноль.
 */
class Elm327UdsSourceTest {

    private class ScriptedTransport : Transport {
        override val title = "scripted"
        val commands = mutableListOf<String>()

        /** Ответ на пакетный запрос двух DID; false — блок его не понимает. */
        var batchSupported = true

        private var pending = ""

        override fun open() = Unit
        override fun close() = Unit
        override fun write(data: ByteArray) {
            val command = String(data, Charsets.US_ASCII).trim()
            commands += command
            pending = answer(command) + "\r>"
        }

        override fun read(): ByteArray {
            if (pending.isEmpty()) return ByteArray(0)
            val bytes = pending.toByteArray(Charsets.US_ASCII)
            pending = ""
            return bytes
        }

        private fun answer(command: String): String = when {
            command == "ATZ" -> "ELM327 v1.5"
            command.startsWith("AT") -> "OK"
            command == "221057" -> "7E8 05 62 10 57 06 80"
            command == "2210572029" && batchSupported ->
                "7E8 09 62 10 57 06 80 20 29 4F 1A"
            command == "010D" -> "7E8 03 41 0D 64"
            command.startsWith("22") -> "NO DATA"
            else -> "NO DATA"
        }
    }

    @Test
    fun `UDS DID is read, decoded and the OBD header is restored`() = runTest {
        val transport = ScriptedTransport()
        val elm = Elm327(transport)
        elm.connect()
        elm.init()
        val source = Elm327SignalSource(elm)
        val boost = VagSignals.all.first { it.id == "vag.7e0.1057.boost" }

        val reading = source.read(boost)
        assertEquals(ReadingState.OK, reading.state)
        assertEquals(1.664, reading.value!!, 0.000001)

        val speed = SignalCatalog.get(SignalCatalog.Ids.SPEED)!!
        val obd = source.read(speed)
        assertEquals(ReadingState.OK, obd.state)
        assertEquals(100.0, obd.value!!, 0.001)
        assertTrue("Заголовок 7DF не восстановлен: ${transport.commands}", "ATSH7DF" in transport.commands)
    }

    @Test
    fun `two dids of one block are read in one request`() = runTest {
        val transport = ScriptedTransport()
        val elm = Elm327(transport)
        elm.connect()
        elm.init()
        val source = Elm327SignalSource(elm)
        val boost = VagSignals.all.first { it.id == "vag.7e0.1057.boost" }
        val charge = VagSignals.all.first { it.id == "vag.7e0.2029.charge_specified" }

        val readings = source.readBatch(listOf(boost, charge))

        assertEquals(ReadingState.OK, readings[boost.id]!!.state)
        assertEquals(1.664, readings[boost.id]!!.value!!, 0.000001)
        assertEquals(ReadingState.OK, readings[charge.id]!!.state)
        assertEquals(1_620.0, readings[charge.id]!!.value!!, 0.001)
        assertEquals(1, transport.commands.count { it == "2210572029" })
    }

    @Test
    fun `unsupported batch falls back to single requests for good`() = runTest {
        val transport = ScriptedTransport().apply { batchSupported = false }
        val elm = Elm327(transport)
        elm.connect()
        elm.init()
        val source = Elm327SignalSource(elm)
        val boost = VagSignals.all.first { it.id == "vag.7e0.1057.boost" }
        val charge = VagSignals.all.first { it.id == "vag.7e0.2029.charge_specified" }

        val readings = source.readBatch(listOf(boost, charge))

        // Пакет не поддержан: данные всё равно получены по одному,
        // а неподдерживаемый DID честно помечен.
        assertEquals(ReadingState.OK, readings[boost.id]!!.state)
        assertEquals(ReadingState.UNSUPPORTED, readings[charge.id]!!.state)

        source.readBatch(listOf(boost, charge))
        assertEquals(
            "Пакетный запрос повторился после отказа",
            1,
            transport.commands.count { it == "2210572029" },
        )
    }

    @Test
    fun `negative UDS answer is unsupported, not zero`() = runTest {
        val transport = ScriptedTransport()
        val elm = Elm327(transport)
        elm.connect()
        elm.init()
        val source = Elm327SignalSource(elm)
        val unknown = SignalDefinition(
            id = "test.unknown",
            nameRu = "Неизвестный",
            nameEn = "Unknown",
            category = SignalCategory.ENGINE,
            protocol = Protocol.UDS,
            service = 0x22,
            did = 0xDEAD,
            uds = UdsValueSpec(bitLength = 8),
            txId = 0x7E0,
            rxId = 0x7E8,
        )

        val reading = source.read(unknown)
        assertEquals(ReadingState.UNSUPPORTED, reading.state)
        assertEquals(null, reading.value)
    }
}
