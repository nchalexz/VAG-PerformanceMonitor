package io.github.nchalexz.aaperfmon.telemetry

import io.github.nchalexz.aaperfmon.core.Elm327
import io.github.nchalexz.aaperfmon.core.IsoTp
import io.github.nchalexz.aaperfmon.core.UdsClient
import io.github.nchalexz.aaperfmon.core.UdsError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin

/** Одно прочитанное значение: число либо честная причина его отсутствия. */
data class Reading(
    val value: Double?,
    val state: ReadingState,
)

/**
 * Источник значений сигналов.
 *
 * Движок телеметрии не знает, откуда пришло число: из демонстрационной
 * модели или из адаптера. Это позволяет проверять расписание опроса и
 * приборы без автомобиля, а затем подменить источник одним движением.
 */
interface SignalSource {
    val label: String
    suspend fun read(definition: SignalDefinition): Reading
    fun close() {}
}

/**
 * Демонстрационный источник.
 *
 * Числа связаны между собой: обороты растут со скоростью, наддув — с
 * оборотами, расход — с мощностью. Это не «красивые графики», а модель,
 * на которой видно, что приборы и вычисляемые показатели согласованы.
 *
 * Данные всегда помечаются как ДЕМО в интерфейсе. Ни один результат этой
 * модели не является подтверждением совместимости с автомобилем.
 */
