package io.github.nchalexz.aaperfmon.telemetry

/**
 * Встроенный каталог сигналов.
 *
 * Первый срез каталога — стандартные OBD-II PID (SAE J1979 / ISO 15031-5)
 * плюс несколько вычисляемых показателей. Это осознанное ограничение первой
 * итерации: расширенные DID VAG требуют проверки на конкретном автомобиле,
 * а выдумывать идентификаторы и формулы запрещено техническим заданием.
 *
 * Каждый сигнал несёт источник и статус проверки. Стандартные PID помечены
 * DECODED: формулы взяты из стандарта, но на тестовом автомобиле ещё не
 * сверялись. Ни один сигнал не помечен CONFIRMED — для этого нужен живой
 * автомобиль, а не эмулятор.
 */
object SignalCatalog {

    // Идентификаторы вынесены в константы: опечатка в строке превращается
    // в «нет данных» на приборке, и заметить её трудно.
    object Ids {
        const val SPEED = "vehicle.speed"
        const val GEAR = "vehicle.gear"
        const val RPM = "engine.rpm"
        const val LOAD = "engine.load_pct"
        const val ABS_LOAD = "engine.abs_load_pct"
        const val COOLANT = "engine.coolant_c"
        const val OIL_TEMP = "engine.oil_c"
        const val INTAKE_AIR = "engine.intake_air_c"
        const val AMBIENT = "engine.ambient_c"
        const val MAP = "engine.map_kpa"
        const val BARO = "engine.baro_kpa"
        const val BOOST = "engine.boost_kpa"
        const val MAF = "engine.maf_gs"
        const val THROTTLE = "engine.throttle_pct"
        const val PEDAL = "engine.pedal_pct"
        const val TIMING = "engine.timing_deg"
        const val RUN_TIME = "engine.run_time_s"
        const val TORQUE_DEMAND_PCT = "engine.torque_demand_pct"
        const val TORQUE_PCT = "engine.torque_pct"
        const val TORQUE_REF = "engine.torque_ref_nm"
        const val TORQUE_NM = "engine.torque_nm"
        const val POWER_KW = "engine.power_kw"
        const val FUEL_LEVEL = "fuel.level_pct"
        const val FUEL_RATE = "fuel.rate_lph"
        const val FUEL_L100 = "fuel.l100_km"
        const val FUEL_PRESSURE = "fuel.pressure_kpa"
        const val RAIL_PRESSURE = "fuel.rail_kpa"
        const val RAIL_REL = "fuel.rail_relative_kpa"
        const val TRIM_SHORT = "fuel.trim_short_pct"
        const val TRIM_LONG = "fuel.trim_long_pct"
        const val LAMBDA = "fuel.lambda"
        const val INJECTION_TIMING = "fuel.injection_timing_deg"
        const val EGR = "engine.egr_pct"
        const val EGR_ERROR = "engine.egr_error_pct"
        const val EVAP_PURGE = "engine.evap_purge_pct"
        const val CATALYST = "emissions.catalyst_c"
        const val VOLTAGE = "electrical.voltage_v"
        const val ETHANOL = "fuel.ethanol_pct"
        const val DISTANCE_MIL = "diagnostics.distance_mil_km"
        const val DISTANCE_CLEARED = "diagnostics.distance_cleared_km"
        const val WARMUPS = "diagnostics.warmups"
        const val CMD_THROTTLE = "engine.cmd_throttle_pct"
        const val REL_THROTTLE = "engine.rel_throttle_pct"
        const val ABS_THROTTLE_B = "engine.abs_throttle_b_pct"
        const val ABS_THROTTLE_C = "engine.abs_throttle_c_pct"
        const val PEDAL_E = "engine.pedal_e_pct"
    }

    private const val OBD_SOURCE = "SAE J1979 / ISO 15031-5, mode 01"

