package io.github.nchalexz.aaperfmon.telemetry

/** Bounded in-memory timings for diagnostic builds, retained until the process stops. */
internal object TelemetryTrace {
    data class Exchange(
        val adapterSession: Int,
        val command: String,
        val txId: Int,
        val startedAtMs: Long,
        val writtenAtMs: Long?,
        val firstBytesAtMs: Long?,
        val firstCanFrameAtMs: Long?,
        val promptAtMs: Long?,
        val finishedAtMs: Long,
        val error: String?,
        val response: String,
    )

    data class Poll(
        val startedAtMs: Long,
        val publishedAtMs: Long,
        val readings: Map<String, Reading>,
    )

    data class BridgeRead(val readyAtMs: Long, val mapSampleAtMs: Long)

    private class Buffer<T>(private val limit: Int) {
        private val records = ArrayDeque<T>()

        @Synchronized
        fun add(record: T) {
            if (records.size == limit) records.removeFirst()
            records.addLast(record)
        }

        @Synchronized
        fun snapshot(): List<T> = records.toList()
    }

    private val exchanges = Buffer<Exchange>(12_000)
    private val polls = Buffer<Poll>(12_000)
    private val bridgeReads = Buffer<BridgeRead>(12_000)

    fun exchange(record: Exchange) = exchanges.add(record)
    fun poll(record: Poll) = polls.add(record)
    fun bridgeRead(record: BridgeRead) = bridgeReads.add(record)

    fun exchanges() = exchanges.snapshot()
    fun polls() = polls.snapshot()
    fun bridgeReads() = bridgeReads.snapshot()

    private val privateDids = setOf("F190", "F187", "F189", "F19E", "F18C")

    fun isTelemetryCommand(command: String): Boolean {
        val clean = command.replace(" ", "").uppercase()
        if (clean.any { it !in "0123456789ABCDEF" }) return false
        return when {
            clean.startsWith("01") -> clean.length >= 4 && clean.length % 2 == 0
            clean.startsWith("22") -> clean.length >= 6 && (clean.length - 2) % 4 == 0 &&
                clean.drop(2).chunked(4).none { it in privateDids }
            else -> false
        }
    }
}
