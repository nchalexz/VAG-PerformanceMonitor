import json, re, sys
from pathlib import Path

src = json.load(open('tools/obdb-audi-q3.json'))
commands = src['commands']
by_key = {}
for c in commands:
    did = c.get('cmd', {}).get('22')
    if not did:
        continue
    for s in c.get('signals', []):
        key = (c['hdr'], did, s.get('name'))
        by_key[key] = (c, s)

# id, hdr, did, name, ui category, dashboard, unit, decimals, pollHz, ru label
SEL = [
 ("vag.7e0.1057.boost", "7E0", "1057", "Boost pressure", "turbo", True, "bar", 2, 2.0, "Давление наддува"),
 ("vag.7e0.2029.charge_specified", "7E0", "2029", "Charge air pressure: specified", "turbo", True, "hPa", 0, 1.0, "Заданное давление наддува"),
 ("vag.7e0.202a.charge_actual", "7E0", "202A", "Charge air pressure: actual", "turbo", True, "hPa", 0, 2.0, "Фактическое давление наддува"),
 ("vag.7e0.11cc.boost_feedback", "7E0", "11CC", "Boost regulator feedback", "turbo", False, "%", 1, 1.0, "Обратная связь регулятора наддува"),
 ("vag.7e0.117e.turbine_actuator", "7E0", "117E", "Turbine actuator 1 bank 1 activation", "turbo", True, "%", 1, 1.0, "Актуатор турбины"),
 ("vag.7e0.1169.rail_actual", "7E0", "1169", "High pressure fuel pressure actual", "fuel", True, "kPa", 0, 2.0, "Давление в рампе"),
 ("vag.7e0.116a.rail_specified", "7E0", "116A", "High pressure fuel pressure commanded", "fuel", False, "kPa", 0, 1.0, "Заданное давление в рампе"),
 ("vag.7e0.121e.rail_regulator", "7E0", "121E", "Fuel pressure regulator valve specified rail pressure", "fuel", False, "kPa", 0, 0.5, "Заданное давление регулятора"),
 ("vag.7e0.121f.rail_deviation", "7E0", "121F", "High pressure fuel control deviation", "diagnostics", False, "kPa", 0, 1.0, "Отклонение давления топлива"),
 ("vag.7e0.111a.fuel_flow", "7E0", "111A", "Fuel flow rate", "fuel", False, "l/h", 1, 1.0, "Расход топлива"),
 ("vag.7e0.1024.maf", "7E0", "1024", "Air mass meter 1", "engine", False, "g/s", 1, 2.0, "Массовый расход воздуха"),
 ("vag.7e0.1047.torque", "7E0", "1047", "Engine torque", "engine", False, "N·m", 0, 2.0, "Момент двигателя"),
 ("vag.7e0.106b.torque_limit", "7E0", "106B", "Torque limit", "engine", False, "N·m", 0, 1.0, "Ограничение момента"),
 ("vag.7e0.10c2.egr_duty", "7E0", "10C2", "Exhaust gas recirculation valve duty", "diagnostics", False, "%", 1, 1.0, "Клапан EGR"),
 ("vag.7e0.113d.lambda", "7E0", "113D", "Oxygen sensor 1 bank 1 lambda value", "fuel", False, "λ", 2, 1.0, "Лямбда"),
 ("vag.7e0.10ff.injector1", "7E0", "10FF", "Injection amount deviation cylinder 1", "diagnostics", True, "mg/st", 2, 0.5, "Коррекция форсунки 1"),
 ("vag.7e0.1105.injector2", "7E0", "1105", "Injection amount deviation cylinder 2", "diagnostics", True, "mg/st", 2, 0.5, "Коррекция форсунки 2"),
 ("vag.7e0.1100.injector3", "7E0", "1100", "Injection amount deviation cylinder 3", "diagnostics", True, "mg/st", 2, 0.5, "Коррекция форсунки 3"),
 ("vag.7e0.1104.injector4", "7E0", "1104", "Injection amount deviation cylinder 4", "diagnostics", True, "mg/st", 2, 0.5, "Коррекция форсунки 4"),
 ("vag.7e0.11f2.main_injection_start", "7E0", "11F2", "Main injection start position", "fuel", False, "°", 1, 0.5, "Начало основного впрыска"),
 ("vag.7e0.14cf.main_injection_duration", "7E0", "14CF", "Main injection duration", "fuel", False, "ms", 2, 0.5, "Длительность впрыска"),
 ("vag.7e0.11be.oil_temp", "7E0", "11BE", "Oil temperature variant", "temperatures", True, "°C", 0, 1.0, "Температура масла"),
 ("vag.7e0.179c.oil_temp2", "7E0", "179C", "Oil temp 2", "temperatures", False, "°C", 0, 0.5, "Температура масла 2"),
 ("vag.7e0.20a1.oil_temp_calc", "7E0", "20A1", "Calculated oil temperature", "temperatures", False, "°C", 0, 0.5, "Расчётная температура масла"),
 ("vag.7e0.1626.coolant", "7E0", "1626", "Radiator output coolant temp: actual", "temperatures", False, "°C", 0, 1.0, "Температура ОЖ"),
 ("vag.7e0.162d.fuel_temp", "7E0", "162D", "Fuel temperature", "temperatures", True, "°C", 0, 1.0, "Температура топлива"),
 ("vag.7e0.1798.intercooler_temp", "7E0", "1798", "Charge air temp at the tubrocharger outlet, calculated", "temperatures", False, "°C", 0, 1.0, "Воздух после интеркулера"),
 ("vag.7e0.10fb.egt1", "7E0", "10FB", "Exhaust gas temperature sensor 1", "temperatures", True, "°C", 0, 1.0, "Температура выхлопа 1"),
 ("vag.7e0.14f7.egt2", "7E0", "14F7", "Exhaust gas temperature sensor 2", "temperatures", False, "°C", 0, 1.0, "Температура выхлопа 2"),
 ("vag.7e0.11b2.dpf_inlet", "7E0", "11B2", "Diesel particulate filter inlet temperature", "diagnostics", True, "°C", 0, 0.5, "Температура перед DPF"),
 ("vag.7e0.10f9.dpf_outlet", "7E0", "10F9", "Diesel particulate filter outlet temperature", "diagnostics", True, "°C", 0, 0.5, "Температура после DPF"),
 ("vag.7e0.1044.dpf_surface", "7E0", "1044", "Diesel particulate filter surface temperature", "diagnostics", False, "°C", 0, 0.2, "Температура DPF"),
 ("vag.7e0.114e.soot_measured", "7E0", "114E", "Soot mass measured", "diagnostics", True, "g", 2, 0.2, "Сажа измеренная"),
 ("vag.7e0.114f.soot_calculated", "7E0", "114F", "Soot mass calculated", "diagnostics", True, "g", 2, 0.2, "Сажа расчётная"),
 ("vag.7e0.1153.ash_volume", "7E0", "1153", "Diesel particulate filter oil ash volume", "diagnostics", False, "l", 2, 0.1, "Объём золы DPF"),
 ("vag.7e0.1354.soot_index", "7E0", "1354", "Soot index", "diagnostics", False, "m", 0, 0.1, "Индекс сажи"),
 ("vag.7e0.1526.nox", "7E0", "1526", "Calculated nitrogen oxide concentration behind turbocharger", "diagnostics", False, "", 0, 0.5, "NOx за турбиной"),
 ("vag.7e0.1164.regen_duration", "7E0", "1164", "Service regeneration current duration", "diagnostics", False, "min", 0, 0.1, "Длительность регенерации"),
 ("vag.7e0.115a.dpf_fuel_since_regen", "7E0", "115A", "Diesel particulate filter fuel consumption since last regeneration", "diagnostics", False, "l", 2, 0.1, "Топливо после регенерации"),
 ("vag.7e0.11bc.oil_level", "7E0", "11BC", "Engine oil level", "engine", False, "mm", 0, 0.2, "Уровень масла"),
 ("vag.7e0.200a.timing_retard1", "7E0", "200A", "Timing angle retardation cylinder 1", "diagnostics", False, "°", 1, 0.5, "Коррекция зажигания 1"),
 ("vag.7e0.200b.timing_retard2", "7E0", "200B", "Timing angle retardation cylinder 2", "diagnostics", False, "°", 1, 0.5, "Коррекция зажигания 2"),
 ("vag.7e0.200c.timing_retard3", "7E0", "200C", "Timing angle retardation cylinder 3", "diagnostics", False, "°", 1, 0.5, "Коррекция зажигания 3"),
 ("vag.7e0.200d.timing_retard4", "7E0", "200D", "Timing angle retardation cylinder 4", "diagnostics", False, "°", 1, 0.5, "Коррекция зажигания 4"),
 ("vag.7e0.11f1.gearbox_input", "7E0", "11F1", "Transmission input shaft speed", "gearbox", False, "rpm", 0, 1.0, "Вход КП"),
 ("vag.7e0.11bf.fuel_regulator", "7E0", "11BF", "Fuel pressure regulator position", "fuel", False, "%", 1, 0.5, "Регулятор давления"),
 ("vag.7e0.1114.fan2", "7E0", "1114", "Coolant fan 2 duty cycle", "engine", False, "%", 0, 0.2, "Вентилятор 2"),
 ("vag.7e0.1116.fan1", "7E0", "1116", "Coolant fan 1 duty cycle", "engine", False, "%", 0, 0.2, "Вентилятор 1"),

 ("vag.7e1.2104.atf_temp", "7E1", "2104", "Automatic transmission fluid temperature variant 2 DSG S-Tronic", "gearbox", True, "°C", 0, 0.5, "Температура масла DSG"),
 ("vag.7e1.102b.clutch1_temp", "7E1", "102B", "Clutch 1 temperature", "gearbox", True, "°C", 0, 0.5, "Температура сцепления K1"),
 ("vag.7e1.102c.clutch2_temp", "7E1", "102C", "Clutch 2 temperature", "gearbox", True, "°C", 0, 0.5, "Температура сцепления K2"),
 ("vag.7e1.210f.gear", "7E1", "210F", "Current gear", "gearbox", True, "", 0, 2.0, "Текущая передача"),
 ("vag.7e1.2102.input_speed", "7E1", "2102", "Transmission input speed", "gearbox", True, "rpm", 0, 2.0, "Входные обороты"),
 ("vag.7e1.2103.output_speed", "7E1", "2103", "Transmission output speed", "gearbox", True, "rpm", 0, 2.0, "Выходные обороты"),
 ("vag.7e1.38eb.clutch1_pressure", "7E1", "38EB", "Clutch 1 actual pressure", "gearbox", True, "bar", 2, 1.0, "Давление K1"),
 ("vag.7e1.38ec.clutch1_pressure_cmd", "7E1", "38EC", "Clutch 1 commanded pressure", "gearbox", False, "bar", 2, 0.5, "Задано давление K1"),
 ("vag.7e1.38ea.clutch2_pressure", "7E1", "38EA", "Clutch 2 actual pressure", "gearbox", True, "bar", 2, 1.0, "Давление K2"),
 ("vag.7e1.38ed.clutch2_pressure_cmd", "7E1", "38ED", "Clutch 2 commanded pressure", "gearbox", False, "bar", 2, 0.5, "Задано давление K2"),
 ("vag.7e1.3839.clutch2_torque", "7E1", "3839", "Clutch 2 actual torque", "gearbox", False, "N·m", 0, 1.0, "Момент K2"),
 ("vag.7e1.382e.clutch1_torque_cmd", "7E1", "382E", "Specified torque clutch 1", "gearbox", False, "N·m", 0, 1.0, "Задан момент K1"),
 ("vag.7e1.381d.oil_pressure", "7E1", "381D", "Oil pressure actual", "gearbox", False, "bar", 2, 0.5, "Давление масла КП"),
 ("vag.7e1.028d.ecu_temp", "7E1", "028D", "ECU temperature", "gearbox", False, "°C", 0, 0.2, "Температура мехатроника"),
 ("vag.7e1.100c.engine_torque", "7E1", "100C", "Engine torque", "gearbox", False, "N·m", 0, 1.0, "Момент двигателя (КП)"),
 ("vag.7e1.100b.driver_torque", "7E1", "100B", "Driver demand torque", "gearbox", False, "N·m", 0, 1.0, "Запрос момента"),
 ("vag.7e1.380c.input_shaft1", "7E1", "380C", "Input shaft 1", "gearbox", False, "rpm", 0, 1.0, "Вал 1"),
 ("vag.7e1.380d.input_shaft2", "7E1", "380D", "Input shaft 2", "gearbox", False, "rpm", 0, 1.0, "Вал 2"),
 ("vag.7e1.380e.output_shaft1", "7E1", "380E", "Output shaft 1", "gearbox", False, "rpm", 0, 1.0, "Выходной вал 1"),
 ("vag.7e1.380f.output_shaft2", "7E1", "380F", "Output shaft 2", "gearbox", False, "rpm", 0, 1.0, "Выходной вал 2"),
 ("vag.7e1.3821.pressure1", "7E1", "3821", "Transmission part 1 pressure actual", "gearbox", False, "bar", 2, 0.5, "Давление контура 1"),
 ("vag.7e1.3824.pressure2", "7E1", "3824", "Transmission part 2 pressure actual", "gearbox", False, "bar", 2, 0.5, "Давление контура 2"),
 ("vag.7e1.3835.clutch1_current", "7E1", "3835", "Clutch 1 valve current: actual", "gearbox", False, "mA", 0, 0.5, "Ток K1"),
 ("vag.7e1.383e.clutch2_current", "7E1", "383E", "Clutch 2 valve current: actual", "gearbox", False, "mA", 0, 0.5, "Ток K2"),
 ("vag.7e1.1118.clutch1_max_temp", "7E1", "1118", "Clutch #1 maximum temperature", "gearbox", False, "°C", 0, 0.1, "Макс. температура K1"),
 ("vag.7e1.1118.clutch2_max_temp", "7E1", "1118", "Clutch #2 maximum temperature", "gearbox", False, "°C", 0, 0.1, "Макс. температура K2"),
 ("vag.7e1.388d.coupling1", "7E1", "388D", "Lower adapted coupling point clutch 1 from kiss point adaptation", "gearbox", False, "A", 2, 0.1, "Точка K1"),
 ("vag.7e1.388e.coupling2", "7E1", "388E", "Lower adapted coupling point clutch 2 from kiss point adaptation", "gearbox", False, "A", 2, 0.1, "Точка K2"),

 ("vag.714.202f.oil_temp", "714", "202F", "Oil temperature", "temperatures", True, "°C", 0, 1.0, "Температура масла (щиток)"),
 ("vag.714.2206.fuel_level", "714", "2206", "Fuel level", "fuel", False, "l", 1, 0.2, "Остаток топлива (л)"),

 ("vag.710.2a07.battery_voltage", "710", "2A07", "Battery voltage", "engine", True, "V", 2, 0.5, "Напряжение АКБ"),
 ("vag.710.2a0c.battery_soc", "710", "2A0C", "Battery charge condition", "diagnostics", True, "%", 0, 0.2, "Заряд АКБ"),
 ("vag.710.2a0b.battery_temp", "710", "2A0B", "Battery temperature", "diagnostics", False, "°C", 0, 0.2, "Температура АКБ"),
 ("vag.710.2a09.battery_current", "710", "2A09", "Battery current", "diagnostics", False, "A", 1, 0.5, "Ток АКБ"),
 ("vag.710.2a00.generator_target", "710", "2A00", "Generator target voltage", "diagnostics", False, "V", 1, 0.2, "Целевое напряжение генератора"),
]

