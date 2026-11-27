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

    /**
     * Длина данных mode 01 для PID, которые VPM ставит в один запрос.
     *
     * Только известные длины: иначе следующий PID в общей строке не найти.
     * Неизвестный PID в пакет не попадает и читается отдельно.
     */
    private val MODE01_LENGTHS: Map<Int, Int> = mapOf(
        0x04 to 1, 0x05 to 1, 0x0B to 1, 0x0C to 2, 0x0D to 1,
        0x0F to 1, 0x10 to 2, 0x11 to 1, 0x2F to 1, 0x33 to 1,
        0x43 to 2, 0x46 to 1, 0x5C to 1, 0x5E to 2, 0x62 to 1, 0x63 to 2,
    )

    fun mode01Length(pid: Int): Int? = MODE01_LENGTHS[pid]

    /**
     * Разбирает один ответ `01 PID1 PID2 …` на несколько полезных нагрузок.
     *
     * ELM327 отдаёт один `41`-блок со всеми PID подряд (иногда по строке на
     * ECU). Если длина PID неизвестна или строка оборвалась, этот PID просто
     * не попадает в результат: его прочитают отдельным запросом.
     */
    fun mode01Batch(lines: List<String>, pids: Set<Int>): Map<Int, ByteArray> {
        if (lines.isEmpty() || IsoTp.isNegative(lines) || pids.isEmpty()) return emptyMap()
        val assembled = runCatching { IsoTp.assemble(lines) }.getOrNull()?.second
        if (assembled != null) {
            val fromFrames = parseMode01(assembled, pids)
            if (fromFrames.isNotEmpty()) return fromFrames
        }
        // Headerless answer: "41 0B 62 0C 1A F8 05 56". A three-character CAN id is
        // not present, so the ISO-TP parser above sees garbage; parse the hex bytes.
        val out = mutableListOf<Byte>()
        for (line in lines) {
            val clean = line.replace(" ", "").trim()
            if (clean.isEmpty() || clean.any { it !in "0123456789abcdefABCDEF" }) continue
            if (clean.length % 2 != 0) continue
            runCatching {
                for (i in clean.indices step 2) out += clean.substring(i, i + 2).toInt(16).toByte()
            }
        }
        return parseMode01(out.toByteArray(), pids)
    }

    private fun parseMode01(bytes: ByteArray, pids: Set<Int>): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        var i = 0
        while (i + 1 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0x41) {
                i++
                continue
            }
            var j = i + 1
            while (j < bytes.size) {
                val pid = bytes[j].toInt() and 0xFF
                val length = MODE01_LENGTHS[pid] ?: break
                if (j + 1 + length > bytes.size) break
                if (pid in pids) result[pid] = bytes.copyOfRange(j + 1, j + 1 + length)
                j += 1 + length
            }
            i = maxOf(j, i + 1)
        }
        return result
    }

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
