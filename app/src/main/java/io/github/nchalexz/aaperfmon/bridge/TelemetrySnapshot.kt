package io.github.nchalexz.aaperfmon.bridge

import io.github.nchalexz.aaperfmon.cockpit.UiLanguage
import io.github.nchalexz.aaperfmon.cockpit.ui.DisplayCategory
import io.github.nchalexz.aaperfmon.cockpit.ui.MetricRegistry
import io.github.nchalexz.aaperfmon.telemetry.ConnectionKind
import io.github.nchalexz.aaperfmon.telemetry.DemoSignalSource
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.SignalStatus
import io.github.nchalexz.aaperfmon.telemetry.TelemetryState
import org.json.JSONArray
import org.json.JSONObject

/** Read-only contract v1 between VAG Performance Monitor and DashGrid. */
const val BRIDGE_VERSION = 1
const val BRIDGE_MAX_SIGNALS = 128
const val BRIDGE_METHOD_SNAPSHOT = "snapshot"
const val BRIDGE_AUTHORITY = "io.github.nchalexz.aaperfmon.telemetry"
const val BRIDGE_READ_PERMISSION = "io.github.nchalexz.aaperfmon.permission.READ_TELEMETRY"

/** One signal of the snapshot, already in the units VPM's cockpit displays. */
data class BridgeSignal(
    val id: String,
    val label: String,
    val value: String?,
    val unit: String,
    val raw: Double?,
    val stale: Boolean,
    val warning: Boolean,
    val category: String,
    /** valid, stale или never_read: разница между «—» и N/A видна и снаружи. */
    val status: String,
)

/** The snapshot VPM publishes through its ContentProvider. */
data class BridgeSnapshot(
    val connection: String,
    val status: String,
    val source: String,
    val updatedAt: Long,
    val signals: List<BridgeSignal>,
) {
    fun signal(id: String): BridgeSignal? = signals.firstOrNull { it.id == id }
}

/**
 * Builds the bridge snapshot from the last state the process already holds.
 *
 * No OBD request is made here and no service is started: missing or stale
 * values are sent as null with `stale = true`, never as zero. The values are
 * formatted exactly as the cockpit shows them (comma decimal, bar for boost),
 * because DashGrid draws the same numbers and does not re-derive VPM factors.
 */
object TelemetrySnapshot {

    /**
     * ДЕМО-модель для снимка. Чтение из DashGrid не запускает службу и не
     * опрашивает адаптер, поэтому значения берутся из той же модели, которую
     * показала бы приборка, а не из замороженного кэша процесса.
     */
    private val demo = DemoSignalSource()

    fun build(
        state: TelemetryState,
        now: Long,
        language: UiLanguage = UiLanguage.RU,
    ): BridgeSnapshot {
        val isDemo = state.connection == ConnectionKind.DEMO
        // DashGrid groups by VPM's display category and keeps the order inside a
        // category, so boost may appear once under Engine and once under Turbo.
        // The main screen reads the first occurrence of an id; main gauges/tiles
        // are therefore placed first in their own category.
        val main = MetricRegistry.gauges + MetricRegistry.mainTiles
        val entries = DisplayCategory.entries.flatMap { category ->
            (main.filter { it.category == category } +
                MetricRegistry.category(category, includeVirtual = true))
                .distinctBy { it.signalId }
                .map { category to it }
        }.take(BRIDGE_MAX_SIGNALS)
        val signals = entries
            .mapNotNull { (category, spec) ->
                val definition = SignalCatalog.get(spec.signalId) ?: return@mapNotNull null
                val value = state.values[spec.signalId]
                val demoRaw = if (isDemo) demo.demoValue(spec.signalId, now) else null
                val status = when {
                    isDemo -> if (demoRaw == null) SignalStatus.NEVER_READ else SignalStatus.VALID
                    else -> value?.statusAt(now, definition) ?: SignalStatus.NEVER_READ
                }
                val raw = when {
                    isDemo -> demoRaw
                    // Устаревшее значение отдаётся вместе с флагом stale:
                    // DashGrid сам решает, показывать его приглушённо или
                    // прочерком. N/A — только когда числа не было вовсе.
                    status == SignalStatus.NEVER_READ -> null
                    else -> value?.value
                }
                val display = raw?.let { spec.display(it) }
                BridgeSignal(
                    id = definition.id,
                    label = spec.label(language),
                    value = if (raw == null) null else spec.format(raw, spec.factor, spec.decimals),
                    unit = spec.unit(language),
                    raw = display,
                    stale = status != SignalStatus.VALID,
                    warning = spec.warningValue(display),
                    category = category.id,
                    status = status.name.lowercase(),
                )
            }
        // The contract defines updatedAt as the newest timestamp among the
        // signals that were actually sent, not as the process's last tick: a
        // snapshot with nothing in it reports 0, not a wall clock from a read.
        // Demo values are computed at read time, so now is their real update.
        val returnedIds = signals.mapTo(HashSet()) { it.id }
        val updatedAt = if (isDemo) {
            now
        } else {
            state.values
                .filterKeys { it in returnedIds }
                .values
                .maxOfOrNull { it.timestampMs }
                ?: 0L
        }
        return BridgeSnapshot(
            connection = state.connection.name.lowercase(),
            status = buildString {
                append(state.connectionLabel.ifBlank { state.connection.name })
                if (state.message.isNotBlank()) append(" · ").append(state.message)
            },
            source = if (state.connection == ConnectionKind.CONNECTED) state.connectionLabel else "",
            updatedAt = updatedAt,
            signals = signals,
        )
    }

    /** JSON array for the `signals` field of the provider Bundle. */
    fun signalsJson(snapshot: BridgeSnapshot): String {
        val array = JSONArray()
        snapshot.signals.forEach { signal ->
            array.put(
                JSONObject().apply {
                    put("id", signal.id)
                    put("label", signal.label)
                    if (signal.value == null) put("value", JSONObject.NULL) else put("value", signal.value)
                    put("unit", signal.unit)
                    if (signal.raw == null) put("raw", JSONObject.NULL) else put("raw", signal.raw)
                    put("stale", signal.stale)
                    put("warning", signal.warning)
                    put("category", signal.category)
                    put("status", signal.status)
                },
            )
        }
        return array.toString()
    }
}
