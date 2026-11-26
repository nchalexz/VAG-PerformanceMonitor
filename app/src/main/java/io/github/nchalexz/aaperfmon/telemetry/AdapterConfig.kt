package io.github.nchalexz.aaperfmon.telemetry

import android.content.Context
import org.json.JSONObject

/** Куда и как подключаться. */
sealed interface AdapterConfig {
    val label: String

    data class Wifi(val host: String, val port: Int = 35000) : AdapterConfig {
        override val label: String get() = "Wi-Fi $host:$port"
    }

    data class Bluetooth(val address: String, val name: String = "") : AdapterConfig {
        override val label: String
            get() = "Bluetooth " + name.ifBlank { address }
    }

    data class Usb(val deviceName: String, val displayName: String = "") : AdapterConfig {
        override val label: String
            get() = "USB " + displayName.ifBlank { deviceName }
    }
}

/**
 * Последняя конфигурация адаптера и решение о автоподключении.
 * Хранится отдельно от профиля автомобиля: профилей может быть много,
 * а адаптер у пользователя обычно один.
 */
object AdapterStore {
    private const val PREFS = "aapm_prefs"
    private const val KEY_CONFIG = "adapter_config"
    private const val KEY_AUTO = "adapter_auto_connect"

    fun save(context: Context, config: AdapterConfig) {
        val json = when (config) {
            is AdapterConfig.Wifi -> JSONObject()
                .put("type", "wifi")
                .put("host", config.host)
                .put("port", config.port)
            is AdapterConfig.Bluetooth -> JSONObject()
                .put("type", "bluetooth")
                .put("address", config.address)
                .put("name", config.name)
            is AdapterConfig.Usb -> JSONObject()
                .put("type", "usb")
                .put("device", config.deviceName)
                .put("name", config.displayName)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONFIG, json.toString())
            .putBoolean(KEY_AUTO, true)
            .apply()
    }

    fun load(context: Context): AdapterConfig? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIG, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "wifi" -> AdapterConfig.Wifi(
                    json.optString("host", "192.168.0.10"),
                    json.optInt("port", 35000),
                )
                "bluetooth" -> AdapterConfig.Bluetooth(
                    json.optString("address"),
                    json.optString("name"),
                )
                "usb" -> AdapterConfig.Usb(
                    json.optString("device"),
                    json.optString("name"),
                )
                else -> null
            }
        }.getOrNull()
    }

    fun autoConnect(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO, false)

    fun setAutoConnect(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO, enabled)
            .apply()
    }
}