    private fun obd(
        id: String,
        nameRu: String,
        nameEn: String,
        category: SignalCategory,
        pid: Int,
        spec: ValueSpec,
        unit: String,
        decimals: Int,
        min: Double?,
        max: Double?,
        pollHz: Double,
        note: String = "",
    ) = SignalDefinition(
        id = id,
        nameRu = nameRu,
        nameEn = nameEn,
        category = category,
        ecu = Ecu.ENGINE,
        protocol = Protocol.OBD2,
        service = 0x01,
        pid = pid,
        decode = spec,
        unit = unit,
        decimals = decimals,
        minValue = min,
        maxValue = max,
        pollHz = pollHz,
        source = OBD_SOURCE,
        verification = Verification.DECODED,
        note = note,
    )

    private fun ratio(scale: Double = 100.0 / 255.0, bias: Double = 0.0) =
        ValueSpec(length = 1, scale = scale, bias = bias)

    private fun word(scale: Double = 1.0, bias: Double = 0.0, signed: Boolean = false) =
        ValueSpec(length = 2, scale = scale, bias = bias, signed = signed)

    /** Стандартные OBD-II PID и вычисляемые величины. */
    private val obdSignals: List<SignalDefinition> = buildList {
        // -- двигатель ------------------------------------------------------
        add(
            obd(
                Ids.RPM, "Обороты двигателя", "Engine speed", SignalCategory.ENGINE,
                0x0C, word(scale = 0.25), "об/мин", 0, 0.0, 8000.0, 10.0,
            )
        )
        add(
            obd(
                Ids.SPEED, "Скорость автомобиля", "Vehicle speed", SignalCategory.ENGINE,
                0x0D, ratio(scale = 1.0), "км/ч", 0, 0.0, 255.0, 10.0,
            )
        )
        add(
            obd(
                Ids.LOAD, "Расчётная нагрузка", "Calculated engine load", SignalCategory.ENGINE,
                0x04, ratio(), "%", 1, 0.0, 100.0, 5.0,
            )
        )
        add(
            obd(
                Ids.ABS_LOAD, "Абсолютная нагрузка", "Absolute engine load", SignalCategory.ENGINE,
                0x43, word(scale = 100.0 / 255.0), "%", 1, 0.0, 100.0, 2.0,
            )
        )
        add(
            obd(
                Ids.MAP, "Давление во впуске", "Intake manifold absolute pressure",
                SignalCategory.ENGINE, 0x0B, ratio(scale = 1.0), "кПа", 0, 0.0, 255.0, 5.0,
            )
        )
        add(
            obd(
                Ids.BARO, "Атмосферное давление", "Barometric pressure", SignalCategory.ENGINE,
                0x33, ratio(scale = 1.0), "кПа", 0, 50.0, 120.0, 0.5,
            )
        )
        add(
            obd(
                Ids.MAF, "Массовый расход воздуха", "Mass air flow", SignalCategory.ENGINE,
                0x10, word(scale = 0.01), "г/с", 1, 0.0, 400.0, 5.0,
            )
        )
        add(
            obd(
                Ids.THROTTLE, "Дроссельная заслонка", "Throttle position", SignalCategory.ENGINE,
                0x11, ratio(), "%", 1, 0.0, 100.0, 5.0,
            )
        )
        add(
            obd(
                Ids.PEDAL, "Педаль акселератора", "Accelerator pedal position D",
                SignalCategory.ENGINE, 0x49, ratio(), "%", 1, 0.0, 100.0, 5.0,
            )
        )
        add(
            obd(
                Ids.TIMING, "Угол опережения зажигания", "Timing advance", SignalCategory.ENGINE,
                0x0E, ValueSpec(length = 1, scale = 0.5, bias = -64.0), "°", 1, -64.0, 64.0, 2.0,
            )
        )
        add(
            obd(
                Ids.RUN_TIME, "Время работы двигателя", "Run time since engine start",
                SignalCategory.ENGINE, 0x1F, word(), "с", 0, 0.0, 65535.0, 0.2,
            )
        )
        add(
            obd(
                Ids.TORQUE_DEMAND_PCT, "Запрошенный момент", "Driver demand torque",
                SignalCategory.ENGINE, 0x61, ratio(scale = 1.0, bias = -125.0), "%", 0,
                -125.0, 130.0, 2.0,
            )
        )
        add(
            obd(
                Ids.TORQUE_REF, "Опорный момент", "Engine reference torque",
                SignalCategory.ENGINE, 0x63, word(), "Н·м", 0, 0.0, 1000.0, 1.0,
            )
        )
        add(
            obd(
                Ids.TORQUE_PCT, "Фактический момент, %", "Actual engine torque",
                SignalCategory.ENGINE, 0x62, ratio(scale = 1.0, bias = -125.0), "%", 0,
                -125.0, 130.0, 2.0,
            )
        )
        add(
            obd(
                Ids.CMD_THROTTLE, "Команда дросселя", "Commanded throttle actuator",
                SignalCategory.ENGINE, 0x4C, ratio(), "%", 1, 0.0, 100.0, 2.0,
            )
        )
        add(
            obd(
                Ids.REL_THROTTLE, "Относительное открытие дросселя", "Relative throttle position",
                SignalCategory.ENGINE, 0x45, ratio(), "%", 1, 0.0, 100.0, 2.0,
            )
        )
        add(
            obd(
                Ids.ABS_THROTTLE_B, "Дроссель, датчик B", "Absolute throttle position B",
                SignalCategory.ENGINE, 0x47, ratio(), "%", 1, 0.0, 100.0, 2.0,
            )
        )
        add(
            obd(
                Ids.ABS_THROTTLE_C, "Дроссель, датчик C", "Absolute throttle position C",
                SignalCategory.ENGINE, 0x48, ratio(), "%", 1, 0.0, 100.0, 2.0,
            )
        )
        add(
            obd(
                Ids.PEDAL_E, "Педаль, датчик E", "Accelerator pedal position E",
                SignalCategory.ENGINE, 0x4A, ratio(), "%", 1, 0.0, 100.0, 2.0,
            )
        )

        // -- температуры ----------------------------------------------------
        add(
            obd(
                Ids.COOLANT, "Охлаждающая жидкость", "Engine coolant temperature",
                SignalCategory.TEMPERATURES, 0x05, ValueSpec(length = 1, bias = -40.0),
                "°C", 0, -40.0, 130.0, 1.0,
            )
        )
        add(
            obd(
                Ids.OIL_TEMP, "Масло двигателя", "Engine oil temperature",
                SignalCategory.TEMPERATURES, 0x5C, ValueSpec(length = 1, bias = -40.0),
                "°C", 0, -40.0, 160.0, 0.5,
                note = "Поддерживается не всеми блоками; при отсутствии показывается «—».",
            )
        )
        add(
            obd(
                Ids.INTAKE_AIR, "Воздух на впуске", "Intake air temperature",
                SignalCategory.TEMPERATURES, 0x0F, ValueSpec(length = 1, bias = -40.0),
                "°C", 0, -40.0, 120.0, 1.0,
            )
        )
        add(
            obd(
                Ids.AMBIENT, "Наружный воздух", "Ambient air temperature",
                SignalCategory.TEMPERATURES, 0x46, ValueSpec(length = 1, bias = -40.0),
                "°C", 0, -40.0, 60.0, 0.5,
            )
        )
        add(
            obd(
                Ids.CATALYST, "Температура катализатора", "Catalyst temperature bank 1 sensor 1",
                SignalCategory.EMISSIONS, 0x3C, word(scale = 0.1, bias = -40.0),
                "°C", 0, -40.0, 1000.0, 0.5,
            )
        )

        // -- топливо --------------------------------------------------------
        add(
            obd(
                Ids.FUEL_LEVEL, "Остаток топлива", "Fuel tank level", SignalCategory.FUEL,
                0x2F, ratio(), "%", 0, 0.0, 100.0, 0.5,
            )
        )
        add(
            obd(
                Ids.FUEL_RATE, "Расход топлива", "Engine fuel rate", SignalCategory.FUEL,
                0x5E, word(scale = 0.05), "л/ч", 1, 0.0, 100.0, 2.0,
            )
        )
        add(
            obd(
                Ids.FUEL_PRESSURE, "Давление топлива", "Fuel pressure", SignalCategory.FUEL,
                0x0A, ratio(scale = 3.0), "кПа", 0, 0.0, 765.0, 1.0,
                note = "Относительное давление для бензиновых систем.",
            )
        )
        add(
            obd(
                Ids.RAIL_PRESSURE, "Давление в топливной рампе", "Fuel rail gauge pressure",
                SignalCategory.FUEL, 0x23, word(scale = 10.0), "кПа", 0, 0.0, 655350.0, 2.0,
            )
        )
        add(
            obd(
                Ids.RAIL_REL, "Давление в рампе относительно впуска",
                "Fuel rail pressure relative to manifold", SignalCategory.FUEL,
                0x22, word(scale = 0.079), "кПа", 0, 0.0, 5177.0, 2.0,
            )
        )
        add(
            obd(
                Ids.TRIM_SHORT, "Краткосрочная топливная коррекция", "Short term fuel trim bank 1",
                SignalCategory.FUEL, 0x06, ValueSpec(length = 1, scale = 100.0 / 128.0, bias = -100.0),
                "%", 1, -100.0, 100.0, 1.0,
            )
        )
        add(
            obd(
                Ids.TRIM_LONG, "Долгосрочная топливная коррекция", "Long term fuel trim bank 1",
                SignalCategory.FUEL, 0x07, ValueSpec(length = 1, scale = 100.0 / 128.0, bias = -100.0),
                "%", 1, -100.0, 100.0, 1.0,
            )
        )
        add(
            obd(
                Ids.LAMBDA, "Лямбда", "Commanded equivalence ratio", SignalCategory.FUEL,
                0x44, word(scale = 1.0 / 32768.0), "λ", 3, 0.0, 2.0, 2.0,
            )
        )
        add(
            obd(
                Ids.INJECTION_TIMING, "Угол впрыска", "Fuel injection timing", SignalCategory.FUEL,
                0x5D, word(scale = 1.0 / 128.0, bias = -210.0), "°", 2, -210.0, 301.0, 1.0,
            )
        )
        add(
            obd(
                Ids.ETHANOL, "Доля этанола", "Ethanol fuel percentage", SignalCategory.FUEL,
                0x52, ratio(), "%", 0, 0.0, 100.0, 0.2,
            )
        )

        // -- экология -------------------------------------------------------
        add(
            obd(
                Ids.EGR, "Команда EGR", "Commanded EGR", SignalCategory.EMISSIONS,
                0x2C, ratio(), "%", 1, 0.0, 100.0, 1.0,
            )
        )
        add(
            obd(
                Ids.EGR_ERROR, "Ошибка EGR", "EGR error", SignalCategory.EMISSIONS,
                0x2D, ValueSpec(length = 1, scale = 100.0 / 128.0, bias = -100.0),
                "%", 1, -100.0, 100.0, 1.0,
            )
        )
        add(
            obd(
                Ids.EVAP_PURGE, "Продувка адсорбера", "Evaporative purge", SignalCategory.EMISSIONS,
                0x2E, ratio(), "%", 1, 0.0, 100.0, 0.5,
            )
        )

        // -- электрика ------------------------------------------------------
        add(
            obd(
                Ids.VOLTAGE, "Напряжение бортовой сети", "Control module voltage",
                SignalCategory.ELECTRICAL, 0x42, word(scale = 0.001), "В", 2, 6.0, 18.0, 0.5,
            )
        )

        // -- диагностика ----------------------------------------------------
        add(
            obd(
                Ids.DISTANCE_MIL, "Пробег с горящей лампой", "Distance with MIL on",
                SignalCategory.DIAGNOSTICS, 0x21, word(), "км", 0, 0.0, 65535.0, 0.2,
            )
        )
        add(
            obd(
                Ids.DISTANCE_CLEARED, "Пробег после сброса ошибок", "Distance since codes cleared",
                SignalCategory.DIAGNOSTICS, 0x31, word(), "км", 0, 0.0, 65535.0, 0.2,
            )
        )
        add(
            obd(
                Ids.WARMUPS, "Прогревы после сброса", "Warm-ups since codes cleared",
                SignalCategory.DIAGNOSTICS, 0x30, ValueSpec(length = 1), "", 0, 0.0, 255.0, 0.1,
            )
        )

        // -- вычисляемые ----------------------------------------------------
        add(
            SignalDefinition(
                id = Ids.TORQUE_NM,
                nameRu = "Фактический момент",
                nameEn = "Actual torque",
                category = SignalCategory.ENGINE,
                computed = ComputedSpec(
                    ComputedKind.TORQUE_NM,
                    listOf(Ids.TORQUE_PCT, Ids.TORQUE_REF),
                ),
                unit = "Н·м",
                decimals = 0,
                minValue = 0.0,
                maxValue = 700.0,
                pollHz = 2.0,
                source = "Вычисление: PID 0x62 × PID 0x63 / 100",
                verification = Verification.DECODED,
                note = "Требует поддержки обоих PID блоком двигателя.",
            )
        )
        add(
            SignalDefinition(
                id = Ids.POWER_KW,
                nameRu = "Мощность",
                nameEn = "Power",
                category = SignalCategory.ENGINE,
                computed = ComputedSpec(ComputedKind.POWER_KW, listOf(Ids.TORQUE_NM, Ids.RPM)),
                unit = "кВт",
                decimals = 0,
                minValue = 0.0,
                maxValue = 500.0,
                pollHz = 2.0,
                source = "Вычисление: момент × обороты / 9549",
                verification = Verification.DECODED,
                note = "Оценка по диагностическому моменту, не стендовый замер.",
            )
        )
        add(
            SignalDefinition(
                id = Ids.BOOST,
                nameRu = "Наддув",
                nameEn = "Boost",
                category = SignalCategory.ENGINE,
                computed = ComputedSpec(ComputedKind.BOOST_KPA, listOf(Ids.MAP, Ids.BARO)),
                unit = "кПа",
                decimals = 0,
                minValue = -100.0,
                maxValue = 250.0,
                pollHz = 5.0,
                source = "Вычисление: PID 0x0B − PID 0x33",
                verification = Verification.DECODED,
                note = "Избыточное давление: отрицательное значение — разрежение.",
            )
        )
        add(
            SignalDefinition(
                id = Ids.FUEL_L100,
                nameRu = "Мгновенный расход",
                nameEn = "Instant consumption",
                category = SignalCategory.FUEL,
                computed = ComputedSpec(ComputedKind.FUEL_L100, listOf(Ids.FUEL_RATE, Ids.SPEED)),
                unit = "л/100 км",
                decimals = 1,
                minValue = 0.0,
                maxValue = 50.0,
                pollHz = 2.0,
                source = "Вычисление: PID 0x5E (л/ч) ÷ PID 0x0D (км/ч) × 100",
                verification = Verification.DECODED,
                note = "На стоянке не определён: показывается «—», а не ноль.",
            )
        )

        // -- виртуальные (появятся с профилем VAG) ---------------------------
        add(
            SignalDefinition(
                id = Ids.GEAR,
                nameRu = "Передача",
                nameEn = "Gear",
                category = SignalCategory.TRANSMISSION,
                ecu = Ecu.TRANSMISSION,
                protocol = Protocol.VIRTUAL,
                unit = "",
                decimals = 0,
                minValue = 0.0,
                maxValue = 8.0,
                pollHz = 2.0,
                display = ValueDisplay.GEAR,
                source = "В демонстрационном режиме — модель; в автомобиле — DID 210F блока 02",
                verification = Verification.DESCRIBED,
                note = "Стандартный OBD-II передачу не отдаёт. Нужен профиль DSG.",
            )
        )
    }

