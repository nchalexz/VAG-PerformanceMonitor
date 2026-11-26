package io.github.nchalexz.aaperfmon.widget

import android.content.ComponentName
import android.content.Context
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.VehicleProfile
import io.github.nchalexz.aaperfmon.telemetry.VehicleStore
import org.json.JSONArray
import org.json.JSONObject

data class WidgetConfig(
    val title: String,
    val signalIds: List<String>,
)

/**
 * Настройки конкретного экземпляра виджета.
 *
 * Ключ — appWidgetId, поэтому несколько виджетов на рабочем столе могут
 * показывать разные наборы параметров, не мешая друг другу.
 */
object WidgetStore {
    private const val PREFS = "aapm_widgets"

    private val fallback = listOf(
        SignalCatalog.Ids.BOOST,
        SignalCatalog.Ids.POWER_KW,
        SignalCatalog.Ids.TORQUE_NM,
        SignalCatalog.Ids.COOLANT,
    )

    fun load(context: Context, widgetId: Int): WidgetConfig? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(widgetId), null) ?: return null
        return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun save(context: Context, widgetId: Int, config: WidgetConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(widgetId), toJson(config).toString())
            .apply()
    }

    fun delete(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(key(widgetId))
            .apply()
    }

    fun all(context: Context): Map<Int, WidgetConfig> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (key, value) ->
            val id = key.removePrefix("w").toIntOrNull() ?: return@mapNotNull null
            val config = runCatching { fromJson(JSONObject(value as String)) }.getOrNull()
            if (config == null) null else id to config
        }.toMap()
    }

    fun ensureDefaults(context: Context, widgetId: Int): WidgetConfig {
        load(context, widgetId)?.let { return it }
        val config = defaultFor(VehicleStore.load(context))
        save(context, widgetId, config)
        return config
    }

    fun defaultFor(profile: VehicleProfile): WidgetConfig {
        val available = profile.signalIds
            .filter { SignalCatalog.get(it) != null }
        val chosen = (fallback + available)
            .distinct()
            .take(4)
        return WidgetConfig(title = profile.name, signalIds = chosen)
    }

    fun ids(context: Context): IntArray {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(
            ComponentName(context, TelemetryWidgetProvider::class.java),
        )
    }

    private fun key(widgetId: Int) = "w$widgetId"

    private fun toJson(config: WidgetConfig): JSONObject = JSONObject()
        .put("title", config.title)
        .put("signals", JSONArray(config.signalIds))

    private fun fromJson(json: JSONObject): WidgetConfig {
        val ids = mutableListOf<String>()
        val array = json.optJSONArray("signals") ?: JSONArray()
        for (i in 0 until array.length()) {
            val id = array.optString(i)
            if (SignalCatalog.get(id) != null) ids += id
        }
        return WidgetConfig(
            title = json.optString("title", "Телеметрия"),
            signalIds = ids.take(4).ifEmpty { fallback },
        )
    }
}