def unit_of(fmt):
    return fmt.get('unit')

def fixed(v):
    text = "%.6g" % float(v)
    if "." not in text and "e" not in text:
        text += ".0"
    return text

out = []
out.append('''// Сгенерировано tools/obdb_import.py из OBDb/Audi-Q3 signalsets/v3/default.json.
// Источник данных: https://github.com/OBDb/Audi-Q3, commit b51e835, CC BY-SA 4.0.
// Не редактировать вручную: правки потеряются при следующей генерации.
package io.github.nchalexz.aaperfmon.telemetry

/**
 * VAG-специфичные сигналы MQB TDI, прочитанные через UDS `22 <DID>`.
 *
 * Это не «все параметры всех блоков»: выбраны живые показатели, у которых
 * в открытом наборе OBDb есть DID, адрес блока и формула. Сигналы с
 * требованием расширенной сессии (`10 03`) исключены: приложение только
 * читает. Ни один сигнал не помечен подтверждённым на автомобиле —
 * для этого нужен живой MQB TDI, а не эмулятор.
 */
object VagSignals {

    private const val SOURCE =
        "OBDb/Audi-Q3 v3 @b51e835, UDS 22 (CC BY-SA 4.0)"

    private fun uds(
        id: String,
        nameRu: String,
        nameEn: String,
        category: SignalCategory,
        txId: Int,
        rxId: Int,
        did: Int,
        spec: UdsValueSpec,
        unit: String,
        decimals: Int,
        min: Double?,
        max: Double?,
        pollHz: Double,
    ) = SignalDefinition(
        id = id,
        nameRu = nameRu,
        nameEn = nameEn,
        category = category,
        ecu = when (txId) {
            0x7E0 -> Ecu.ENGINE
            0x7E1 -> Ecu.TRANSMISSION
            0x714 -> Ecu.CLUSTER
            0x710 -> Ecu.BODY
            else -> Ecu.UNKNOWN
        },
        protocol = Protocol.UDS,
        service = 0x22,
        did = did,
        uds = spec,
        txId = txId,
        rxId = rxId,
        unit = unit,
        decimals = decimals,
        minValue = min,
        maxValue = max,
        pollHz = pollHz,
        source = "$SOURCE, %04X".format(did),
        verification = Verification.DECODED,
        note = "Формула из открытого набора OBDb; на MQB TDI не проверялась.",
    )

    val all: List<SignalDefinition> = listOf(
''')

