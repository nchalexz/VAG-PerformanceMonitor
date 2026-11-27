package io.github.nchalexz.aaperfmon.telemetry

import io.github.nchalexz.aaperfmon.core.AdapterBus
import kotlinx.coroutines.CancellationException
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
 * Расписание живёт в [TelemetryScheduler]: у каждого параметра свой
 * интервал, приоритет и история отказов. Здесь — цикл «взять следующий
 * созревший параметр, опросить, записать результат, пересчитать производные»,
 * плюс учёт метрик [TelemetryMetrics]. Все запросы строго последовательны:
 * у адаптера один канал, и два запроса одновременно невозможны.
 *
 * Две ступени подписки: экран приоритетнее фона. Фон (виджет, профиль)
 * опрашивается тогда, когда экран молчит, и не чаще раза в секунду:
 * частота обновления интерфейса не должна определять частоту запросов.
 * Каталог Live Data опрашивается по одному сигналу в свободное время.
 */
class TelemetryEngine(
    private val source: SignalSource,
    private val scope: CoroutineScope,
    private val catalog: List<SignalDefinition> = SignalCatalog.all,
    private val clock: () -> Long = MonotonicClock::now,
    val metrics: TelemetryMetrics = TelemetryMetrics(clock),
) {

    private val index: Map<String, SignalDefinition> = catalog.associateBy { it.id }
    private val computedSignals: List<SignalDefinition> = catalog.filter { it.isComputed }

    private val scheduler = TelemetryScheduler(index, clock = clock)

    private val _values = MutableStateFlow<Map<String, SignalValue>>(emptyMap())
    val values: StateFlow<Map<String, SignalValue>> = _values.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile
    private var foregroundIds: List<String> = emptyList()

    /** Фон: сигналы профиля для виджета. Опрашиваются, только когда экран молчит. */
    @Volatile
    private var backgroundIds: List<String> = emptyList()

    @Volatile
    private var catalogIds: List<String> = emptyList()

    @Volatile
    private var catalogEnabled: Boolean = false

    /**
     * Запасные источники: `запасной → основной`. Запасной опрашивается,
     * только пока основной не отдал свежее значение. Так один показатель
     * не читается из двух мест одновременно.
     */
    @Volatile
    private var fallbacks: Map<String, String> = emptyMap()

    private var job: Job? = null
    private var lastEcu: Int? = null
    private var lastFreshnessTick = 0L

    /** Какой источник данных сейчас используется: «ДЕМО» или «ELM327». */
    val sourceLabel: String get() = source.label

    fun setDashboard(ids: Collection<String>) {
        // Вычисляемые показатели не опрашиваются, но их входы обязаны
        // опрашиваться. Иначе наддув, момент и мощность молча висят
        // без данных, хотя сами сигналы на экране выбраны.
        foregroundIds = expand(ids).map { it.first }
        refreshDemand()
    }

    /**
     * Фоновые сигналы (профиль для виджета). Они не должны отбирать шину
     * у приборки: пока экран подписан на сигнал, фон идёт второй ступенью.
     */
    fun setBackground(ids: Collection<String>) {
        backgroundIds = expand(ids).map { it.first }
        refreshDemand()
    }

    fun setCatalog(ids: Collection<String>) {
        catalogIds = ids.distinct().filter { index[it]?.isComputed == false }
        refreshDemand()
    }

    fun setCatalogEnabled(enabled: Boolean) {
        catalogEnabled = enabled
        refreshDemand()
    }

    fun setFallbacks(map: Map<String, String>) {
        fallbacks = map
        refreshDemand()
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

    /** Состояние очереди на текущий момент: для диагностики и тестов. */
    fun schedule(): List<TelemetryScheduler.Snapshot> = scheduler.snapshots()

    private suspend fun runLoop() {
        _running.value = true
        try {
            while (currentCoroutineContext().isActive) {
                val now = clock()
                if (now - lastFreshnessTick >= FRESHNESS_TICK_MS) {
                    trackFreshness(now)
                    lastFreshnessTick = now
                }
                val batch = scheduler.nextDueBatch(now)
                if (batch.isEmpty()) {
                    delay(scheduler.nextDueInMs(now).coerceIn(1L, MAX_IDLE_MS))
                    continue
                }
                poll(batch)
                recompute()
                // Статусы основных источников могли измениться: от этого
                // зависит, пора ли опрашивать запасной.
                refreshDemand()
            }
        } finally {
            _running.value = false
        }
    }

    /**
     * Один такт опроса. Пакет собирается планировщиком: несколько DID
     * одного блока могут уйти одним запросом, если блок это умеет.
     */
    private suspend fun poll(batch: List<SignalDefinition>) {
        val startedAt = clock()
        val readings = try {
            // Одна транзакция шины: диагностика не вклинится между
            // заголовком и запросом этого пакета.
            AdapterBus.transaction { source.readBatch(batch) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            batch.associate {
                it.id to Reading(null, ReadingState.ERROR, ReadingFailure.TRANSPORT)
            }
        }
        val finishedAt = clock()
        val latency = (finishedAt - startedAt).coerceAtLeast(0L)

        // Переключение ECU — это отдельная команда ATSH и потерянные
        // миллисекунды, поэтому их число считаем явно.
        val ecu = batch.firstOrNull()?.txId ?: 0
        if (lastEcu != null && lastEcu != ecu) metrics.onEcuSwitch()
        lastEcu = ecu

        val updates = mutableMapOf<String, SignalValue>()
        val previousValues = _values.value
        for (definition in batch) {
            val reading = readings[definition.id]
                ?: Reading(null, ReadingState.ERROR, ReadingFailure.TRANSPORT)
            scheduler.record(
                signalId = definition.id,
                state = reading.state,
                latencyMs = latency,
                now = finishedAt,
                failure = reading.failure,
            )
            metrics.onPoll(
                signalId = definition.id,
                state = reading.state,
                failure = reading.failure,
                latencyMs = latency,
                batchSize = batch.size,
                now = finishedAt,
            )
            val previous = previousValues[definition.id]
            val ok = reading.state == ReadingState.OK && reading.value != null
            updates[definition.id] = if (ok) {
                SignalValue(
                    value = reading.value,
                    timestampMs = finishedAt,
                    state = ReadingState.OK,
                    sourceLabel = source.label,
                    lastAttemptMs = finishedAt,
                    hasEverBeenRead = true,
                    consecutiveFailures = 0,
                )
            } else {
                // Неудачная попытка не стирает последнее число: оно живёт
                // до истечения срока актуальности. Время успеха не двигаем.
                SignalValue(
                    value = previous?.value,
                    timestampMs = previous?.timestampMs ?: 0L,
                    state = reading.state,
                    sourceLabel = previous?.sourceLabel ?: source.label,
                    lastAttemptMs = finishedAt,
                    hasEverBeenRead = previous?.hasEverBeenRead ?: false,
                    consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                )
            }
        }
        _values.update { it + updates }
        metrics.onQueueDepth(scheduler.queueDepth(clock()))
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
            val usable = spec.inputs.all { id ->
                val inputDefinition = index[id]
                val inputValue = snapshot[id]
                inputDefinition != null && inputValue != null &&
                    inputValue.value != null &&
                    inputValue.statusAt(now, inputDefinition) == SignalStatus.VALID
            }
            val previous = snapshot[definition.id]
            if (!usable) {
                // Последнее посчитанное число сохраняется: если входы
                // устарели, на экране остаётся серое значение, а не прочерк.
                // Прочерк — только если расчёт не давал числа ни разу.
                val lastValue = previous?.value
                if (previous == null || (lastValue == null &&
                        previous.state == ReadingState.UNSUPPORTED)
                ) {
                    if (previous != null) continue
                }
                updates[definition.id] = SignalValue(
                    value = lastValue,
                    timestampMs = previous?.timestampMs ?: 0L,
                    state = ReadingState.UNSUPPORTED,
                    sourceLabel = "расчёт",
                    lastAttemptMs = now,
                    hasEverBeenRead = previous?.hasEverBeenRead ?: false,
                    consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                )
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
                // Например, расход на стоянке: новое число не определено,
                // но последнее известное остаётся серым, пока не устареет.
                val lastValue = previous?.value
                if (previous != null && lastValue == null &&
                    previous.state == ReadingState.UNSUPPORTED
                ) {
                    continue
                }
                updates[definition.id] = SignalValue(
                    value = lastValue,
                    timestampMs = previous?.timestampMs ?: 0L,
                    state = ReadingState.UNSUPPORTED,
                    sourceLabel = "расчёт",
                    lastAttemptMs = now,
                    hasEverBeenRead = previous?.hasEverBeenRead ?: false,
                    consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                )
                continue
            }
            updates[definition.id] = SignalValue(
                value = value,
                timestampMs = now,
                state = ReadingState.OK,
                sourceLabel = "расчёт",
                lastAttemptMs = now,
                hasEverBeenRead = true,
                consecutiveFailures = 0,
            )
        }

        if (updates.isNotEmpty()) {
            _values.update { it + updates }
        }
    }

    /**
     * Актуальность проверяется независимо от поступления ответов: если
     * адаптер пропал, значение обязано стать «—» само, без нового запроса.
     */
    private fun trackFreshness(now: Long) {
        for ((id, value) in _values.value) {
            val definition = index[id] ?: continue
            metrics.onFreshness(
                signalId = id,
                status = value.statusAt(now, definition),
                ageMs = if (value.hasEverBeenRead) now - value.timestampMs else 0L,
            )
        }
        metrics.onQueueDepth(scheduler.queueDepth(now))
    }

    /**
     * Пересобирает запросы к планировщику.
     *
     * Приоритет вычисляемого показателя наследуется его входами: без этого
     * расчётный наддув P0 опирался бы на P2-параметры и опаздывал.
     */
    private fun refreshDemand() {
        val demands = mutableListOf<TelemetryScheduler.Demand>()
        expand(foregroundIds).forEach { (id, priority) ->
            demands += TelemetryScheduler.Demand(id, TelemetryScheduler.Tier.FOREGROUND, priority)
        }
        expand(backgroundIds).forEach { (id, priority) ->
            demands += TelemetryScheduler.Demand(id, TelemetryScheduler.Tier.BACKGROUND, priority)
        }
        if (catalogEnabled) {
            catalogIds.forEach {
                demands += TelemetryScheduler.Demand(it, TelemetryScheduler.Tier.CATALOG)
            }
        }
        scheduler.setDemand(suppressFallbacks(demands))
    }

    /**
     * Убирает запасной источник из очереди, пока основной отдаёт свежее
     * значение. Ошибка или устаревание основного возвращает запасной.
     */
    private fun suppressFallbacks(
        demands: List<TelemetryScheduler.Demand>,
    ): List<TelemetryScheduler.Demand> {
        if (fallbacks.isEmpty()) return demands
        val now = clock()
        return demands.filter { demand ->
            val primaryId = fallbacks[demand.signalId] ?: return@filter true
            // Явная подписка экрана на сигнал сильнее правила запасного.
            if (demand.tier == TelemetryScheduler.Tier.FOREGROUND) return@filter true
            val primaryDefinition = index[primaryId] ?: return@filter true
            val primaryValue = _values.value[primaryId] ?: return@filter true
            primaryValue.statusAt(now, primaryDefinition) != SignalStatus.VALID
        }
    }

    /**
     * Раскрывает вычисляемые сигналы до их входов и возвращает пары
     * «сигнал — унаследованный приоритет».
     */
    private fun expand(ids: Collection<String>): List<Pair<String, SignalPriority>> {
        val result = LinkedHashMap<String, SignalPriority>()
        fun visit(id: String, inherited: SignalPriority?) {
            val definition = index[id] ?: return
            // Живой источник не умеет отдавать virtual-сигналы (передачу):
            // в очередь они не попадают, иначе каждый опрос — впустую.
            if (definition.protocol == Protocol.VIRTUAL && !source.providesVirtualSignals) return
            val own = definition.priority
            val priority = if (inherited == null || inherited.ordinal < own.ordinal) inherited ?: own else own
            if (definition.isComputed) {
                definition.computed?.inputs?.forEach { visit(it, priority) }
                return
            }
            val current = result[id]
            result[id] = if (current == null || priority.ordinal < current.ordinal) priority else current
        }
        ids.forEach { visit(it, null) }
        return result.map { it.key to it.value }
    }

    private companion object {
        /** Как часто пересчитывать актуальность без новых ответов. */
        const val FRESHNESS_TICK_MS = 250L

        /** Предел простоя цикла: движок просыпается и проверяет очередь. */
        const val MAX_IDLE_MS = 250L
    }
}
