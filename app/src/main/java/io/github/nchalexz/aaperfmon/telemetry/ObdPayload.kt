package io.github.nchalexz.aaperfmon.telemetry

import io.github.nchalexz.aaperfmon.core.IsoTp

/**
 * Разбор ответов адаптера.
 *
 * ELM327 в режиме ATH1 возвращает строки с CAN-ID и заголовком ISO-TP,
 * например `7E8 06 41 0C 1F 40`. Дешёвые адаптеры иногда отвечают без
 * заголовка. Оба варианта должны разбираться одинаково: иначе на одном
 * адаптере телеметрия работает, а на другом молча показывает «—».
 */
object ObdPayload {

    /** Полезная нагрузка ответа mode 01: байты после 0x41 и PID. */
    fun mode01(lines: List<String>, pid: Int): ByteArray? =
        extract(lines, responseService = 0x41, identifier = pid, identifierBytes = 1)

    /** Полезная нагрузка ответа UDS 0x62 после DID. */
    fun uds22(lines: List<String>, did: Int): ByteArray? =
        extract(lines, responseService = 0x62, identifier = did, identifierBytes = 2)

    private fun extract(
        lines: List<String>,
        responseService: Int,
        identifier: Int,
        identifierBytes: Int,
    ): ByteArray? {
        if (lines.isEmpty() || IsoTp.isNegative(lines)) return null

        // Основной путь: адаптер отдал кадры с CAN-ID, ISO-TP собирается.
        val assembled = runCatching { IsoTp.assemble(lines) }.getOrNull()
        val fromFrames = assembled?.second?.let { find(it, responseService, identifier, identifierBytes) }
        if (fromFrames != null) return fromFrames

        // Запасной путь: ответ пришёл без заголовка, одной или несколькими
        // строками «41 0C 1F 40». Склеиваем все hex-байты и ищем маркер.
        val bytes = mutableListOf<Byte>()
        for (line in lines) {
            val clean = line.replace(" ", "").trim()
            if (clean.isEmpty() || clean.any { it !in "0123456789abcdefABCDEF" }) continue
            if (clean.length % 2 != 0) continue
            runCatching {
                for (i in clean.indices step 2) {
                    bytes += clean.substring(i, i + 2).toInt(16).toByte()
                }
            }
        }
        return find(bytes.toByteArray(), responseService, identifier, identifierBytes)
    }

    private fun find(
        bytes: ByteArray,
        service: Int,
        identifier: Int,
        identifierBytes: Int,
    ): ByteArray? {
        val need = 1 + identifierBytes
        if (bytes.size < need) return null
        var i = 0
        while (i + need <= bytes.size) {
            val serviceMatches = (bytes[i].toInt() and 0xFF) == service
            val idMatches = when (identifierBytes) {
                1 -> (bytes[i + 1].toInt() and 0xFF) == identifier
                2 -> (bytes[i + 1].toInt() and 0xFF) == ((identifier shr 8) and 0xFF) &&
                    (bytes[i + 2].toInt() and 0xFF) == (identifier and 0xFF)
                else -> false
            }
            if (serviceMatches && idMatches) {
                return bytes.copyOfRange(i + need, bytes.size)
            }
            i++
        }
        return null
    }
}