ui_entries = []
dash_ids = []
missing = []
for (sid, hdr, did, name, ui, dash, unit, dec, hz, ru) in SEL:
    key = (hdr, did, name)
    if key not in by_key:
        missing.append(key)
        continue
    c, s = by_key[key]
    f = s['fmt']
    tx = int(hdr, 16)
    rax = c.get('rax')
    if not rax:
        rax = {'7E0':'7E8','7E1':'7E9','714':'77E','710':'77A'}[hdr]
    rx = int(rax, 16)
    bix = f.get('bix', 0)
    length = f['len']
    signed = bool(f.get('sign'))
    le = bool(f.get('blsb'))
    mul = f.get('mul', 1)
    div = f.get('div', 1)
    add = f.get('add', 0)
    mn = f.get('min')
    mx = f.get('max')
    srcunit = f.get('unit')
    # подсказка: единица из OBDb не всегда совпадает с физической
    cat = {'turbo':'ENGINE','engine':'ENGINE','temperatures':'TEMPERATURES','fuel':'FUEL',
           'gearbox':'TRANSMISSION','diagnostics':'DIAGNOSTICS','emissions':'EMISSIONS'}[ui]
    out.append('        uds(\n')
    out.append('            id = "%s",\n' % sid)
    out.append('            nameRu = "%s",\n' % ru.replace('"','\\"'))
    out.append('            nameEn = "%s",\n' % name.replace('"','\\"').replace('tubrocharger','turbocharger'))
    out.append('            category = SignalCategory.%s,\n' % cat)
    out.append('            txId = 0x%03X,\n' % tx)
    out.append('            rxId = 0x%03X,\n' % rx)
    out.append('            did = 0x%s,\n' % did)
    out.append('            spec = UdsValueSpec(bitOffset = %d, bitLength = %d, signed = %s, littleEndian = %s, mul = %s, div = %s, add = %s),\n' % (
        bix, length, 'true' if signed else 'false', 'true' if le else 'false', fixed(mul), fixed(div), fixed(add)))
    out.append('            unit = "%s",\n' % unit)
    out.append('            decimals = %d,\n' % dec)
    out.append('            min = %s,\n' % ('null' if mn is None else fixed(mn)))
    out.append('            max = %s,\n' % ('null' if mx is None else fixed(mx)))
    out.append('            pollHz = %s,\n' % fixed(hz))
    out.append('        ),\n')
    ui_entries.append((sid, ui))
    if dash:
        dash_ids.append(sid)

out.append('    )\n\n')
out.append('    /** К какой вкладке расширенного экрана относить сигнал. */\n')
out.append('    val uiCategory: Map<String, String> = mapOf(\n')
for sid, ui in ui_entries:
    out.append('        "%s" to "%s",\n' % (sid, ui))
out.append('    )\n\n')
out.append('    /** Что показывать на расширенном экране в первую очередь. */\n')
out.append('    val dashboardIds: Set<String> = setOf(\n')
for sid in dash_ids:
    out.append('        "%s",\n' % sid)
out.append('    )\n')
out.append('}\n')

Path('app/src/main/java/io/github/nchalexz/aaperfmon/telemetry/VagSignals.kt').write_text(''.join(out))
print('selected', len(ui_entries), 'missing', missing)
