package io.github.nchalexz.aaperfmon.telemetry

import kotlin.math.max

/**
 * Технические метрики опроса телеметрии.
 *
 * Это не «красивые графики»: по этим числам видно, достигнута ли целевая
 * частота на конкретном адаптере и автомобиле, и где именно теряется
 * время — в таймаутах, в отрицательных ответах блока или в транспорте.
 *
 * Всё считается на лету и хранится в памяти: запись на диск делается
 * только при экспорте журнала. Блокировки простые и synchronized: запросов
 * не больше нескольких десятков в секунду.
 */
class TelemetryMetrics(
    private val clock: () -> Long = { 0L },
    private val journalLimit: Int = 2_000,
) {

    /** Одна запись журнала: что, когда, с каким результатом. */
    data class Event(
        val atMs: Long,
        val signalId: String,
        val state: ReadingState,
        val failure: ReadingFailure,
        val latencyMs: Long,
        val batchSize: Int,
    )

    /** Метрики одного параметра. */
    data class SignalStats(
        val signalId: String,
        val attempts: Long,
        val successes: Long,
        val timeouts: Long,
        val negativeResponses: Long,
        val transportErrors: Long,
        val averageLatencyMs: Double,
        val p95LatencyMs: Long,
        /** Фактическая частота успешных обновлений, Гц. */
        val updatesPerSecond: Double,
        /** Возраст последнего корректного значения, мс. */
        val ageMs: Long,
        val staleTransitions: Long,
        val status: SignalStatus,
    )

    /** Сводка по всем параметрам. */
    data class Snapshot(
        val uptimeMs: Long,
        val requests: Long,
        val successes: Long,
        val timeouts: Long,
        val negativeResponses: Long,
        val transportErrors: Long,
        val requestRateHz: Double,
        val averageLatencyMs: Double,
        val p95LatencyMs: Long,
        val successPercent: Double,
        val ecuSwitches: Long,
        val queueDepth: Int,
        val staleTransitions: Long,
        val signals: List<SignalStats>,
    )

    private class Counter {
        var attempts = 0L
        var successes = 0L
        var timeouts = 0L
        var negatives = 0L
        var transportErrors = 0L
        var totalLatency = 0L
        var lastLatency = 0L
        var lastSuccessMs = 0L
        var previousSuccessMs = 0L
        var rateEwmaMs = 0.0
        var staleTransitions = 0L
        var status = SignalStatus.NEVER_READ
        var ageMs = 0L
        val latencySamples = ArrayDeque<Long>()
    }

    private val startedAt = clock()
    private val counters = LinkedHashMap<String, Counter>()
    private val requestTimes = ArrayDeque<Long>()
    private var globalLatency = ArrayDeque<Long>()

    private var requests = 0L
    private var successes = 0L
    private var timeouts = 0L
    private var negatives = 0L
    private var transportErrors = 0L
    private var ecuSwitches = 0L
    private var staleTransitions = 0L
    private var queueDepth = 0

    private val journal = ArrayDeque<Event>()

    /** Результат одного опроса. Вызывается движком после каждого чтения. */
    @Synchronized
    fun onPoll(
        signalId: String,
        state: ReadingState,
        failure: ReadingFailure,
        latencyMs: Long,
        batchSize: Int = 1,
        now: Long = clock(),
    ) {
        requests++
        requestTimes.addLast(now)
        while (requestTimes.size > 64) requestTimes.removeFirst()
        if (latencyMs > 0) {
            globalLatency.addLast(latencyMs)
            while (globalLatency.size > 128) globalLatency.removeFirst()
        }

        val counter = counters.getOrPut(signalId) { Counter() }
        counter.attempts++
        counter.lastLatency = latencyMs
        counter.totalLatency += latencyMs
        counter.latencySamples.addLast(latencyMs)
        while (counter.latencySamples.size > 32) counter.latencySamples.removeFirst()

        when (state) {
            ReadingState.OK -> {
                successes++
                counter.successes++
                counter.previousSuccessMs = counter.lastSuccessMs
                counter.lastSuccessMs = now
                if (counter.previousSuccessMs > 0) {
                    val gap = (now - counter.previousSuccessMs).toDouble()
                    counter.rateEwmaMs = if (counter.rateEwmaMs <= 0.0) {
                        gap
                    } else {
                        counter.rateEwmaMs * 0.7 + gap * 0.3
                    }
                }
            }
            ReadingState.UNSUPPORTED -> {
                negatives++
                counter.negatives++
            }
            ReadingState.ERROR -> {
                if (failure == ReadingFailure.TIMEOUT) {
                    timeouts++
                    counter.timeouts++
                }
                if (failure == ReadingFailure.TRANSPORT) {
                    transportErrors++
                    counter.transportErrors++
                }
                if (failure == ReadingFailure.NEGATIVE) {
                    negatives++
                    counter.negatives++
                }
            }
        }

        journal.addLast(Event(now, signalId, state, failure, latencyMs, batchSize))
        while (journal.size > journalLimit) journal.removeFirst()
    }

    /** Актуальность значения: движок присылает её на каждом тике. */
    @Synchronized
    fun onFreshness(signalId: String, status: SignalStatus, ageMs: Long) {
        val counter = counters.getOrPut(signalId) { Counter() }
        if (counter.status == SignalStatus.VALID && status != SignalStatus.VALID) {
            counter.staleTransitions++
            staleTransitions++
        }
        counter.status = status
        counter.ageMs = max(0L, ageMs)
    }

    @Synchronized
    fun onQueueDepth(depth: Int) {
        queueDepth = depth
    }

    @Synchronized
    fun onEcuSwitch() {
        ecuSwitches++
    }

    @Synchronized
    fun reset() {
        counters.clear()
        requestTimes.clear()
        globalLatency = ArrayDeque()
        journal.clear()
        requests = 0
        successes = 0
        timeouts = 0
        negatives = 0
        transportErrors = 0
        ecuSwitches = 0
        staleTransitions = 0
        queueDepth = 0
    }

    @Synchronized
    fun snapshot(now: Long = clock()): Snapshot {
        val signals = counters.map { (id, counter) ->
            val average = if (counter.attempts == 0L) 0.0
            else counter.totalLatency.toDouble() / counter.attempts
            val perSecond = if (counter.rateEwmaMs > 0.0) 1000.0 / counter.rateEwmaMs else 0.0
            SignalStats(
                signalId = id,
                attempts = counter.attempts,
                successes = counter.successes,
                timeouts = counter.timeouts,
                negativeResponses = counter.negatives,
                transportErrors = counter.transportErrors,
                averageLatencyMs = round1(average),
                p95LatencyMs = percentile(counter.latencySamples.toList(), 0.95),
                updatesPerSecond = round1(perSecond),
                ageMs = counter.ageMs,
                staleTransitions = counter.staleTransitions,
                status = counter.status,
            )
        }
        return Snapshot(
            uptimeMs = (now - startedAt).coerceAtLeast(0L),
            requests = requests,
            successes = successes,
            timeouts = timeouts,
            negativeResponses = negatives,
            transportErrors = transportErrors,
            requestRateHz = round1(requestRate()),
            averageLatencyMs = round1(
                if (globalLatency.isEmpty()) 0.0
                else globalLatency.average(),
            ),
            p95LatencyMs = percentile(globalLatency.toList(), 0.95),
            successPercent = round1(if (requests == 0L) 0.0 else successes * 100.0 / requests),
            ecuSwitches = ecuSwitches,
            queueDepth = queueDepth,
            staleTransitions = staleTransitions,
            signals = signals,
        )
    }

    /** Последние записи журнала в хронологическом порядке. */
    @Synchronized
    fun events(limit: Int = journalLimit): List<Event> =
        journal.toList().takeLast(limit.coerceAtLeast(0))

    private fun requestRate(): Double {
        val times = requestTimes.toList()
        if (times.size < 2) return 0.0
        val span = (times.last() - times.first()).coerceAtLeast(1L)
        return (times.size - 1) * 1000.0 / span
    }

    private fun percentile(samples: List<Long>, fraction: Double): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0
}
