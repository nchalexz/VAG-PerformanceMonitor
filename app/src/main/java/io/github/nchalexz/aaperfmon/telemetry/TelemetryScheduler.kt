package io.github.nchalexz.aaperfmon.telemetry

/**
 * Адаптивный планировщик опроса телеметрии.
 *
 * Одна очередь, один канал адаптера. У каждого параметра свой интервал,
 * приоритет, срок актуальности и история отказов. Планировщик ничего не
 * читает сам: он отвечает на вопрос «что опрашивать следующим» и помнит,
 * когда и с каким результатом это делалось. Благодаря этому его можно
 * проверять обычными JVM-тестами, без адаптера и корутин.
 *
 * Правила, которые здесь зашиты:
 *  - P0 получает шину раньше P1, P1 раньше P2;
 *  - при перегрузке адаптера P2, затем P1 замедляются, P0 — нет;
 *  - параметр, который перестал отвечать, опрашивается всё реже, но никогда
 *    не выпадает из расписания навсегда: идёт периодическая перепроверка;
 *  - «запасной» источник одного показателя не отбирает шину у основного;
 *  - ожидание P2 не бесконечно: со временем он поднимается в очереди.
 */
class TelemetryScheduler(
    private val definitions: Map<String, SignalDefinition> =
        SignalCatalog.all.associateBy { it.id },
    private val config: Config = Config(),
    private val clock: () -> Long = { 0L },
) {

    /** Числовые настройки планировщика. Вынесены сюда ради тестов. */
    data class Config(
        val minIntervalMs: Long = 100L,
        val maxIntervalMs: Long = 30_000L,
        val maxBackoffMs: Long = 15_000L,
        /** Как часто перепроверять параметр, который ни разу не ответил. */
        val unsupportedRecheckMs: Long = 30_000L,
        /** Перепроверка «горячих» параметров: водитель ждёт их на приборке. */
        val unsupportedRecheckHotMs: Long = 15_000L,
        /** Во сколько раз интервал растёт от фактической задержки ответа. */
        val latencyFactor: Double = 1.15,
        /** Задержка, после которой адаптер считается перегруженным. */
        val saturatedLatencyMs: Long = 150L,
        /** Через сколько ожидания параметр поднимается на ступень в очереди. */
        val starvationMs: Long = 3_000L,
        /** Сколько DID можно попросить одним запросом. */
        val batchLimit: Int = 4,
        /** Минимальный интервал фоновых сигналов (виджет, профиль). */
        val backgroundFloorMs: Long = 1_000L,
        /** Минимальный интервал каталога Live Data. */
        val catalogFloorMs: Long = 1_000L,
    )

    /** Кто просит параметр: экран важнее виджета, виджет важнее каталога. */
    enum class Tier { FOREGROUND, BACKGROUND, CATALOG }

    data class Demand(
        val signalId: String,
        val tier: Tier = Tier.FOREGROUND,
        /**
         * Приоритет, унаследованный от вычисляемого показателя. Наддув P0
         * требует и свои входы опрашивать как P0, иначе расчёт опаздывает.
         */
        val priority: SignalPriority? = null,
    )

    /** Состояние одного параметра в очереди: нужно диагностике и тестам. */
    data class Snapshot(
        val signalId: String,
        val priority: SignalPriority,
        val effectiveIntervalMs: Long,
        val lastAttemptMs: Long,
        val lastSuccessMs: Long,
        val consecutiveFailures: Int,
        val hasSucceeded: Boolean,
        val suspendedUntilMs: Long,
        val latencyEwmaMs: Double,
    )

    private class Entry(
        val definition: SignalDefinition,
        var tier: Tier = Tier.FOREGROUND,
        var overridePriority: SignalPriority? = null,
        var intervalFloorMs: Long = 0L,
        var lastAttemptMs: Long = 0L,
        var createdAtMs: Long = 0L,
        var attempted: Boolean = false,
        var lastSuccessMs: Long = 0L,
        var consecutiveFailures: Int = 0,
        var latencyEwmaMs: Double = 0.0,
        var suspendedUntilMs: Long = 0L,
    ) {
        val priority: SignalPriority
            get() = if (tier == Tier.CATALOG) {
                SignalPriority.P2
            } else {
                overridePriority ?: definition.priority
            }
    }

    private val entries = LinkedHashMap<String, Entry>()

    /** Скользящая задержка ответа по всей шине: ею меряется перегрузка. */
    private var busLatencyEwmaMs = 0.0

    /** Текущий набор параметров. Замена целиком, без частичных обновлений. */
    @Synchronized
    fun setDemand(demands: Collection<Demand>) {
        val previous = HashMap(entries)
        entries.clear()
        // Один и тот же сигнал может просить несколько экранов: остаётся
        // самое приоритетное требование, дублей в очереди не бывает.
        val best = LinkedHashMap<String, Demand>()
        for (demand in demands) {
            val definition = definitions[demand.signalId] ?: continue
            if (definition.isComputed) continue
            val current = best[demand.signalId]
            if (current == null ||
                demand.tier.ordinal < current.tier.ordinal ||
                (demand.tier == current.tier && demand.priority != null &&
                    (current.priority == null || demand.priority.ordinal < current.priority.ordinal))
            ) {
                best[demand.signalId] = demand
            }
        }
        for ((id, demand) in best) {
            val definition = definitions[id] ?: continue
            val tier = demand.tier
            val old = previous[id]
            entries[id] = Entry(
                definition = definition,
                tier = tier,
                overridePriority = demand.priority,
                intervalFloorMs = when (tier) {
                    Tier.FOREGROUND -> 0L
                    Tier.BACKGROUND -> config.backgroundFloorMs
                    Tier.CATALOG -> config.catalogFloorMs
                },
                lastAttemptMs = old?.lastAttemptMs ?: 0L,
                createdAtMs = old?.createdAtMs ?: clock(),
                attempted = old?.attempted ?: false,
                lastSuccessMs = old?.lastSuccessMs ?: 0L,
                consecutiveFailures = old?.consecutiveFailures ?: 0,
                latencyEwmaMs = old?.latencyEwmaMs ?: 0.0,
                suspendedUntilMs = old?.suspendedUntilMs ?: 0L,
            )
        }
    }

    /** Момент, когда параметр снова можно опрашивать. */
    @Synchronized
    fun nextAttemptAt(signalId: String): Long {
        val entry = entries[signalId] ?: return Long.MAX_VALUE
        return nextAttemptAt(entry)
    }

    /**
     * Следующий параметр и, если это UDS, соседи по тому же блоку.
     *
     * Пакет собирается только из DID одного ECU и только из уже созревших:
     * планировщик не имеет права задерживать P0 ради красивого пакета.
     */
    @Synchronized
    fun nextDueBatch(now: Long = clock(), limit: Int = config.batchLimit): List<SignalDefinition> {
        val due = entries.values.filter { isDue(it, now) }
        if (due.isEmpty()) return emptyList()
        val leader = due.minWithOrNull(compareBy({ rank(it, now) }, { nextAttemptAt(it) }, { it.definition.id }))
            ?: return emptyList()
        val batch = mutableListOf(leader.definition)
        if (leader.definition.isUds && limit > 1) {
            val group = due.asSequence()
                .filter { it !== leader }
                .filter { candidate ->
                    val definition = candidate.definition
                    definition.isUds &&
                        definition.txId == leader.definition.txId &&
                        definition.did != leader.definition.did
                }
                .sortedWith(compareBy({ rank(it, now) }, { nextAttemptAt(it) }, { it.definition.id }))
                .take(limit - 1)
                .toList()
            group.forEach { batch += it.definition }
        }
        return batch
    }

    /** Следующий параметр без учёта пакета: удобно для тестов и журнала. */
    @Synchronized
    fun nextDue(now: Long = clock()): SignalDefinition? = nextDueBatch(now, limit = 1).firstOrNull()

    /** Через сколько миллисекунд в очереди что-то появится. */
    @Synchronized
    fun nextDueInMs(now: Long = clock()): Long {
        if (entries.isEmpty()) return config.maxIdleMs
        var best = config.maxIdleMs
        for (entry in entries.values) {
            val wait = (nextAttemptAt(entry) - now).coerceAtLeast(0L)
            if (wait < best) best = wait
        }
        return best
    }

    /** Сколько параметров уже просрочено: это глубина очереди для журнала. */
    @Synchronized
    fun queueDepth(now: Long = clock()): Int = entries.values.count { isDue(it, now) }

    @Synchronized
    fun contains(signalId: String): Boolean = entries.containsKey(signalId)

    /**
     * Записывает результат опроса и назначает следующий срок.
     *
     * Успех сбрасывает историю отказов и включает адаптивный интервал.
     * Таймаут даёт экспоненциальную паузу, отрицательный ответ UDS —
     * длинную паузу с обязательной перепроверкой.
     */
    @Synchronized
    fun record(
        signalId: String,
        state: ReadingState,
        latencyMs: Long,
        now: Long = clock(),
        failure: ReadingFailure = ReadingFailure.NONE,
    ) {
        val entry = entries[signalId] ?: return
        entry.attempted = true
        entry.lastAttemptMs = now
        if (latencyMs > 0) {
            // Задержка отдельного параметра сглаживается сильнее, чем общая:
            // одиночный медленный ответ не должен надолго разрежать опрос.
            entry.latencyEwmaMs = if (entry.latencyEwmaMs <= 0.0) {
                latencyMs.toDouble()
            } else {
                entry.latencyEwmaMs * 0.8 + latencyMs * 0.2
            }
            busLatencyEwmaMs = if (busLatencyEwmaMs <= 0.0) {
                latencyMs.toDouble()
            } else {
                busLatencyEwmaMs * 0.7 + latencyMs * 0.3
            }
        }
        when {
            state == ReadingState.OK -> {
                entry.consecutiveFailures = 0
                entry.lastSuccessMs = now
                entry.suspendedUntilMs = 0L
            }
            // Источник ответил «такого параметра нет». Перепроверяем редко:
            // блок мог проснуться, но долбить его каждые 100 мс бессмысленно.
            failure == ReadingFailure.NEGATIVE || state == ReadingState.UNSUPPORTED -> {
                entry.consecutiveFailures++
                entry.suspendedUntilMs = now + recheckMs(entry)
            }
            // Таймаут или сбой транспорта: короткая пауза, растущая с числом
            // отказов подряд, но без бесконечного ожидания.
            else -> {
                entry.consecutiveFailures++
                // Первая пауза — секунда, дальше удвоение до предела.
                // Так одиночный таймаут не выключает параметр, а серия
                // отказов не превращается в шквал повторных запросов.
                val backoff = (1_000L shl minOf(entry.consecutiveFailures - 1, 4))
                    .coerceAtMost(config.maxBackoffMs)
                entry.suspendedUntilMs = now + backoff
            }
        }
    }

    /** Отметка «сейчас опрошен», если чтение не дошло до [record]. */
    @Synchronized
    fun markAttempt(signalId: String, now: Long = clock()) {
        entries[signalId]?.let {
            it.attempted = true
            it.lastAttemptMs = now
        }
    }

    @Synchronized
    fun snapshot(signalId: String): Snapshot? {
        val entry = entries[signalId] ?: return null
        return Snapshot(
            signalId = entry.definition.id,
            priority = entry.priority,
            effectiveIntervalMs = effectiveIntervalMs(entry),
            lastAttemptMs = entry.lastAttemptMs,
            lastSuccessMs = entry.lastSuccessMs,
            consecutiveFailures = entry.consecutiveFailures,
            hasSucceeded = entry.lastSuccessMs > 0,
            suspendedUntilMs = entry.suspendedUntilMs,
            latencyEwmaMs = entry.latencyEwmaMs,
        )
    }

    @Synchronized
    fun snapshots(): List<Snapshot> {
        val now = clock()
        return entries.values.map { entry ->
            Snapshot(
                signalId = entry.definition.id,
                priority = entry.priority,
                effectiveIntervalMs = effectiveIntervalMs(entry),
                lastAttemptMs = entry.lastAttemptMs,
                lastSuccessMs = entry.lastSuccessMs,
                consecutiveFailures = entry.consecutiveFailures,
                hasSucceeded = entry.lastSuccessMs > 0,
                suspendedUntilMs = entry.suspendedUntilMs,
                latencyEwmaMs = entry.latencyEwmaMs,
            )
        }
    }

    /** Сколько параметров сейчас в очереди. */
    @Synchronized
    fun size(): Int = entries.size

    // -- внутреннее ---------------------------------------------------------

    private fun isDue(entry: Entry, now: Long): Boolean = nextAttemptAt(entry) <= now

    private fun nextAttemptAt(entry: Entry): Long {
        val interval = if (entry.attempted) effectiveIntervalMs(entry) else 0L
        val own = if (entry.attempted) entry.lastAttemptMs + interval else 0L
        return maxOf(own, entry.suspendedUntilMs)
    }

    /**
     * Ступень в очереди. Чем меньше, тем раньше опрос.
     *
     * Параметр, который давно ждёт, поднимается на одну-две ступени: так P2
     * не откладывается бесконечно, пока P0 занимает каждый слот шины.
     */
    private fun rank(entry: Entry, now: Long): Int {
        var rank = entry.priority.ordinal
        // Параметр, который ещё ни разу не опрашивался, ждёт с момента
        // появления в очереди: иначе при насыщенной шине P2 не получил бы
        // ни одного шанса, пока P0 постоянно просрочен.
        val since = if (entry.attempted) entry.lastAttemptMs else entry.createdAtMs
        val waited = now - since
        if (waited >= config.starvationMs) rank = (rank - 1).coerceAtLeast(0)
        if (waited >= config.starvationMs * 2) rank = (rank - 2).coerceAtLeast(0)
        return rank
    }

    private fun baseIntervalMs(entry: Entry): Long =
        (1000.0 / entry.definition.pollHz.coerceIn(0.05, 20.0))
            .toLong()
            .coerceAtLeast(config.minIntervalMs)

    /**
     * Интервал с учётом фактической задержки и перегрузки адаптера.
     *
     * Если адаптер отвечает медленнее, чем идёт опрос, интервал растёт:
     * иначе очередь растёт быстрее, чем шина успевает её разбирать, и
     * значения приходят всё более старыми. При перегрузке P2 и P1
     * замедляются сильнее, P0 сохраняет свой темп.
     */
    private fun effectiveIntervalMs(entry: Entry): Long {
        var interval = maxOf(baseIntervalMs(entry), entry.intervalFloorMs)
        if (entry.latencyEwmaMs > 0.0) {
            interval = maxOf(
                interval,
                Math.ceil(entry.latencyEwmaMs * config.latencyFactor).toLong(),
            )
        }
        if (entry.priority != SignalPriority.P0) {
            interval = (interval * saturationFactor()).toLong()
        }
        return interval.coerceIn(config.minIntervalMs, config.maxIntervalMs)
    }

    private fun saturationFactor(): Double {
        if (busLatencyEwmaMs <= config.saturatedLatencyMs) return 1.0
        val over = (busLatencyEwmaMs - config.saturatedLatencyMs) / config.saturatedLatencyMs
        return (1.0 + over).coerceAtMost(3.0)
    }

    private fun recheckMs(entry: Entry): Long =
        if (entry.priority == SignalPriority.P2) {
            config.unsupportedRecheckMs
        } else {
            config.unsupportedRecheckHotMs
        }

    /** Предел простоя очереди: движок просыпается, даже если ничего не созрело. */
    private val Config.maxIdleMs: Long get() = 250L
}
