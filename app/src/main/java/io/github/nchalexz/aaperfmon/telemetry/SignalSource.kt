package io.github.nchalexz.aaperfmon.telemetry

import io.github.nchalexz.aaperfmon.core.Elm327
import io.github.nchalexz.aaperfmon.core.IsoTp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
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

    override suspend fun read(definition: SignalDefinition): Reading {
        val t = clock() / 1000.0
        val value = valueFor(definition.id, t) ?: return Reading(null, ReadingState.UNSUPPORTED)
        return Reading(value, ReadingState.OK)
    }

    private fun valueFor(id: String, t: Double): Double? {
        val speed = (62.0 + 52.0 * sin(t * 0.18)).coerceIn(0.0, 160.0)
        val rpm = (800.0 + speed * 27.0 + 120.0 * sin(t * 1.7)).coerceAtLeast(650.0)
        val gear = when {
            speed < 1 -> 0
            speed < 15 -> 1
            speed < 35 -> 2
            speed < 55 -> 3
            speed < 75 -> 4
            speed < 95 -> 5
            speed < 115 -> 6
            else -> 7
        }
        val map = 100.0 + if (rpm > 1400) 25.0 + 55.0 * abs(sin(t * 0.9)) else -20.0
        val baro = 100.5
        val boost = map - baro
        val torquePct = (25.0 + 55.0 * abs(sin(t * 0.35))).coerceIn(-10.0, 100.0)
        val torqueRef = 380.0
        val torqueNm = torquePct / 100.0 * torqueRef

        return when (id) {
            SignalCatalog.Ids.SPEED -> speed
            SignalCatalog.Ids.GEAR -> gear.toDouble()
            SignalCatalog.Ids.RPM -> rpm
            SignalCatalog.Ids.LOAD -> (18.0 + 55.0 * abs(sin(t * 0.3))).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.ABS_LOAD -> (22.0 + 60.0 * abs(sin(t * 0.29))).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.COOLANT -> 88.0 + 3.0 * sin(t * 0.05)
            SignalCatalog.Ids.OIL_TEMP -> 94.0 + 4.0 * sin(t * 0.04)
            SignalCatalog.Ids.INTAKE_AIR -> 31.0 + 4.0 * sin(t * 0.11)
            SignalCatalog.Ids.AMBIENT -> 18.0 + 1.5 * sin(t * 0.01)
            SignalCatalog.Ids.MAP -> map
            SignalCatalog.Ids.BARO -> baro
            SignalCatalog.Ids.BOOST -> boost
            SignalCatalog.Ids.MAF -> (4.0 + rpm / 100.0 * 1.8).coerceIn(2.0, 160.0)
            SignalCatalog.Ids.THROTTLE -> (12.0 + 70.0 * (map - 70.0) / 110.0).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.REL_THROTTLE -> (10.0 + 55.0 * abs(sin(t * 0.4))).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.ABS_THROTTLE_B -> (10.0 + 54.0 * abs(sin(t * 0.4))).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.ABS_THROTTLE_C -> (9.0 + 52.0 * abs(sin(t * 0.4))).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.CMD_THROTTLE -> (12.0 + 60.0 * abs(sin(t * 0.4))).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.PEDAL -> (15.0 + 65.0 * abs(sin(t * 0.4))).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.PEDAL_E -> (14.0 + 64.0 * abs(sin(t * 0.4))).coerceIn(0.0, 100.0)
            SignalCatalog.Ids.TIMING -> 8.0 + 10.0 * sin(t * 0.5)
            SignalCatalog.Ids.RUN_TIME -> t % 3600.0
            SignalCatalog.Ids.TORQUE_PCT -> torquePct
            SignalCatalog.Ids.TORQUE_DEMAND_PCT -> (torquePct + 10.0).coerceAtMost(100.0)
            SignalCatalog.Ids.TORQUE_REF -> torqueRef
            SignalCatalog.Ids.TORQUE_NM -> torqueNm
            SignalCatalog.Ids.POWER_KW -> torqueNm * rpm / 9549.0
            SignalCatalog.Ids.FUEL_LEVEL -> 57.0 - (t * 0.01) % 40.0
            SignalCatalog.Ids.FUEL_RATE -> (4.0 + 9.0 * abs(sin(t * 0.35))).coerceIn(2.0, 25.0)
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
            SignalCatalog.Ids.VOLTAGE -> 13.9 + 0.15 * sin(t * 0.4)
            SignalCatalog.Ids.ETHANOL -> 5.0
            SignalCatalog.Ids.DISTANCE_MIL -> 0.0
            SignalCatalog.Ids.DISTANCE_CLEARED -> 128.0
            SignalCatalog.Ids.WARMUPS -> 12.0
            else -> null
        }
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

    override suspend fun read(definition: SignalDefinition): Reading = withContext(Dispatchers.IO) {
        val pid = definition.pid ?: return@withContext Reading(null, ReadingState.UNSUPPORTED)
        // Диагностика могла оставить заголовок последнего найденного блока.
        // Для OBD-II нужен функциональный адрес 7DF, иначе запрос уйдёт
        // в борт компьютер, а не в двигатель.
        if (!headerPrepared) {
            elm.acceptAll()
            elm.setHeader(0x7DF)
            headerPrepared = true
        }
        val command = "01%02X".format(pid)
        val lines = try {
            elm.command(command, timeoutMs)
        } catch (_: Exception) {
            return@withContext Reading(null, ReadingState.ERROR)
        }
        if (IsoTp.isNegative(lines)) {
            val text = lines.joinToString(" ").uppercase()
            val unsupported = text.contains("NO DATA") || text.contains("?") ||
                text.contains("UNABLE") || text.contains("BUS INIT")
            return@withContext Reading(
                null,
                if (unsupported) ReadingState.UNSUPPORTED else ReadingState.ERROR,
            )
        }
        val payload = ObdPayload.mode01(lines, pid)
            ?: return@withContext Reading(null, ReadingState.UNSUPPORTED)
        val value = definition.decode?.decode(payload)
        if (value == null) Reading(null, ReadingState.ERROR) else Reading(value, ReadingState.OK)
    }
}