    /**
     * Приоритеты из требований к частоте опроса.
     *
     * Таблица отдельная, а не разбросана по вызовам `obd()`: так видно весь
     * набор приоритетных параметров сразу, и его легко сверить с заданием.
     * Всё, чего здесь нет, — P2.
     */
    private val priorities: Map<String, SignalPriority> = mapOf(
        // P0: то, на что смотрит водитель, и входы расчётного наддува.
        Ids.RPM to SignalPriority.P0,
        Ids.SPEED to SignalPriority.P0,
        Ids.BOOST to SignalPriority.P0,
        Ids.MAP to SignalPriority.P0,
        Ids.BARO to SignalPriority.P0,
        Ids.OIL_TEMP to SignalPriority.P0,
        Ids.COOLANT to SignalPriority.P0,
        // P1: момент, мощность, передача, педаль, давление топлива.
        Ids.TORQUE_NM to SignalPriority.P1,
        Ids.TORQUE_PCT to SignalPriority.P1,
        Ids.TORQUE_REF to SignalPriority.P1,
        Ids.POWER_KW to SignalPriority.P1,
        Ids.GEAR to SignalPriority.P1,
        Ids.PEDAL to SignalPriority.P1,
        Ids.FUEL_PRESSURE to SignalPriority.P1,
        // Передачу настоящий блок отдаёт только по DID 210F.
        "vag.7e1.210f.gear" to SignalPriority.P1,
    )

