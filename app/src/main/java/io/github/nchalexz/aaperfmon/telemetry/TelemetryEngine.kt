package io.github.nchalexz.aaperfmon.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Движок телеметрии.
 *
 * Отвечает за расписание опроса, приоритеты, кэш последних значений и
 * вычисляемые показатели. Намеренно не знает ни об Android, ни о Compose,
 * ни о конкретном экране: всё общение — через StateFlow.
 *
 * Два класса подписок:
 *  - активный дашборд — опрашивается с частотой из описания сигнала;
 *  - каталог Live Data — опрашивается по одному сигналу в свободное время,
 *    чтобы не забивать шину и не мешать приборке.
 *
 * Запросы никогда не выполняются параллельно: у адаптера один канал, и
 * дешёвые клоны не переносят два запроса подряд. Это же правило было
 * выяснено в базовом VAG Diag.
 */
class TelemetryEngine(
    private val source: SignalSource,
    private val scope: CoroutineScope,
    private val catalog: List<SignalDefinition> = SignalCatalog.all,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val index: Map<String, SignalDefinition> = catalog.associateBy { it.id }
    private val computedSignals: List<SignalDefinition> = catalog.filter { it.isComputed }

    private val _values = MutableStateFlow<Map<String, SignalValue>>(emptyMap())
    val values: StateFlow<Map<String, SignalValue>> = _values.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile
    private var dashboardIds: List<String> = emptyList()

    @Volatile
    private var catalogIds: List<String> = emptyList()

    @Volatile
    private var catalogEnabled: Boolean = false

    private var job: Job? = null
    private var catalogCursor = 0

    // Эти карты трогает только корутина опроса, поэтому блокировки не нужны.
    private val lastRead = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, Int>()

    /** Какой источник данных сейчас используется: «ДЕМО» или «ELM327». */
    val sourceLabel: String get() = source.label

    fun setDashboard(ids: Collection<String>) {
        // Вычисляемые показатели не опрашиваются, но их входы обязаны
        // опрашиваться. Иначе наддув, момент и мощность молча висят
        // без данных, хотя сами сигналы на экране выбраны.
        val expanded = LinkedHashSet<String>()
        fun visit(id: String) {
            if (!expanded.add(id)) return
            index[id]?.computed?.inputs?.forEach(::visit)
        }
        ids.forEach(::visit)
        dashboardIds = expanded.filter { index[it]?.isComputed == false }
    }

    fun setCatalog(ids: Collection<String>) {
        catalogIds = ids.distinct().filter { index[it]?.isComputed == false }
    }

    fun setCatalogEnabled(enabled: Boolean) {
        catalogEnabled = enabled
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun close() {
        stop()
        source.close()
    }

    private suspend fun runLoop() {
        _running.value = true
        try {
            while (currentCoroutineContext().isActive) {
                val now = clock()
                val due = dashboardIds.asSequence()
                    .mapNotNull { index[it] }
                    .filter { isDue(it, now) }
                    .minByOrNull { lastRead[it.id] ?: 0L }

                val next = due ?: nextCatalogSignal(now)
                if (next == null) {
                    delay(25)
                    continue
                }
                poll(next)
                recompute()
            }
        } finally {
            _running.value = false
        }
    }

    private fun isDue(definition: SignalDefinition, now: Long): Boolean {
        val last = lastRead[definition.id] ?: return true
        return now - last >= effectiveInterval(definition)
    }

    /**
     * Каталог опрашивается по кругу, ровно один сигнал за проход. Так
     * активная приборка всегда получает шину первой, а список параметров
     * обновляется, пока она молчит.
     */
    private fun nextCatalogSignal(now: Long): SignalDefinition? {
        if (!catalogEnabled) return null
        val list = catalogIds.mapNotNull { index[it] }.filter { !it.isComputed }
        if (list.isEmpty()) return null
        repeat(list.size) {
            val candidate = list[catalogCursor % list.size]
            catalogCursor = (catalogCursor + 1) % list.size
            if (isDue(candidate, now)) return candidate
        }
        return null
    }

    private fun effectiveInterval(definition: SignalDefinition): Long {
        val misses = failures[definition.id] ?: 0
        if (misses == 0) return definition.intervalMs
        // После отказа не долбим блок каждые 100 мс: сначала ждём секунду,
        // потом всё дольше, но не больше 15 секунд.
        val backoff = definition.intervalMs * (1L shl minOf(misses, 4))
        return backoff.coerceIn(1_000L, 15_000L)
    }

    private suspend fun poll(definition: SignalDefinition) {
        lastRead[definition.id] = clock()
        val reading = try {
            source.read(definition)
        } catch (_: Exception) {
            Reading(null, ReadingState.ERROR)
        }
        val now = clock()
        if (reading.state == ReadingState.OK && reading.value != null) {
            failures[definition.id] = 0
        } else {
            failures[definition.id] = (failures[definition.id] ?: 0) + 1
        }
        val value = SignalValue(
            value = reading.value,
            timestampMs = now,
            state = reading.state,
            sourceLabel = source.label,
        )
        _values.update { it + (definition.id to value) }
    }

    /**
     * Пересчитывает производные показатели по последним значениям.
     * Если входных данных нет или они устарели, показатель становится
     * «нет данных», а не нулём.
     */
    private fun recompute() {
        val now = clock()
        val snapshot = _values.value
        val updates = mutableMapOf<String, SignalValue>()

        for (definition in computedSignals) {
            val spec = definition.computed ?: continue
            val resolved = spec.inputs.map { id ->
                val inputDefinition = index[id]
                val inputValue = snapshot[id]
                Triple(id, inputDefinition, inputValue)
            }
            val usable = resolved.all { (_, inputDefinition, inputValue) ->
                inputDefinition != null && inputValue != null &&
                    inputValue.state == ReadingState.OK && inputValue.value != null &&
                    !inputValue.isStale(now, inputDefinition)
            }
            if (!usable) {
                val previous = snapshot[definition.id]
                if (previous == null || previous.value != null) {
                    updates[definition.id] = SignalValue(
                        value = null,
                        timestampMs = now,
                        state = ReadingState.UNSUPPORTED,
                        sourceLabel = "расчёт",
                    )
                }
                continue
            }
            val a = snapshot[spec.inputs[0]]!!.value!!
            val b = snapshot[spec.inputs[1]]!!.value!!
            val value = when (spec.kind) {
                ComputedKind.TORQUE_NM -> a / 100.0 * b
                ComputedKind.POWER_KW -> a * b / 9549.0
                ComputedKind.BOOST_KPA -> a - b
                // Ниже 3 км/ч деление на скорость даёт мусор: это «нет
                // данных», а не бесконечный расход и не ноль.
                ComputedKind.FUEL_L100 -> if (b < 3.0) null else a / b * 100.0
            }
            if (value == null) {
                updates[definition.id] = SignalValue(
                    value = null,
                    timestampMs = now,
                    state = ReadingState.UNSUPPORTED,
                    sourceLabel = "расчёт",
                )
                continue
            }
            updates[definition.id] = SignalValue(
                value = value,
                timestampMs = now,
                state = ReadingState.OK,
                sourceLabel = "расчёт",
            )
        }

        if (updates.isNotEmpty()) {
            _values.update { it + updates }
        }
    }
}
