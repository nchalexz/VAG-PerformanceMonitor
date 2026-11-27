package io.github.nchalexz.aaperfmon.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверка технических метрик: без них «целевая частота» — только слова. */
class TelemetryMetricsTest {

    @Test
    fun `outcomes are counted separately`() {
        val metrics = TelemetryMetrics(clock = { 0L })
        metrics.onPoll("a", ReadingState.OK, ReadingFailure.NONE, 100L, now = 1_000L)
        metrics.onPoll("a", ReadingState.ERROR, ReadingFailure.TIMEOUT, 250L, now = 1_100L)
        metrics.onPoll("a", ReadingState.UNSUPPORTED, ReadingFailure.NEGATIVE, 80L, now = 1_200L)
        metrics.onPoll("b", ReadingState.ERROR, ReadingFailure.TRANSPORT, 0L, now = 1_300L)
        metrics.onPoll("b", ReadingState.ERROR, ReadingFailure.NEGATIVE, 0L, now = 1_400L)

        val snapshot = metrics.snapshot(now = 2_000L)
        assertEquals(5, snapshot.requests.toInt())
        assertEquals(1, snapshot.successes.toInt())
        assertEquals(1, snapshot.timeouts.toInt())
        assertEquals(2, snapshot.negativeResponses.toInt())
        assertEquals(1, snapshot.transportErrors.toInt())
        assertEquals(20.0, snapshot.successPercent, 0.01)
        assertTrue(snapshot.averageLatencyMs > 0.0)
    }

    @Test
    fun `p95 latency is computed from the sample window`() {
        val metrics = TelemetryMetrics(clock = { 0L })
        for (i in 1..20) {
            metrics.onPoll("a", ReadingState.OK, ReadingFailure.NONE, i * 10L, now = i * 100L)
        }
        val snapshot = metrics.snapshot()
        // Индекс 0,95 от 20 отсчётов — предпоследний: 190 мс.
        assertEquals(190L, snapshot.p95LatencyMs)
    }

    @Test
    fun `actual update rate follows successful intervals`() {
        val metrics = TelemetryMetrics(clock = { 0L })
        for (i in 1..10) {
            metrics.onPoll("a", ReadingState.OK, ReadingFailure.NONE, 50L, now = i * 100L)
        }
        val signal = metrics.snapshot().signals.first { it.signalId == "a" }
        assertEquals(10.0, signal.updatesPerSecond, 0.5)
    }

    @Test
    fun `stale transitions are counted once per transition`() {
        val metrics = TelemetryMetrics(clock = { 0L })
        metrics.onFreshness("a", SignalStatus.VALID, ageMs = 100L)
        metrics.onFreshness("a", SignalStatus.STALE, ageMs = 6_000L)
        metrics.onFreshness("a", SignalStatus.STALE, ageMs = 7_000L)
        metrics.onFreshness("a", SignalStatus.VALID, ageMs = 100L)
        metrics.onFreshness("a", SignalStatus.STALE, ageMs = 6_000L)

        val snapshot = metrics.snapshot()
        assertEquals(2, snapshot.staleTransitions.toInt())
        assertEquals(2, snapshot.signals.first().staleTransitions.toInt())
        assertEquals(SignalStatus.STALE, snapshot.signals.first().status)
    }

    @Test
    fun `journal keeps the most recent events`() {
        val metrics = TelemetryMetrics(clock = { 0L }, journalLimit = 3)
        for (i in 1..5) {
            metrics.onPoll("a", ReadingState.OK, ReadingFailure.NONE, 10L, now = i * 100L)
        }
        val events = metrics.events()
        assertEquals(3, events.size)
        assertEquals(300L, events.first().atMs)
        assertEquals(500L, events.last().atMs)
    }
}