class DemoSignalSource(
    private val clock: () -> Long = System::currentTimeMillis,
) : SignalSource {

    override val label: String = "ДЕМО"

    /**
     * Отсчёт от запуска источника, а не от начала эпохи: при старте модель
     * стоит ровно на значениях из макетов и дальше плавно уходит от них.
     * Так демонстрационный экран можно сравнивать с макетом один в один.
     */
    private val startedAt: Long = clock()

    override suspend fun read(definition: SignalDefinition): Reading {
        val t = (clock() - startedAt) / 1000.0
        // Первые секунды модель стоит на значениях из макетов, затем
        // плавно начинает движение: так экран можно сверить с макетом
        // и одновременно увидеть анимацию стрелок.
        val amplitude = ((t - 3.0) / 6.0).coerceIn(0.0, 1.0)
        val value = valueFor(definition.id, t, amplitude)
            ?: return Reading(null, ReadingState.UNSUPPORTED)
        return Reading(value, ReadingState.OK)
    }

    private fun valueFor(id: String, t: Double, amplitude: Double): Double? {
        // Базовые числа взяты из макетов: 96 км/ч, 1840 об/мин, 5 передача,
        // 48 % нагрузки, 0,62 бар наддува, 14,6 В, 58 % топлива.
        // Синусоиды дают плавное движение вокруг базы, не выдумывая
        // несуществующие датчики: все эти сигналы есть в каталоге.
        val speed = 96.0 + amplitude * 9.0 * sin(t * 0.12)
        val rpm = 1840.0 + amplitude * 160.0 * sin(t * 0.21)
        val gear = 5
        val map = 162.0 + amplitude * 15.0 * sin(t * 0.23)
        val baro = 100.0
        val boost = map - baro
        val load = 48.0 + amplitude * 12.0 * sin(t * 0.17)
        val throttle = 21.0 + amplitude * 5.0 * sin(t * 0.17)
        val pedal = 19.0 + amplitude * 6.0 * sin(t * 0.17)
        val maf = 42.5 + amplitude * 6.0 * sin(t * 0.19)
        val fuelRate = 4.8 + amplitude * 1.2 * sin(t * 0.30)
        val torquePct = (45.0 + amplitude * 20.0 * sin(t * 0.25)).coerceIn(0.0, 100.0)
        val torqueRef = 380.0
        val torqueNm = torquePct / 100.0 * torqueRef

        return when (id) {
            SignalCatalog.Ids.SPEED -> speed
            SignalCatalog.Ids.GEAR -> gear.toDouble()
            SignalCatalog.Ids.RPM -> rpm
            SignalCatalog.Ids.LOAD -> load.coerceIn(0.0, 100.0)
            SignalCatalog.Ids.ABS_LOAD -> (load + 2.0).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.COOLANT -> 86.0 + amplitude * 2.0 * sin(t * 0.05)
            SignalCatalog.Ids.OIL_TEMP -> 95.0 + amplitude * 3.0 * sin(t * 0.04)
            SignalCatalog.Ids.INTAKE_AIR -> 31.0 + amplitude * 4.0 * sin(t * 0.11)
            SignalCatalog.Ids.AMBIENT -> 18.0 + amplitude * 1.5 * sin(t * 0.01)
            SignalCatalog.Ids.MAP -> map
            SignalCatalog.Ids.BARO -> baro
            SignalCatalog.Ids.BOOST -> boost
            SignalCatalog.Ids.MAF -> maf.coerceIn(2.0, 160.0)
            SignalCatalog.Ids.THROTTLE -> throttle.coerceIn(0.0, 100.0)
            SignalCatalog.Ids.REL_THROTTLE -> (throttle - 2.0).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.ABS_THROTTLE_B -> (throttle - 3.0).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.ABS_THROTTLE_C -> (throttle - 4.0).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.CMD_THROTTLE -> (throttle + 1.0).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.PEDAL -> pedal.coerceIn(0.0, 100.0)
            SignalCatalog.Ids.PEDAL_E -> (pedal - 1.0).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.TIMING -> 8.0 + amplitude * 10.0 * sin(t * 0.5)
            SignalCatalog.Ids.RUN_TIME -> t % 3600.0
            SignalCatalog.Ids.TORQUE_PCT -> torquePct
            SignalCatalog.Ids.TORQUE_DEMAND_PCT -> (torquePct + 10.0).coerceAtMost(100.0)
            SignalCatalog.Ids.TORQUE_REF -> torqueRef
            SignalCatalog.Ids.TORQUE_NM -> torqueNm
            SignalCatalog.Ids.POWER_KW -> torqueNm * rpm / 9549.0
            SignalCatalog.Ids.FUEL_LEVEL -> (58.0 - (t * 0.004) % 40.0).coerceAtLeast(0.0)
            SignalCatalog.Ids.FUEL_RATE -> fuelRate.coerceIn(2.0, 25.0)
            SignalCatalog.Ids.FUEL_L100 -> if (speed < 3.0) null else fuelRate / speed * 100.0
            SignalCatalog.Ids.FUEL_PRESSURE -> 320.0 + 20.0 * sin(t * 0.2)
            SignalCatalog.Ids.RAIL_PRESSURE -> 42000.0 + 3000.0 * sin(t * 0.3)
            SignalCatalog.Ids.RAIL_REL -> 22000.0 + 2000.0 * sin(t * 0.3)
            SignalCatalog.Ids.TRIM_SHORT -> -3.0 + 6.0 * sin(t * 0.2)
            SignalCatalog.Ids.TRIM_LONG -> -2.0 + 3.0 * sin(t * 0.05)
            SignalCatalog.Ids.LAMBDA -> 1.0 + 0.08 * sin(t * 1.2)
            SignalCatalog.Ids.INJECTION_TIMING -> -4.0 + 6.0 * sin(t * 0.6)
            SignalCatalog.Ids.EGR -> 28.0 + 18.0 * sin(t * 0.2)
            SignalCatalog.Ids.EGR_ERROR -> -2.0 + 4.0 * sin(t * 0.3)
            SignalCatalog.Ids.EVAP_PURGE -> 8.0 + 7.0 * sin(t * 0.1)
            SignalCatalog.Ids.CATALYST -> 320.0 + 60.0 * sin(t * 0.03)
            SignalCatalog.Ids.VOLTAGE -> 14.6 + 0.2 * sin(t * 0.07)
            SignalCatalog.Ids.ETHANOL -> 5.0
            SignalCatalog.Ids.DISTANCE_MIL -> 0.0
            SignalCatalog.Ids.DISTANCE_CLEARED -> 128.0
            SignalCatalog.Ids.WARMUPS -> 12.0
            else -> vagValue(id, t, amplitude)
        }
    }

    /**
     * Демонстрационные значения VAG-сигналов.
     *
     * Нужны, чтобы вкладки «Коробка», «Турбо» и «Диагностика» можно было
     * проверить без автомобиля. Это модель, а не данные блока: на экране
     * всегда горит ДЕМО. На реальной машине те же DID читаются через UDS.
     */
    private fun vagValue(id: String, t: Double, amplitude: Double): Double? = when (id) {
        "vag.7e0.1057.boost" -> 1.62 + amplitude * 0.05 * sin(t * 0.23)
        "vag.7e0.2029.charge_specified" -> 1620.0
        "vag.7e0.202a.charge_actual" -> 1610.0 + amplitude * 20.0 * sin(t * 0.23)
        "vag.7e0.117e.turbine_actuator" -> 45.0
        "vag.7e0.11cc.boost_feedback" -> 42.0
        "vag.7e0.1169.rail_actual" -> 48_000.0 + amplitude * 900.0 * sin(t * 0.3)
        "vag.7e0.116a.rail_specified" -> 48_000.0
        "vag.7e0.121e.rail_regulator" -> 48_000.0
        "vag.7e0.121f.rail_deviation" -> 120.0
        "vag.7e0.111a.fuel_flow" -> 4.8
        "vag.7e0.1024.maf" -> 42.5 + amplitude * 3.0 * sin(t * 0.19)
        "vag.7e0.1047.torque" -> 181.0
        "vag.7e0.106b.torque_limit" -> 380.0
        "vag.7e0.10c2.egr_duty" -> 28.0
        "vag.7e0.113d.lambda" -> 1.0
        "vag.7e0.10ff.injector1" -> 0.12
        "vag.7e0.1105.injector2" -> -0.08
        "vag.7e0.1100.injector3" -> 0.05
        "vag.7e0.1104.injector4" -> -0.09
        "vag.7e0.11f2.main_injection_start" -> -4.0
        "vag.7e0.14cf.main_injection_duration" -> 0.85
        "vag.7e0.11be.oil_temp" -> 95.0 + amplitude * 2.0 * sin(t * 0.04)
        "vag.7e0.179c.oil_temp2" -> 92.0
        "vag.7e0.20a1.oil_temp_calc" -> 94.0
        "vag.7e0.1626.coolant" -> 86.0 + amplitude * 1.5 * sin(t * 0.05)
        "vag.7e0.162d.fuel_temp" -> 30.0
        "vag.7e0.1798.intercooler_temp" -> 42.0
        "vag.7e0.10fb.egt1" -> 320.0 + amplitude * 20.0 * sin(t * 0.07)
        "vag.7e0.14f7.egt2" -> 380.0
        "vag.7e0.11b2.dpf_inlet" -> 280.0
        "vag.7e0.10f9.dpf_outlet" -> 260.0
        "vag.7e0.1044.dpf_surface" -> 250.0
        "vag.7e0.114e.soot_measured" -> 6.4
        "vag.7e0.114f.soot_calculated" -> 5.8
        "vag.7e0.1153.ash_volume" -> 0.12
        "vag.7e0.1354.soot_index" -> 120.0
        "vag.7e0.1526.nox" -> 80.0
        "vag.7e0.1164.regen_duration" -> 0.0
        "vag.7e0.115a.dpf_fuel_since_regen" -> 3.2
        "vag.7e0.11bc.oil_level" -> 72.0
        "vag.7e0.200a.timing_retard1" -> 0.0
        "vag.7e0.200b.timing_retard2" -> 0.0
        "vag.7e0.200c.timing_retard3" -> 0.0
        "vag.7e0.200d.timing_retard4" -> 0.0
        "vag.7e0.11f1.gearbox_input" -> 1840.0
        "vag.7e0.11bf.fuel_regulator" -> 32.0
        "vag.7e0.1114.fan2" -> 0.0
        "vag.7e0.1116.fan1" -> 0.0
        "vag.7e1.2104.atf_temp" -> 82.0 + amplitude * 1.5 * sin(t * 0.03)
        "vag.7e1.102b.clutch1_temp" -> 65.0
        "vag.7e1.102c.clutch2_temp" -> 63.0
        "vag.7e1.210f.gear" -> 5.0
        "vag.7e1.2102.input_speed" -> 1840.0
        "vag.7e1.2103.output_speed" -> 720.0
        "vag.7e1.38eb.clutch1_pressure" -> 4.2
        "vag.7e1.38ec.clutch1_pressure_cmd" -> 4.0
        "vag.7e1.38ea.clutch2_pressure" -> 0.5
        "vag.7e1.38ed.clutch2_pressure_cmd" -> 0.6
        "vag.7e1.3839.clutch2_torque" -> 120.0
        "vag.7e1.382e.clutch1_torque_cmd" -> 180.0
        "vag.7e1.381d.oil_pressure" -> 4.5
        "vag.7e1.028d.ecu_temp" -> 70.0
        "vag.7e1.100c.engine_torque" -> 181.0
        "vag.7e1.100b.driver_torque" -> 190.0
        "vag.7e1.380c.input_shaft1" -> 1840.0
        "vag.7e1.380d.input_shaft2" -> 0.0
        "vag.7e1.380e.output_shaft1" -> 720.0
        "vag.7e1.380f.output_shaft2" -> 0.0
        "vag.7e1.3821.pressure1" -> 4.2
        "vag.7e1.3824.pressure2" -> 0.5
        "vag.7e1.3835.clutch1_current" -> 850.0
        "vag.7e1.383e.clutch2_current" -> 120.0
        "vag.7e1.1118.clutch1_max_temp" -> 90.0
        "vag.7e1.1118.clutch2_max_temp" -> 88.0
        "vag.7e1.388d.coupling1" -> 0.9
        "vag.7e1.388e.coupling2" -> 0.2
        "vag.714.202f.oil_temp" -> 95.0
        "vag.714.2206.fuel_level" -> 42.0
        "vag.710.2a07.battery_voltage" -> 14.6
        "vag.710.2a0c.battery_soc" -> 78.0
        "vag.710.2a0b.battery_temp" -> 28.0
        "vag.710.2a09.battery_current" -> 12.5
        "vag.710.2a00.generator_target" -> 14.4
        else -> null
    }
}

