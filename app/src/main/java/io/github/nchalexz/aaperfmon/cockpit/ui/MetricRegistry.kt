package io.github.nchalexz.aaperfmon.cockpit.ui

import io.github.nchalexz.aaperfmon.cockpit.UiLanguage
import io.github.nchalexz.aaperfmon.telemetry.GearLabel
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.SignalDefinition
import io.github.nchalexz.aaperfmon.telemetry.SignalStatus
import io.github.nchalexz.aaperfmon.telemetry.SignalValue
import io.github.nchalexz.aaperfmon.telemetry.VagSignals
import io.github.nchalexz.aaperfmon.telemetry.ValueDisplay
import java.util.Locale

/** Категории расширенного экрана. Не совпадают с SignalCategory: это ракурс показа. */
enum class DisplayCategory(val id: String) {
    ENGINE("engine"),
    TEMPERATURES("temperatures"),
    FUEL("fuel"),
    GEARBOX("gearbox"),
    TURBO("turbo"),
    DIAGNOSTICS("diagnostics");

    fun label(language: UiLanguage): String = when (this) {
        ENGINE -> language.pick("Engine", "Двигатель")
        TEMPERATURES -> language.pick("Temperatures", "Температуры")
        FUEL -> language.pick("Fuel", "Топливо")
        GEARBOX -> language.pick("Gearbox", "КП")
        TURBO -> language.pick("Turbo", "Турбо")
        DIAGNOSTICS -> language.pick("Diagnostics", "Диагностика")
    }

    companion object {
        fun byId(id: String?): DisplayCategory =
            entries.firstOrNull { it.id == id } ?: ENGINE
    }
}

fun UiLanguage.pick(english: String, russian: String): String =
    if (this == UiLanguage.RU) russian else english

enum class GaugeIcon { COOLANT, BOOST, OIL, NONE }

/**
 * Как показывать сигнал: подписи, единицы, масштаб шкалы и пороги.
 *
 * Каталог (`SignalCatalog`) знает, что такое сигнал и как его прочитать.
 * Реестр знает, как его показать. Пороги здесь — значения по умолчанию
 * для отображения, а не универсальные «критические» для всех двигателей:
 * профиль автомобиля сможет их переопределить.
 */
/**
 * Один источник показателя. Основной сигнал и запасные: на MQB температура
 * масла может быть в блоке двигателя, в щитке или только в стандартном PID.
 * Показываем первый, который реально ответил, и не подставляем ноль.
 */
data class MetricSource(
    val signalId: String,
    val factor: Double = 1.0,
    val decimals: Int? = null,
)

