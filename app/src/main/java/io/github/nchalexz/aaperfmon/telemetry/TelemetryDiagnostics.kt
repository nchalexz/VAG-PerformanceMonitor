package io.github.nchalexz.aaperfmon.telemetry

import android.content.Context
import android.os.Build
import io.github.nchalexz.aaperfmon.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экспорт журнала производительности телеметрии.
 *
 * Файл создаётся по явному нажатию и содержит только технические числа:
 * частоты, задержки, таймауты, состояния очереди. Ни VIN, ни координат,
 * ни содержимого памяти блока здесь нет — это журнал опроса, а не дамп
 * автомобиля.
 *
 * Формат JSON выбран потому, что его одинаково удобно смотреть человеку
 * и разбирать скриптом после поездки на реальном ELM327.
 */
object TelemetryDiagnostics {

    /** Подкаталог кэша: FileProvider отдаёт ровно его, не весь кэш. */
    const val DIRECTORY = "diagnostics"

    private const val KEEP_FILES = 5

    fun export(
        context: Context,
        source: String,
        connection: TelemetryState,
        metrics: TelemetryMetrics.Snapshot?,
        schedule: List<TelemetryScheduler.Snapshot>,
        events: List<TelemetryMetrics.Event>,
    ): File? = runCatching {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        rotate(directory)

        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(now)
        val file = File(directory, "telemetry-$stamp.json")

        val root = JSONObject().apply {
            put("format", "vpm-telemetry-diagnostics")
            put("formatVersion", 1)
            put("exportedAt", iso(now))
            put("appVersion", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", Build.VERSION.RELEASE)
            put("source", source)
            put(
                "connection",
                JSONObject().apply {
                    put("kind", connection.connection.name.lowercase(Locale.ROOT))
                    put("label", connection.connectionLabel)
                    put("message", connection.message)
                    put("adapterVersion", connection.adapterVersion)
                    put("voltage", connection.voltage)
                },
            )
            put("metrics", metrics?.let { metricsJson(it) } ?: JSONObject.NULL)
            put(
                "schedule",
                JSONArray().apply {
                    schedule.forEach { entry ->
                        put(
                            JSONObject().apply {
                                put("signalId", entry.signalId)
                                put("priority", entry.priority.name)
                                put("intervalMs", entry.effectiveIntervalMs)
                                put("lastAttemptMs", entry.lastAttemptMs)
                                put("lastSuccessMs", entry.lastSuccessMs)
                                put("consecutiveFailures", entry.consecutiveFailures)
                                put("hasSucceeded", entry.hasSucceeded)
                                put("suspendedUntilMs", entry.suspendedUntilMs)
                                put("latencyEwmaMs", entry.latencyEwmaMs)
                            },
                        )
                    }
                },
            )
            put(
                "events",
                JSONArray().apply {
                    // Журнал ограничен по объёму при сборке метрик; здесь
                    // он просто переписывается как есть.
                    events.forEach { event ->
                        put(
                            JSONObject().apply {
                                put("atMs", event.atMs)
                                put("signalId", event.signalId)
                                put("state", event.state.name.lowercase(Locale.ROOT))
                                put("failure", event.failure.name.lowercase(Locale.ROOT))
                                put("latencyMs", event.latencyMs)
                                put("batchSize", event.batchSize)
                            },
                        )
                    }
                },
            )
            put(
                "notes",
                JSONArray().apply {
                    put("times are monotonic milliseconds since device boot (elapsedRealtime)")
                    put("no VIN, no location, no vehicle identifiers are collected")
                },
            )
        }

        file.writeText(root.toString(2))
        file
    }.getOrNull()

    private fun rotate(directory: File) {
        val files = directory.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
        files.dropLast(KEEP_FILES - 1).forEach { runCatching { it.delete() } }
    }

    private fun metricsJson(snapshot: TelemetryMetrics.Snapshot): JSONObject =
        JSONObject().apply {
            put("uptimeMs", snapshot.uptimeMs)
            put("requests", snapshot.requests)
            put("successes", snapshot.successes)
            put("timeouts", snapshot.timeouts)
            put("negativeResponses", snapshot.negativeResponses)
            put("transportErrors", snapshot.transportErrors)
            put("requestRateHz", snapshot.requestRateHz)
            put("averageLatencyMs", snapshot.averageLatencyMs)
            put("p95LatencyMs", snapshot.p95LatencyMs)
            put("successPercent", snapshot.successPercent)
            put("ecuSwitches", snapshot.ecuSwitches)
            put("queueDepth", snapshot.queueDepth)
            put("staleTransitions", snapshot.staleTransitions)
            put(
                "signals",
                JSONArray().apply {
                    snapshot.signals.forEach { signal ->
                        put(
                            JSONObject().apply {
                                put("signalId", signal.signalId)
                                put("attempts", signal.attempts)
                                put("successes", signal.successes)
                                put("timeouts", signal.timeouts)
                                put("negativeResponses", signal.negativeResponses)
                                put("transportErrors", signal.transportErrors)
                                put("averageLatencyMs", signal.averageLatencyMs)
                                put("p95LatencyMs", signal.p95LatencyMs)
                                put("updatesPerSecond", signal.updatesPerSecond)
                                put("ageMs", signal.ageMs)
                                put("staleTransitions", signal.staleTransitions)
                                put("status", signal.status.name.lowercase(Locale.ROOT))
                            },
                        )
                    }
                },
            )
        }

    private fun iso(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(date)
}
