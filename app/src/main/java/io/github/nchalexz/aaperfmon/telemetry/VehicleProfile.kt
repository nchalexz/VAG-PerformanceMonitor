package io.github.nchalexz.aaperfmon.telemetry

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Профиль автомобиля: то, что пользователь выбирает до подключения и что
 * определяет набор отображаемых параметров.
 *
 * Это не «автоматическое определение по VIN» — такой функции пока нет.
 * Профиль честно редактируется вручную; встроенные профили помечены как
 * непроверенные.
 */
data class VehicleProfile(
    val id: String,
    val name: String,
    val platform: String,
    val engine: String,
    val transmission: String,
    val vin: String,
    val signalIds: Set<String>,
    val builtIn: Boolean = false,
) {
    val summary: String
        get() = listOf(platform, engine, transmission)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

object VehicleProfiles {

    /** Набор по умолчанию совпадает с экранами «Движение» и «Динамика». */
    private val defaultSignals: List<String> = (
        Dashboards.driving.signalIds + Dashboards.performance.signalIds
        ).distinct()

    val mqb = VehicleProfile(
        id = "vag-mqb",
        name = "VAG MQB",
        platform = "MQB",
        engine = "",
        transmission = "",
        vin = "",
        signalIds = defaultSignals.toSet(),
        builtIn = true,
    )

    val universal = VehicleProfile(
        id = "obd2-universal",
        name = "Универсальный OBD-II",
        platform = "OBD-II",
        engine = "",
        transmission = "",
        vin = "",
        signalIds = defaultSignals.toSet(),
        builtIn = true,
    )

    val builtIn: List<VehicleProfile> = listOf(mqb, universal)

    fun byId(id: String): VehicleProfile =
        builtIn.firstOrNull { it.id == id } ?: mqb
}

/**
 * Хранилище активного профиля. JSON, без Room: на один профиль это
 * дешевле и не тянет зависимость ради нескольких полей.
 */
object VehicleStore {
    private const val PREFS = "aapm_prefs"
    private const val KEY_PROFILE = "vehicle_profile"

    fun load(context: Context): VehicleProfile {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROFILE, null)
            ?: return VehicleProfiles.mqb
        return runCatching { fromJson(JSONObject(raw)) }.getOrElse { VehicleProfiles.mqb }
    }

    fun save(context: Context, profile: VehicleProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROFILE, toJson(profile).toString())
            .apply()
    }

    fun reset(context: Context): VehicleProfile {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PROFILE)
            .apply()
        return VehicleProfiles.mqb
    }

    fun toJson(profile: VehicleProfile): JSONObject = JSONObject()
        .put("id", profile.id)
        .put("name", profile.name)
        .put("platform", profile.platform)
        .put("engine", profile.engine)
        .put("transmission", profile.transmission)
        .put("vin", profile.vin)
        .put("builtIn", profile.builtIn)
        .put("signals", JSONArray(profile.signalIds.toList()))

    fun fromJson(json: JSONObject): VehicleProfile {
        val signals = mutableSetOf<String>()
        val array = json.optJSONArray("signals") ?: JSONArray()
        for (i in 0 until array.length()) {
            val id = array.optString(i)
            if (id.isNotBlank()) signals += id
        }
        return VehicleProfile(
            id = json.optString("id", "custom"),
            name = json.optString("name", "Автомобиль"),
            platform = json.optString("platform", ""),
            engine = json.optString("engine", ""),
            transmission = json.optString("transmission", ""),
            vin = json.optString("vin", ""),
            signalIds = signals.ifEmpty { VehicleProfiles.mqb.signalIds },
            builtIn = json.optBoolean("builtIn", false),
        )
    }
}