data class MetricSpec(
    val signalId: String,
    val category: DisplayCategory,
    val labelEn: String,
    val labelRu: String,
    val unitEn: String,
    val unitRu: String,
    val decimals: Int,
    /** Множитель для показа: кПа → бар, если нужно. */
    val factor: Double = 1.0,
    val scaleMin: Double = 0.0,
    val scaleMax: Double = 1.0,
    val warnFrom: Double? = null,
    val criticalFrom: Double? = null,
    val icon: GaugeIcon = GaugeIcon.NONE,
    /** Сигнал существует только в демо (стандартный OBD-II его не отдаёт). */
    val virtual: Boolean = false,
    /** Запасные источники, если основной не отвечает. */
    val alternatives: List<MetricSource> = emptyList(),
    /** Передача показывается позицией селектора: P, R, N, 1…
     */
    val gear: Boolean = false,
) {
    val allSignalIds: List<String>
        get() = listOf(signalId) + alternatives.map { it.signalId }

    fun display(raw: Double, factorOverride: Double = factor): Double = raw * factorOverride

    fun label(language: UiLanguage): String =
        language.pick(labelEn, labelRu)

    fun unit(language: UiLanguage): String =
        language.pick(unitEn, unitRu)

    fun format(raw: Double?, factorOverride: Double = factor, decimalsOverride: Int = decimals): String {
        val value = raw?.let { display(it, factorOverride) } ?: return "—"
        if (value.isNaN() || value.isInfinite()) return "—"
        if (gear) return GearLabel.of(value)
        val text = String.format(Locale.ROOT, "%.${decimalsOverride}f", value)
        // Европейская запятая как на макетах, независимо от системной локали.
        return text.replace('.', ',')
    }

    fun fraction(displayValue: Double?): Float? {
        if (displayValue == null) return null
        if (scaleMax <= scaleMin) return null
        return ((displayValue - scaleMin) / (scaleMax - scaleMin))
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    fun warningValue(displayValue: Double?): Boolean =
        displayValue != null && warnFrom != null && displayValue >= warnFrom

    fun criticalValue(displayValue: Double?): Boolean =
        displayValue != null && criticalFrom != null && displayValue >= criticalFrom
}

/** Почему у значения нет числа. Это разные ситуации, и путать их нельзя. */
enum class MetricState {
    OK,

    /** Успешного чтения ещё не было в этой сессии: на экране N/A. */
    NEVER_READ,

    WAITING,
    STALE,
    UNSUPPORTED,
    ERROR,
    OFFLINE,
}

data class MetricReading(
    val spec: MetricSpec,
    val raw: Double?,
    val display: Double?,
    val state: MetricState,
    val text: String,
) {
    val isOk: Boolean get() = state == MetricState.OK

    /**
     * Значение было, но устарело. На экране оно остаётся числом, только
     * светло-серым: прочерк означает «не получено ни разу», а не «давно
     * не обновлялось». Так цифры не мигают между числом и прочерком.
     */
    val stale: Boolean get() = state == MetricState.STALE && raw != null

    val fraction: Float? get() = if (isOk || stale) spec.fraction(display) else null
}

fun MetricState.note(language: UiLanguage): String = when (this) {
    MetricState.OK -> ""
    MetricState.NEVER_READ -> language.pick("not read yet", "не прочитан")
    MetricState.WAITING -> language.pick("waiting", "ожидание")
    MetricState.STALE -> language.pick("stale", "устарело")
    MetricState.UNSUPPORTED -> language.pick("not supported", "не поддерживается")
    MetricState.ERROR -> language.pick("read error", "ошибка чтения")
    MetricState.OFFLINE -> language.pick("no link", "нет связи")
}

private fun rank(state: MetricState): Int = when (state) {
    MetricState.OK -> 0
    MetricState.STALE -> 1
    MetricState.NEVER_READ -> 2
    MetricState.WAITING -> 3
    MetricState.UNSUPPORTED -> 4
    MetricState.ERROR -> 5
    MetricState.OFFLINE -> 6
}

/**
 * Читает показатель из первого ответившего источника.
 *
 * Основной сигнал может не поддерживаться конкретным блоком (например,
 * PID температуры масла на дизеле), а VAG DID — ответить. Порядок
 * предпочтения: актуальное значение, затем устаревшее, затем ожидание,
 * затем «не поддерживается». Ноль вместо отсутствия не подставляется.
 */
fun readMetric(
    spec: MetricSpec,
    values: Map<String, SignalValue>,
    now: Long,
    canShowData: Boolean,
): MetricReading {
    val sources = buildList {
        add(MetricSource(spec.signalId, spec.factor, spec.decimals))
        addAll(spec.alternatives)
    }

    data class Candidate(
        val source: MetricSource,
        val definition: SignalDefinition,
        val value: SignalValue?,
        val status: SignalStatus?,
    )

    val candidates = sources.mapNotNull { source ->
        val definition = SignalCatalog.get(source.signalId) ?: return@mapNotNull null
        val value = values[source.signalId]
        Candidate(source, definition, value, value?.statusAt(now, definition))
    }

    fun render(candidate: Candidate): MetricReading {
        val status = candidate.status ?: SignalStatus.NEVER_READ
        val state = when {
            !canShowData -> MetricState.OFFLINE
            status == SignalStatus.NEVER_READ -> MetricState.NEVER_READ
            status == SignalStatus.STALE -> MetricState.STALE
            else -> MetricState.OK
        }
        val raw = if (state == MetricState.OK || state == MetricState.STALE) {
            candidate.value?.value
        } else {
            null
        }
        return MetricReading(
            spec = spec,
            raw = raw,
            display = raw?.let { spec.display(it, candidate.source.factor) },
            state = state,
            text = when {
                raw != null -> spec.format(
                    raw,
                    candidate.source.factor,
                    candidate.source.decimals ?: spec.decimals,
                )
                else -> "—"
            },
        )
    }

    if (!canShowData) return render(candidates.firstOrNull() ?: return MetricReading(
        spec = spec,
        raw = null,
        display = null,
        state = MetricState.OFFLINE,
        text = "—",
    ))

    // Порядок выбора источника фиксирован, а не зависит от того, кто
    // успел ответить последним: иначе число и прочерк мигали бы между
    // основным PID и запасным DID. Сначала свежий основной, затем свежий
    // запасной, затем самое новое из устаревших, и только потом «—».
    candidates.firstOrNull { it.status == SignalStatus.VALID }?.let { return render(it) }
    candidates.firstOrNull { it.value?.value != null && it.status == SignalStatus.STALE }
        ?.let { return render(it) }
    val newest = candidates
        .filter { it.value?.value != null }
        .maxByOrNull { it.value!!.timestampMs }
    if (newest != null) return render(newest)

    return MetricReading(
        spec = spec,
        raw = null,
        display = null,
        state = MetricState.NEVER_READ,
        text = "—",
    )
}

/**
 * Реестр отображаемых показателей.
 *
 * Набор главного экрана фиксирован макетом: три прибора и пять плиток.
 * Расширенный экран показывает остальные сигналы категориями. Показатели,
 * которых в каталоге нет или которые стандартный OBD-II не отдаёт, сюда
 * не добавляются — лучше меньше плиток, чем нарисованный ноль.
 */
object MetricRegistry {

    private fun metric(
        id: String,
        category: DisplayCategory,
        en: String,
        ru: String,
        unitEn: String,
        unitRu: String,
        decimals: Int,
        factor: Double = 1.0,
        min: Double = 0.0,
        max: Double = 1.0,
        warn: Double? = null,
        critical: Double? = null,
        icon: GaugeIcon = GaugeIcon.NONE,
        alternatives: List<MetricSource> = emptyList(),
        gear: Boolean = false,
    ): MetricSpec {
        val definition = requireNotNull(SignalCatalog.get(id)) { "Нет сигнала $id" }
        return MetricSpec(
            signalId = id,
            category = category,
            labelEn = en,
            labelRu = ru,
            unitEn = unitEn,
            unitRu = unitRu,
            decimals = decimals,
            factor = factor,
            scaleMin = min,
            scaleMax = max,
            warnFrom = warn,
            criticalFrom = critical,
            icon = icon,
            virtual = definition.protocol == io.github.nchalexz.aaperfmon.telemetry.Protocol.VIRTUAL,
            alternatives = alternatives,
            gear = gear,
        )
    }

    val coolant = metric(
        SignalCatalog.Ids.COOLANT, DisplayCategory.TEMPERATURES,
        "Coolant", "ОЖ", "°C", "°C", 0,
        min = 40.0, max = 120.0, warn = 105.0, critical = 115.0,
        icon = GaugeIcon.COOLANT,
    )
    /** Избыточный наддув: MAP минус атмосферное. Вкладка «Турбо». */
    val boost = metric(
        SignalCatalog.Ids.BOOST, DisplayCategory.TURBO,
        "Boost rel.", "НАДДУВ ИЗБ.", "bar", "бар", 2,
        factor = 0.01, min = -1.0, max = 2.0, warn = 1.75, critical = 1.9,
    )

    /**
     * Абсолютное давление во впуске — то, что показывает консоль машины
     * (например, 0,98 бар на холостом ходу). Это MAP, а не разница с
     * атмосферным; избыточный наддув остался отдельным показателем.
     */
    val chargePressure = metric(
        SignalCatalog.Ids.MAP, DisplayCategory.TURBO,
        "Boost abs.", "НАДДУВ АБС.", "bar", "бар", 2,
        factor = 0.01, min = 0.0, max = 2.5, warn = 2.0, critical = 2.3,
        icon = GaugeIcon.BOOST,
    )
    val oil = metric(
        SignalCatalog.Ids.OIL_TEMP, DisplayCategory.TEMPERATURES,
        "Oil", "МАСЛО", "°C", "°C", 0,
        min = 40.0, max = 150.0, warn = 130.0, critical = 145.0,
        icon = GaugeIcon.OIL,
        // На дизеле PID 0x5C часто молчит; VAG отдаёт масло из блока
        // двигателя или из щитка. Порядок: сначала блок двигателя.
        alternatives = listOf(
            MetricSource("vag.7e0.11be.oil_temp"),
            MetricSource("vag.714.202f.oil_temp"),
        ),
    )

    val speed = metric(
        SignalCatalog.Ids.SPEED, DisplayCategory.ENGINE,
        "SPEED", "СКОРОСТЬ", "km/h", "км/ч", 0,
        min = 0.0, max = 240.0,
    )
    val rpm = metric(
        SignalCatalog.Ids.RPM, DisplayCategory.ENGINE,
        "REVS", "ОБОРОТЫ", "rpm", "об/мин", 0,
        // Шкала тахометра, а не «доля»: без неё стрелка стояла бы на максимуме
        // уже на холостых. Красная зона — около отсечки, не универсальный порог.
        min = 0.0, max = 8000.0, warn = 6500.0, critical = 7200.0,
    )
    val gear = metric(
        SignalCatalog.Ids.GEAR, DisplayCategory.GEARBOX,
        "GEAR", "ПЕРЕДАЧА", "", "", 0,
        alternatives = listOf(MetricSource("vag.7e1.210f.gear")),
        gear = true,
    )
    val fuelUse = metric(
        SignalCatalog.Ids.FUEL_L100, DisplayCategory.FUEL,
        "FUEL USE", "РАСХОД", "l/100 km", "л/100 км", 1,
    )
    val fuelLevel = metric(
        SignalCatalog.Ids.FUEL_LEVEL, DisplayCategory.FUEL,
        "FUEL", "ТОПЛИВО", "%", "%", 0,
        // На части MQB стандартный PID 0x2F молчит, а щиток отдаёт уровень
        // по DID 2206. Показываем первый ответивший источник.
        alternatives = listOf(MetricSource("vag.714.2206.fuel_level")),
    )

    private val load = metric(
        SignalCatalog.Ids.LOAD, DisplayCategory.ENGINE,
        "ENGINE LOAD", "НАГРУЗКА", "%", "%", 0,
    )
    private val throttle = metric(
        SignalCatalog.Ids.THROTTLE, DisplayCategory.ENGINE,
        "THROTTLE", "ДРОССЕЛЬ", "%", "%", 0,
    )
    private val pedal = metric(
        SignalCatalog.Ids.PEDAL, DisplayCategory.ENGINE,
        "PEDAL", "ПЕДАЛЬ", "%", "%", 0,
    )
    private val map = metric(
        SignalCatalog.Ids.MAP, DisplayCategory.ENGINE,
        "INTAKE PRESSURE", "ДАВЛ. ВПУСКА", "kPa", "кПа", 0,
    )
    private val baro = metric(
        SignalCatalog.Ids.BARO, DisplayCategory.ENGINE,
        "ATMOSPHERIC", "АТМ. ДАВЛЕНИЕ", "kPa", "кПа", 0,
    )
    private val maf = metric(
        SignalCatalog.Ids.MAF, DisplayCategory.ENGINE,
        "AIR FLOW", "ВОЗДУХ", "g/s", "г/с", 1,
    )
    private val voltage = metric(
        SignalCatalog.Ids.VOLTAGE, DisplayCategory.ENGINE,
        "BATTERY", "НАПРЯЖЕНИЕ", "V", "В", 1,
    )
    private val torque = metric(
        SignalCatalog.Ids.TORQUE_NM, DisplayCategory.ENGINE,
        "TORQUE", "МОМЕНТ", "Nm", "Н·м", 0,
    )
    private val power = metric(
        SignalCatalog.Ids.POWER_KW, DisplayCategory.ENGINE,
        "POWER", "МОЩНОСТЬ", "kW", "кВт", 0,
    )
    private val absLoad = metric(
        SignalCatalog.Ids.ABS_LOAD, DisplayCategory.ENGINE,
        "ABS LOAD", "АБС. НАГРУЗКА", "%", "%", 0,
    )
    private val timing = metric(
        SignalCatalog.Ids.TIMING, DisplayCategory.ENGINE,
        "IGNITION", "УГОЛ ЗАЖИГ.", "°", "°", 1,
    )
    private val cmdThrottle = metric(
        SignalCatalog.Ids.CMD_THROTTLE, DisplayCategory.ENGINE,
        "CMD THROTTLE", "КОМАНДА ДРОССЕЛЯ", "%", "%", 0,
    )
    private val relThrottle = metric(
        SignalCatalog.Ids.REL_THROTTLE, DisplayCategory.ENGINE,
        "REL THROTTLE", "ОТН. ДРОССЕЛЬ", "%", "%", 0,
    )
    private val absThrottleB = metric(
        SignalCatalog.Ids.ABS_THROTTLE_B, DisplayCategory.ENGINE,
        "THROTTLE B", "ДРОССЕЛЬ B", "%", "%", 0,
    )
    private val absThrottleC = metric(
        SignalCatalog.Ids.ABS_THROTTLE_C, DisplayCategory.ENGINE,
        "THROTTLE C", "ДРОССЕЛЬ C", "%", "%", 0,
    )
    private val pedalE = metric(
        SignalCatalog.Ids.PEDAL_E, DisplayCategory.ENGINE,
        "PEDAL E", "ПЕДАЛЬ E", "%", "%", 0,
    )
    private val torqueDemand = metric(
        SignalCatalog.Ids.TORQUE_DEMAND_PCT, DisplayCategory.ENGINE,
        "TORQUE DEMAND", "ЗАПРОС МОМЕНТА", "%", "%", 0,
    )
    private val torquePct = metric(
        SignalCatalog.Ids.TORQUE_PCT, DisplayCategory.ENGINE,
        "TORQUE, %", "МОМЕНТ, %", "%", "%", 0,
    )
    private val torqueRef = metric(
        SignalCatalog.Ids.TORQUE_REF, DisplayCategory.ENGINE,
        "TORQUE REF", "ОПОРНЫЙ МОМЕНТ", "Nm", "Н·м", 0,
    )
    private val runTime = metric(
        SignalCatalog.Ids.RUN_TIME, DisplayCategory.ENGINE,
        "RUN TIME", "ВРЕМЯ РАБОТЫ", "s", "с", 0,
    )

    private val intakeAir = metric(
        SignalCatalog.Ids.INTAKE_AIR, DisplayCategory.TEMPERATURES,
        "INTAKE AIR", "ВОЗДУХ ВПУСКА", "°C", "°C", 0,
    )
    private val ambient = metric(
        SignalCatalog.Ids.AMBIENT, DisplayCategory.TEMPERATURES,
        "AMBIENT", "СНАРУЖИ", "°C", "°C", 0,
    )
    private val catalyst = metric(
        SignalCatalog.Ids.CATALYST, DisplayCategory.TEMPERATURES,
        "CATALYST", "КАТАЛИЗАТОР", "°C", "°C", 0,
    )

    private val fuelRate = metric(
        SignalCatalog.Ids.FUEL_RATE, DisplayCategory.FUEL,
        "FUEL RATE", "РАСХОД, Л/Ч", "l/h", "л/ч", 1,
    )
    private val fuelPressure = metric(
        SignalCatalog.Ids.FUEL_PRESSURE, DisplayCategory.FUEL,
        "FUEL PRESSURE", "ДАВЛ. ТОПЛИВА", "kPa", "кПа", 0,
    )
    private val rail = metric(
        SignalCatalog.Ids.RAIL_PRESSURE, DisplayCategory.FUEL,
        "RAIL PRESSURE", "ДАВЛ. РАМПЫ", "kPa", "кПа", 0,
    )

    /** Давление в рампе в барах: 31000 кПа читается как 310 бар. */
    private val railBar = metric(
        SignalCatalog.Ids.RAIL_PRESSURE, DisplayCategory.FUEL,
        "RAIL", "ДАВЛ. РАМПЫ", "bar", "бар", 0,
        factor = 0.01, min = 0.0, max = 2500.0,
    )

    /** Температура выхлопа: перегрев турбины и DPF видно по ней. */
    private val egt = metric(
        "vag.7e0.10fb.egt1", DisplayCategory.TEMPERATURES,
        "EGT", "ВЫХЛОП", "°C", "°C", 0,
        min = 0.0, max = 900.0, warn = 700.0, critical = 850.0,
    )

    /** Температура масла DSG из блока коробки: важна для защиты КП. */
    private val dsgTemp = metric(
        "vag.7e1.2104.atf_temp", DisplayCategory.GEARBOX,
        "DSG OIL", "ТЕМП. DSG", "°C", "°C", 0,
        min = 30.0, max = 140.0, warn = 110.0, critical = 125.0,
    )
    private val railRelative = metric(
        SignalCatalog.Ids.RAIL_REL, DisplayCategory.FUEL,
        "RAIL REL", "РАМПА ОТН.", "kPa", "кПа", 0,
    )
    private val trimShort = metric(
        SignalCatalog.Ids.TRIM_SHORT, DisplayCategory.FUEL,
        "TRIM SHORT", "КОРР. КРАТК.", "%", "%", 1,
    )
    private val trimLong = metric(
        SignalCatalog.Ids.TRIM_LONG, DisplayCategory.FUEL,
        "TRIM LONG", "КОРР. ДОЛГ.", "%", "%", 1,
    )
    private val lambda = metric(
        SignalCatalog.Ids.LAMBDA, DisplayCategory.FUEL,
        "LAMBDA", "ЛЯМБДА", "λ", "λ", 2,
    )
    private val injection = metric(
        SignalCatalog.Ids.INJECTION_TIMING, DisplayCategory.FUEL,
        "INJECTION", "ВПРЫСК", "°", "°", 1,
    )
    private val ethanol = metric(
        SignalCatalog.Ids.ETHANOL, DisplayCategory.FUEL,
        "ETHANOL", "ЭТАНОЛ", "%", "%", 0,
    )

    private val egr = metric(
        SignalCatalog.Ids.EGR, DisplayCategory.DIAGNOSTICS,
        "EGR", "EGR", "%", "%", 1,
    )
    private val egrError = metric(
        SignalCatalog.Ids.EGR_ERROR, DisplayCategory.DIAGNOSTICS,
        "EGR ERROR", "ОШИБКА EGR", "%", "%", 1,
    )
    private val distanceMil = metric(
        SignalCatalog.Ids.DISTANCE_MIL, DisplayCategory.DIAGNOSTICS,
        "DISTANCE MIL", "ПРОБЕГ С MIL", "km", "км", 0,
    )
    private val distanceCleared = metric(
        SignalCatalog.Ids.DISTANCE_CLEARED, DisplayCategory.DIAGNOSTICS,
        "SINCE CLEAR", "ПОСЛЕ СБРОСА", "km", "км", 0,
    )
    private val warmups = metric(
        SignalCatalog.Ids.WARMUPS, DisplayCategory.DIAGNOSTICS,
        "WARM-UPS", "ПРОГРЕВЫ", "", "", 0,
    )

    // -- VAG-сигналы из UDS --------------------------------------------------
    //
    // Это живые показатели, для которых в открытом наборе OBDb есть DID,
    // адрес блока и формула. Они не помечены подтверждёнными: на MQB TDI
    // их ещё нужно проверить. Показываем только то, что реально ответило.

    private data class DisplayUnit(
        val en: String,
        val ru: String,
        val factor: Double,
        val decimals: Int,
    )

    private fun displayUnit(definition: SignalDefinition): DisplayUnit = when (definition.unit) {
        "hPa" -> DisplayUnit("bar", "бар", 0.001, 2)
        "kPa" -> DisplayUnit("bar", "бар", 0.01, 0)
        "N·m" -> DisplayUnit("Nm", "Н·м", 1.0, definition.decimals)
        "mg/st" -> DisplayUnit("mg/st", "мг/цикл", 1.0, definition.decimals)
        "l/h" -> DisplayUnit("l/h", "л/ч", 1.0, definition.decimals)
        "l" -> DisplayUnit("l", "л", 1.0, definition.decimals)
        "mA" -> DisplayUnit("mA", "мА", 1.0, definition.decimals)
        "" -> DisplayUnit("", "", 1.0, definition.decimals)
        else -> DisplayUnit(definition.unit, unitRu(definition.unit), 1.0, definition.decimals)
    }

    private fun unitRu(unit: String): String = when (unit) {
        "V" -> "В"
        "A" -> "А"
        "km" -> "км"
        "rpm" -> "об/мин"
        "min" -> "мин"
        "s" -> "с"
        "m" -> "м"
        "g" -> "г"
        "ms" -> "мс"
        "bar" -> "бар"
        else -> unit
    }

    /** Длинные имена OBDb сокращаем до читаемой подписи плитки. */
    private fun shortEnglish(name: String): String = name
        .replace("Automatic transmission fluid temperature variant 2 DSG S-Tronic", "DSG oil temp")
        .replace("Diesel particulate filter", "DPF")
        .replace("Injection amount deviation cylinder", "Injector dev. cyl")
        .replace("High pressure fuel pressure actual", "Rail pressure")
        .replace("High pressure fuel pressure commanded", "Rail specified")
        .replace("Fuel pressure regulator valve specified rail pressure", "Rail specified")
        .replace("High pressure fuel control deviation", "Rail deviation")
        .replace("Charge air pressure: specified", "Boost specified")
        .replace("Charge air pressure: actual", "Boost actual")
        .replace("Charge air temp at the turbocharger outlet, calculated", "Intercooler outlet")
        .replace("Calculated nitrogen oxide concentration behind turbocharger", "NOx behind turbo")
        .replace("Service regeneration current duration", "Regen duration")
        .replace("Diesel particulate filter fuel consumption since last regeneration", "DPF fuel since regen")
        .replace("Exhaust gas recirculation valve duty", "EGR valve")
        .replace("Oxygen sensor 1 bank 1 lambda value", "Lambda")
        .replace("Turbine actuator 1 bank 1 activation", "Turbine actuator")
        .replace("Radiator output coolant temp: actual", "Coolant (radiator)")
        .replace("Timing angle retardation cylinder", "Timing retard cyl")
        .replace("Transmission input shaft speed", "Gearbox input")
        .replace("Transmission input speed", "Gearbox input")
        .replace("Transmission output speed", "Gearbox output")
        .replace("Battery charge condition", "Battery SOC")
        .take(30)

    private fun labelEn(definition: SignalDefinition): String = when (definition.id) {
        "vag.7e0.1057.boost" -> "Boost pressure (abs)"
        "vag.7e0.2029.charge_specified" -> "Boost specified (abs)"
        "vag.7e0.202a.charge_actual" -> "Boost actual (abs)"
        "vag.7e0.11f1.gearbox_input" -> "Engine: gearbox input"
        "vag.7e1.2102.input_speed" -> "Gearbox input"
        "vag.7e1.2103.output_speed" -> "Gearbox output"
        "vag.7e0.11be.oil_temp" -> "Oil temp (engine)"
        "vag.7e0.179c.oil_temp2" -> "Oil temp 2"
        "vag.7e0.20a1.oil_temp_calc" -> "Oil temp calculated"
        "vag.710.2a07.battery_voltage" -> "Battery sensor"
        else -> shortEnglish(definition.nameEn)
    }

    private fun labelRu(definition: SignalDefinition): String = when (definition.id) {
        "vag.7e0.1057.boost" -> "Наддув (абс.)"
        "vag.7e0.2029.charge_specified" -> "Задано (абс.)"
        "vag.7e0.202a.charge_actual" -> "Факт (абс.)"
        "vag.7e0.11f1.gearbox_input" -> "Вход КП (двиг.)"
        "vag.7e1.2102.input_speed" -> "Вход КП"
        "vag.7e1.2103.output_speed" -> "Выход КП"
        "vag.7e0.11be.oil_temp" -> "Масло (двиг.)"
        "vag.7e0.179c.oil_temp2" -> "Масло 2"
        "vag.7e0.20a1.oil_temp_calc" -> "Масло расч."
        "vag.710.2a07.battery_voltage" -> "АКБ (датчик)"
        else -> definition.nameRu
    }

    private val vagSpecs: List<MetricSpec> = VagSignals.all.map { definition ->
        val unit = displayUnit(definition)
        val category = DisplayCategory.byId(VagSignals.uiCategory[definition.id])
        MetricSpec(
            signalId = definition.id,
            category = category,
            labelEn = labelEn(definition),
            labelRu = labelRu(definition),
            unitEn = unit.en,
            unitRu = unit.ru,
            decimals = unit.decimals,
            factor = unit.factor,
            scaleMin = definition.minValue ?: 0.0,
            scaleMax = definition.maxValue ?: 1.0,
            virtual = false,
            gear = definition.display == ValueDisplay.GEAR,
        )
    }

    private val vagByCategory: Map<DisplayCategory, List<MetricSpec>> =
        vagSpecs.groupBy { it.category }

    /**
     * Три прибора главного экрана: то, что меняется каждую секунду и читается
     * по стрелке. Обороты, скорость и наддув; температуры вынесены в плитки,
     * потому что за поездку они почти не двигаются, а место на шкале занимают.
     */
    /**
     * Три колодца главного экрана: то, за чем следят, чтобы не перегреть
     * и не повредить двигатель. Обороты и скорость видны на плитках —
     * их и так показывает щиток автомобиля.
     */
    val gauges: List<MetricSpec> = listOf(coolant, chargePressure, oil)

    /** Пять плиток: медленные и дискретные величины, которые нужны цифрой. */
    /**
     * Пять плиток главного экрана — только показатели, которые стандартный
     * OBD-II отдаёт на подавляющем большинстве машин и в которых мы уверены.
     *
     * Передача, мгновенный расход и уровень топлива убраны: для передачи
     * нужен DID коробки, который отвечает не всегда, а PID расхода (0x5E)
     * и уровня (0x2F) многие блоки не поддерживают. Вместо них — нагрузка,
     * давление во впуске и напряжение, которые понятны и проверяемы.
     */
    /**
     * Пять плиток: обороты и скорость плюс показатели, по которым видно
     * перегрев, перегрузку и проблемы с питанием.
     */
    val mainTiles: List<MetricSpec> = listOf(dsgTemp, intakeAir, egt, voltage, railBar)

    private val engine = listOf(
        speed, rpm, load, throttle, pedal,
        map, baro, maf, voltage,
        boost, torque, power, absLoad, timing,
        cmdThrottle, relThrottle, absThrottleB, absThrottleC, pedalE,
        torqueDemand, torquePct, torqueRef, runTime,
    )
    private val temperatures = listOf(coolant, oil, intakeAir, ambient, catalyst)
    private val fuel = listOf(
        fuelLevel, fuelUse, fuelRate, rail, railRelative, fuelPressure,
        trimShort, trimLong, lambda, injection, ethanol,
    )
    // OBD-II передачу не отдаёт: в расширенной сетке её заменяет VAG DID.
    private val gearbox = emptyList<MetricSpec>()
    private val turbo = listOf(chargePressure, boost, baro)
    private val diagnostics = listOf(
        voltage, trimShort, trimLong, timing, egr, egrError, catalyst,
        distanceMil, distanceCleared, warmups, runTime, lambda,
    )

    private val byCategory: Map<DisplayCategory, List<MetricSpec>> = mapOf(
        DisplayCategory.ENGINE to engine,
        DisplayCategory.TEMPERATURES to temperatures,
        DisplayCategory.FUEL to fuel,
        DisplayCategory.GEARBOX to gearbox,
        DisplayCategory.TURBO to turbo,
        DisplayCategory.DIAGNOSTICS to diagnostics,
    )

    private val allSpecs: List<MetricSpec> = (
        engine + temperatures + fuel + gearbox + turbo + diagnostics + vagSpecs
        ).distinctBy { it.signalId }
    private val index: Map<String, MetricSpec> = allSpecs.associateBy { it.signalId }

    fun byId(signalId: String): MetricSpec? = index[signalId]

    fun category(category: DisplayCategory, includeVirtual: Boolean): List<MetricSpec> {
        val obd = byCategory[category].orEmpty()
        val vag = vagByCategory[category].orEmpty()
        val vagPrimary = vag.filter { it.signalId in VagSignals.dashboardIds }
        val vagRest = vag.filterNot { it.signalId in VagSignals.dashboardIds }
        // Первая строка остаётся макетной (скорость, обороты, нагрузка…),
        // сразу за ней идут VAG-показатели, а не в конце длинного списка.
        val firstRows = if (category == DisplayCategory.ENGINE) 10 else 5
        return (obd.take(firstRows) + vagPrimary + obd.drop(firstRows) + vagRest)
            .filter { includeVirtual || !it.virtual }
    }

    /** Категории, которые есть смысл показывать: пустую «Коробку» на живых данных скрываем. */
    fun categories(includeVirtual: Boolean): List<DisplayCategory> =
        DisplayCategory.entries.filter { category(it, includeVirtual).isNotEmpty() }

    fun all(): List<MetricSpec> = allSpecs

    fun definition(spec: MetricSpec): SignalDefinition =
        requireNotNull(SignalCatalog.get(spec.signalId))
}
