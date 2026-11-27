package io.github.nchalexz.aaperfmon.telemetry

/**
 * Описание сигнала телеметрии.
 *
 * Каталог намеренно не знает ни о Compose, ни об Activity, ни о конкретном
 * транспорте: это чистые данные плюс правило декодирования. Благодаря этому
 * каталог проверяется обычными JVM-тестами, а движок телеметрии можно
 * подключить к разным источникам — демонстрационному и настоящему ELM327.
 */

/** Категория сигнала. Совпадает с группами из технического задания. */
enum class SignalCategory(val labelRu: String) {
    ENGINE("Двигатель"),
    TEMPERATURES("Температуры"),
    FUEL("Топливо"),
    TRANSMISSION("Коробка передач"),
    DRIVETRAIN("Полный привод"),
    CHASSIS("Шасси и тормоза"),
    EMISSIONS("Экология"),
    ELECTRICAL("Электрика"),
    DIAGNOSTICS("Диагностика"),
    IDENTIFICATION("Идентификация"),
}

/** Электронный блок. Адрес — диагностический адрес VAG, не CAN-ID. */
enum class Ecu(val address: Int, val labelRu: String) {
    ENGINE(0x01, "Двигатель"),
    TRANSMISSION(0x02, "Коробка передач"),
    ABS(0x03, "ABS / ESP"),
    CLIMATE(0x08, "Климат"),
    BODY(0x09, "Бортовая электроника"),
    ACC(0x13, "Адаптивный круиз"),
    SUSPENSION(0x14, "Подвеска"),
    CLUSTER(0x17, "Приборная панель"),
    GATEWAY(0x19, "CAN Gateway"),
    AWD(0x22, "Полный привод"),
    STEERING(0x44, "Усилитель руля"),
    INFOTAINMENT(0x5F, "Головное устройство"),
    UNKNOWN(0x00, "Не определён"),
}

/** Протокол, которым читается сигнал. */
enum class Protocol(val labelRu: String) {
    OBD2("OBD-II"),
    UDS("UDS"),
    KWP2000("KWP2000"),
    /** Сигнал не читается напрямую: вычисляется или появится с профилем блока. */
    VIRTUAL("Виртуальный"),
}

/**
 * Насколько сигнал проверен. Это не украшение: приложение не должно
 * показывать «подтверждённым» то, что таковым не является.
 */
enum class Verification(val labelRu: String) {
    DESCRIBED("описан"),
    DISCOVERED("обнаружен"),
    READ("прочитан"),
    DECODED("декодирован"),
    CONFIRMED("подтверждён на автомобиле"),
}

/**
 * Как превратить байты ответа в число.
 *
 * Для подавляющего большинства стандартных PID достаточно «взять length
 * байтов, посчитать big-endian целое, умножить на scale и прибавить bias».
 * Этого описания хватает и для отрицательных температур, и для дробных
 * величин вроде оборотов или наддува.
 */
data class ValueSpec(
    val offset: Int = 0,
    val length: Int = 1,
    val signed: Boolean = false,
    val scale: Double = 1.0,
    val bias: Double = 0.0,
    val littleEndian: Boolean = false,
) {
    /**
     * Декодирует значение или возвращает null, если байтов меньше, чем
     * требует описание. Null здесь принципиален: ноль означал бы
     * «датчик показал ноль», а это разные вещи.
     */
    fun decode(bytes: ByteArray): Double? {
        if (bytes.size < offset + length || length <= 0) return null
        var raw = 0L
        for (i in 0 until length) {
            val byte = bytes[offset + i].toLong() and 0xFF
            raw = if (littleEndian) raw or (byte shl (8 * i)) else (raw shl 8) or byte
        }
        val numeric = if (signed) signExtend(raw, length * 8) else raw
        return numeric.toDouble() * scale + bias
    }

    private fun signExtend(value: Long, bits: Int): Long {
        if (bits <= 0 || bits >= 64) return value
        val shift = 64 - bits
        return (value shl shift) shr shift
    }
}

/**
 * Декодирование ответа UDS `22 <DID>`.
 *
 * VAG-блоки отдают значение не с начала ответа, а с произвольного бита,
 * иногда младшим байтом вперёд. Формула взята из описания сигнала:
 * `(raw × mul ÷ div) + add`. Нулевой указатель здесь не нужен: биты
 * считаются от первого байта полезной нагрузки (после 62 DID DID).
 */