/**
 * Настоящий источник: стандартные PID через ELM327.
 *
 * Один запрос — один сигнал. Движок специально не объединяет запросы:
 * дешёвые адаптеры плохо переносят несколько PID в одной команде, а
 * выигрыш в скорости не стоит потерянных ответов.
 *
 * Блокирующий обмен адаптера уведён в Dispatchers.IO: движок остаётся
 * корутинным и не замораживает интерфейс.
 */
class Elm327SignalSource(
    private val elm: Elm327,
    private val timeoutMs: Long = 600,
) : SignalSource {

    override val label: String = "ELM327"

    @Volatile
    private var headerPrepared = false

    /** По клиенту на блок: у каждого свой адрес запроса и своя история «занят». */
    private val udsClients = mutableMapOf<Int, UdsClient>()

    /**
     * Ответ на DID кэшируется на четверть секунды: несколько сигналов одного
     * блока часто лежат в одном ответе, и повторять запрос к шине ради них
     * бессмысленно.
     */
    private val udsCache = mutableMapOf<Long, CachedUds>()

    /** Был ли включён фильтр ответа при дочитывании многокадрового UDS. */
    private var udsFilterActive = false

    private data class CachedUds(val at: Long, val reading: Reading)

    override suspend fun read(definition: SignalDefinition): Reading = withContext(Dispatchers.IO) {
        if (definition.isUds) readUds(definition) else readObd(definition)
    }

    private fun readObd(definition: SignalDefinition): Reading {
        val pid = definition.pid ?: return Reading(null, ReadingState.UNSUPPORTED)
        // Диагностика могла оставить заголовок последнего найденного блока.
        // Для OBD-II нужен функциональный адрес 7DF, иначе запрос уйдёт
        // в борт компьютер, а не в двигатель.
        if (!headerPrepared) {
            elm.acceptAll()
            if (udsFilterActive) {
                elm.clearRxFilter()
                udsFilterActive = false
            }
            elm.setHeader(0x7DF)
            headerPrepared = true
        }
        val command = "01%02X".format(pid)
        val lines = try {
            elm.command(command, timeoutMs)
        } catch (_: Exception) {
            return Reading(null, ReadingState.ERROR)
        }
        if (IsoTp.isNegative(lines)) {
            val text = lines.joinToString(" ").uppercase()
            val unsupported = text.contains("NO DATA") || text.contains("?") ||
                text.contains("UNABLE") || text.contains("BUS INIT")
            return Reading(
                null,
                if (unsupported) ReadingState.UNSUPPORTED else ReadingState.ERROR,
            )
        }
        val payload = ObdPayload.mode01(lines, pid)
            ?: return Reading(null, ReadingState.UNSUPPORTED)
        val value = definition.decode?.decode(payload)
        return if (value == null) Reading(null, ReadingState.ERROR)
        else Reading(value, ReadingState.OK)
    }

    /**
     * Чтение UDS `22 <DID>`.
     *
     * Сессию не меняем и ничего не пишем: только ReadDataByIdentifier.
     * DIDs, которым нужна расширенная сессия (`10 03`), в каталог не
     * попадают — это требование режима «только мониторинг».
     */
    private fun readUds(definition: SignalDefinition): Reading {
        val txId = definition.txId ?: return Reading(null, ReadingState.UNSUPPORTED)
        val did = definition.did ?: return Reading(null, ReadingState.UNSUPPORTED)
        val decoder = definition.uds ?: return Reading(null, ReadingState.UNSUPPORTED)
        val key = (txId.toLong() shl 32) or (did.toLong() and 0xFFFFFFFFL)
        val now = System.currentTimeMillis()
        udsCache[key]?.let { cached ->
            if (now - cached.at < 250) return cached.reading
        }

        val client = udsClients.getOrPut(txId) { UdsClient(elm, txId) }
        definition.rxId?.let { client.expectResponse(it) }
        // Следующий OBD-запрос обязан снова выставить 7DF.
        headerPrepared = false
        val data = try {
            client.request("22%04X".format(did), timeoutMs = 900, attempts = 1)
        } catch (error: UdsError) {
            // Нет ответа, «вне диапазона», «нужен доступ» — для приборки это
            // одинаково «параметр не отдаётся», а не сломанный адаптер.
            return Reading(null, ReadingState.UNSUPPORTED)
        } catch (_: Exception) {
            return Reading(null, ReadingState.ERROR)
        }
        if (client.lastTruncated || data.size < 3) {
            return Reading(null, ReadingState.ERROR)
        }
        if (data[0].toInt() and 0xFF != 0x62 ||
            data[1].toInt() and 0xFF != (did shr 8) ||
            data[2].toInt() and 0xFF != (did and 0xFF)
        ) {
            return Reading(null, ReadingState.ERROR)
        }
        if (client.mfStrategy != null) udsFilterActive = true
        val payload = data.copyOfRange(3, data.size)
        val value = decoder.decode(payload) ?: return Reading(null, ReadingState.ERROR)
        val reading = Reading(value, ReadingState.OK)
        udsCache[key] = CachedUds(now, reading)
        return reading
    }
}