    /**
     * Передача показывается буквами P/R/N, а не числом. DID 210F приходит
     * со смещением −2, поэтому без этой пометки на приборке было бы «-2».
     */
    private val gearSignals: Set<String> = setOf(Ids.GEAR, "vag.7e1.210f.gear")

    private fun decorate(definitions: List<SignalDefinition>): List<SignalDefinition> =
        definitions.map { definition ->
            val priority = priorities[definition.id]
            val gear = definition.id in gearSignals
            if (priority == null && !gear) {
                definition
            } else {
                definition.copy(
                    priority = priority ?: definition.priority,
                    display = if (gear) ValueDisplay.GEAR else definition.display,
                )
            }
        }

    /**
     * Полный каталог: стандартные PID, вычисляемые показатели и VAG-сигналы
     * из UDS. Последние читаются только через `22 <DID>` и не выдаются за
     * подтверждённые: статус остаётся DECODED.
     */
    val all: List<SignalDefinition> = decorate(obdSignals + VagSignals.all)

    private val index: Map<String, SignalDefinition> = all.associateBy { it.id }

    fun get(id: String): SignalDefinition? = index[id]

    /** Только сигналы, которые можно опросить через OBD-II mode 01. */
    val measured: List<SignalDefinition> = all.filter { it.isMeasured }

    /** Сигналы, которые движок считает сам из уже прочитанных. */
    val computed: List<SignalDefinition> = all.filter { it.isComputed }

    /** VAG-сигналы, читаемые по UDS `22 <DID>`. */
    val uds: List<SignalDefinition> = all.filter { it.isUds }

    fun byCategory(): Map<SignalCategory, List<SignalDefinition>> =
        all.groupBy { it.category }.toSortedMap(compareBy { it.ordinal })

    fun search(query: String): List<SignalDefinition> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return all
        return all.filter {
            it.nameRu.lowercase().contains(needle) ||
                it.nameEn.lowercase().contains(needle) ||
                it.id.lowercase().contains(needle) ||
                it.unit.lowercase().contains(needle)
        }
    }
}