data class UdsValueSpec(
    val bitOffset: Int = 0,
    val bitLength: Int = 8,
    val signed: Boolean = false,
    val littleEndian: Boolean = false,
    val mul: Double = 1.0,
    val div: Double = 1.0,
    val add: Double = 0.0,
) {
    fun decode(payload: ByteArray): Double? {
        if (bitLength <= 0 || bitLength > 32) return null
        if (bitOffset < 0 || bitOffset + bitLength > payload.size * 8) return null

        val raw = if (bitOffset % 8 == 0 && bitLength % 8 == 0) {
            val start = bitOffset / 8
            val count = bitLength / 8
            val bytes = payload.copyOfRange(start, start + count)
            if (littleEndian) bytes.reverse()
            var value = 0L
            for (byte in bytes) value = (value shl 8) or (byte.toLong() and 0xFF)
            if (signed) signExtend(value, bitLength) else value
        } else {
            // Неровные битовые поля читаем MSB-first, как их описывает OBDb.
            var value = 0L
            for (i in 0 until bitLength) {
                val position = bitOffset + i
                val byte = payload[position / 8].toInt() and 0xFF
                val bit = (byte shr (7 - position % 8)) and 1
                value = (value shl 1) or bit.toLong()
            }
            if (signed) signExtend(value, bitLength) else value
        }
        val value = raw.toDouble() * mul / div + add
        return if (value.isNaN() || value.isInfinite()) null else value
    }

    private fun signExtend(value: Long, bits: Int): Long {
        if (bits <= 0 || bits >= 64) return value
        val shift = 64 - bits
        return (value shl shift) shr shift
    }
}

/**
 * Приоритет опроса и срок актуальности.
 *
 * Значения взяты из требований к телеметрии: P0 и P1 считаются свежими
 * пять секунд, редкие P2 — тридцать. Приоритет влияет на порядок очереди,
 * а не на гарантированную частоту: частота всегда ограничена адаптером.
 */
enum class SignalPriority(val staleTimeoutMs: Long) {
    /** Обороты, скорость, наддув, температура масла и ОЖ. */
    P0(5_000L),

    /** Момент, мощность, передача, педаль, давление топлива. */
    P1(5_000L),

    /** Остальные диагностические параметры и редкие температуры. */
    P2(30_000L),
}

/** Как показывать последнее известное значение. */
enum class SignalStatus {
    /** Число получено и не старше срока актуальности. */
    VALID,

    /** Значение было, но устарело: показывается прочерк. */
    STALE,

    /** Успешного чтения не было ни разу за сессию: показывается N/A. */
    NEVER_READ,
}

/**
 * Как показывать число.
 *
 * Передача — не число на шкале: у неё есть P, R и N. Показывать «-2»
 * вместо парковки бессмысленно, поэтому для неё отдельный вид.
 */
enum class ValueDisplay {
    NUMBER,

    /** Позиция селектора DSG: −2 = P, −1 = R, 0 = N, 1… = передача. */
    GEAR,
}

/**
 * Подписи позиции селектора.
 *
 * Смещение взято из открытого набора OBDb (поле `add = -2`): нулевое
 * значение после смещения — нейтраль, отрицательные — парковка и задний
 * ход. На реальном MQB это ещё не проверялось: на автомобиле P/R/N
 * нужно сверить первыми.
 */
object GearLabel {
    fun of(value: Double?): String {
        if (value == null) return "—"
        val rounded = Math.round(value).toInt()
        if (Math.abs(value - rounded) > 0.01) return "—"
        return when (rounded) {
            -2 -> "P"
            -1 -> "R"
            0 -> "N"
            in 1..10 -> rounded.toString()
            else -> "—"
        }
    }
}

/** Какой показатель вычисляется из других сигналов. */
enum class ComputedKind {
    /** Фактический момент, Н·м: процент из PID 0x62 от опорного момента 0x63. */
    TORQUE_NM,

    /** Мощность, кВт: момент × обороты / 9549. */
    POWER_KW,

    /** Наддув, кПа: абсолютное давление во впуске минус атмосферное. */
    BOOST_KPA,

    /**
     * Мгновенный расход, л/100 км: расход в л/ч делится на скорость.
     * На стоянке показатель не определён — это не ноль, а «нет данных».
     */
    FUEL_L100,
}

/** Вычисляемый сигнал и его зависимости. */
data class ComputedSpec(
    val kind: ComputedKind,
    val inputs: List<String>,
)

/**
 * Один сигнал каталога.
 *
 * Поля source/verification/note заполняются честно. Если формула взята из
 * стандарта — это указано. Если сигнал ещё не проверен на машине — статус
 * остаётся DECODED или DESCRIBED, а не CONFIRMED.
 */
