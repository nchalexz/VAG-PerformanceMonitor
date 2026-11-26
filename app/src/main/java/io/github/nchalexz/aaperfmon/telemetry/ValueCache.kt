package io.github.nchalexz.aaperfmon.telemetry

import android.content.Context
import org.json.JSONObject

/**
 * Последние значения на диск.
 *
 * Нужен виджету и экрану, когда процесс приложения был перезапущен:
 * показать «последнее известное» с честной пометкой «устарело», а не
 * пустоту и не ноль. Запись throttled: SharedPreferences не любит
 * несколько записей в секунду.
 */
object ValueCache {
    private const val PREFS = "aapm_prefs"
    private const val KEY_VALUES = "last_values"
    private const val MIN_INTERVAL_MS = 1_500L

    @Volatile
    private var lastWrite = 0L

    fun load(context: Context): Map<String, SignalValue> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VALUES, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { id ->
                    val item = root.optJSONObject(id) ?: return@forEach
                    val value = if (item.has("v")) item.optDouble("v") else null
                    put(
                        id,
                        SignalValue(
                            value = value,
                            timestampMs = item.optLong("t", 0L),
                            state = runCatching {
                                ReadingState.valueOf(item.optString("s"))
                            }.getOrDefault(ReadingState.ERROR),
                            sourceLabel = item.optString("src", "кэш"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun save(context: Context, values: Map<String, SignalValue>, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWrite < MIN_INTERVAL_MS) return
        lastWrite = now
        val root = JSONObject()
        values.forEach { (id, value) ->
            root.put(
                id,
                JSONObject()
                    .put("t", value.timestampMs)
                    .put("s", value.state.name)
                    .put("src", value.sourceLabel)
                    .apply {
                        if (value.value != null) put("v", value.value)
                    },
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_VALUES, root.toString())
            .apply()
    }
}