data class SignalDefinition(
    val id: String,
    val nameRu: String,
    val nameEn: String,
    val category: SignalCategory,
    val ecu: Ecu = Ecu.ENGINE,
    val protocol: Protocol = Protocol.OBD2,
    /** Сервис запроса: 0x01 — OBD mode 01, 0x22 — UDS ReadDataByIdentifier. */
    val service: Int = 0x01,
    /** PID для OBD-II. */
    val pid: Int? = null,
    /** DID для UDS. */
    val did: Int? = null,
    /** Декодер стандартного PID (OBD-II). */
    val decode: ValueSpec? = null,
    /** Декодер ответа UDS `22 <DID>`. */
    val uds: UdsValueSpec? = null,
    /** Адрес запроса для UDS: 0x7E0 — двигатель, 0x7E1 — коробка, 0x714 — щиток. */
    val txId: Int? = null,
    /** Адрес ответа: 0x7E8, 0x7E9, 0x77E. Если null, адаптер слушает сам. */
    val rxId: Int? = null,
    val computed: ComputedSpec? = null,
    val unit: String = "",
    val decimals: Int = 0,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    /** Рекомендуемая частота опроса, Гц. */
    val pollHz: Double = 1.0,
    /** Приоритет очереди опроса и срок актуальности значения. */
    val priority: SignalPriority = SignalPriority.P2,
    /** Как рисовать значение: число или позиция селектора. */
    val display: ValueDisplay = ValueDisplay.NUMBER,
    val source: String = "",
    val verification: Verification = Verification.DESCRIBED,
    val note: String = "",
) {
    val isMeasured: Boolean get() = decode != null && protocol == Protocol.OBD2 && pid != null
    val isUds: Boolean get() = protocol == Protocol.UDS && did != null && uds != null && txId != null
    val isComputed: Boolean get() = computed != null

    /** Интервал опроса в миллисекундах, но не чаще одного раза в 100 мс. */
    val intervalMs: Long
        get() = (1000.0 / pollHz.coerceIn(0.05, 20.0)).toLong().coerceAtLeast(100L)

    /** Сколько значение остаётся актуальным: 5 с для P0/P1, 30 с для P2. */
    val staleTimeoutMs: Long get() = priority.staleTimeoutMs

    /** Текст запроса для журнала и документации. */
    val requestHex: String
        get() = when {
            pid != null -> "%02X%02X".format(service, pid)
            did != null -> "%02X%04X".format(service, did)
            else -> "—"
        }

    fun format(value: Double?): String {
        if (value == null) return "—"
        return when (display) {
            ValueDisplay.GEAR -> GearLabel.of(value)
            ValueDisplay.NUMBER -> "%.${decimals}f".format(value)
        }
    }
}

/** Состояние последнего чтения сигнала. */
enum class ReadingState {
    OK,
    UNSUPPORTED,
    ERROR,
}

/** Последнее известное значение сигнала. */
data class SignalValue(
    val value: Double?,
    /** Время последнего успешного чтения по монотонным часам. */
    val timestampMs: Long,
    val state: ReadingState,
    val sourceLabel: String,
    /** Время последней попытки чтения, удачной или нет. */
    val lastAttemptMs: Long = timestampMs,
    /** Было ли успешное чтение хотя бы раз за текущую сессию. */
    val hasEverBeenRead: Boolean = value != null,
    /** Сколько неудачных попыток подряд было после последнего успеха. */
    val consecutiveFailures: Int = 0,
) {
    /**
     * Состояние значения к моменту [now].
     *
     * Ошибка чтения сама по себе не стирает число: значение остаётся
     * действительным, пока не истёк срок актуальности. Ноль — допустимый
     * результат и всегда отличается от отсутствия данных.
     */
    fun statusAt(now: Long, definition: SignalDefinition): SignalStatus {
        if (!hasEverBeenRead) return SignalStatus.NEVER_READ
        if (value == null) return SignalStatus.STALE
        // Монотонные часы обнуляются после перезагрузки телефона, а в кэше
        // могло остаться время прошлой загрузки. «Будущее» — значит старьё.
        if (timestampMs > now) return SignalStatus.STALE
        return if (now - timestampMs > definition.staleTimeoutMs) {
            SignalStatus.STALE
        } else {
            SignalStatus.VALID
        }
    }

    /** Устарело ли значение к моменту now. */
    fun isStale(now: Long, definition: SignalDefinition): Boolean =
        statusAt(now, definition) != SignalStatus.VALID
}
