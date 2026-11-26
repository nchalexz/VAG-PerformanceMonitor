package io.github.nchalexz.aaperfmon.cockpit

import android.content.Context
import io.github.nchalexz.aaperfmon.cockpit.ui.DisplayCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** Ночная, дневная или автоматическая тема приборки. */
enum class ThemeMode { AUTO, DAY, NIGHT }

/**
 * Язык подписей. По умолчанию английский, но на русской системе
 * автомобильный экран переключается на русский сам — так требуют
 * макеты (английские подписи) и остальное приложение (русский язык).
 */
enum class LanguageMode { SYSTEM, ENGLISH, RUSSIAN }

enum class CockpitPage(val index: Int) {
    DASHBOARD(0),
    EXTENDED(1);

    companion object {
        fun of(index: Int): CockpitPage = entries.firstOrNull { it.index == index } ?: DASHBOARD
    }
}

/** Язык, на котором реально рисуются подписи. */
enum class UiLanguage { EN, RU }

data class CockpitSettings(
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val languageMode: LanguageMode = LanguageMode.SYSTEM,
    /** Последняя открытая категория расширенного экрана. Экран всегда стартует с главного. */
    val categoryId: String = "engine",
)

fun CockpitSettings.resolveLanguage(locale: Locale = Locale.getDefault()): UiLanguage =
    when (languageMode) {
        LanguageMode.ENGLISH -> UiLanguage.EN
        LanguageMode.RUSSIAN -> UiLanguage.RU
        LanguageMode.SYSTEM -> if (locale.language.equals("ru", ignoreCase = true)) {
            UiLanguage.RU
        } else {
            UiLanguage.EN
        }
    }

fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.AUTO -> systemDark
    ThemeMode.DAY -> false
    ThemeMode.NIGHT -> true
}

/**
 * Настройки приборки в SharedPreferences.
 *
 * Отдельно от профиля автомобиля и адаптера: это оформление, а не данные.
 * Объект живёт в процессе, поэтому смена темы в настройках сразу видна
 * на автомобильном экране, когда он откроется снова.
 */
object CockpitPrefs {
    private const val NAME = "aapm_cockpit"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LANGUAGE = "language_mode"
    private const val KEY_CATEGORY = "category"

    private var app: Context? = null
    private val _state = MutableStateFlow(CockpitSettings())
    val state: StateFlow<CockpitSettings> = _state.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        val application = context.applicationContext
        app = application
        val prefs = application.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        _state.value = CockpitSettings(
            themeMode = runCatching {
                ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.AUTO.name)
            }.getOrDefault(ThemeMode.AUTO),
            languageMode = runCatching {
                LanguageMode.valueOf(prefs.getString(KEY_LANGUAGE, null) ?: LanguageMode.SYSTEM.name)
            }.getOrDefault(LanguageMode.SYSTEM),
            categoryId = prefs.getString(KEY_CATEGORY, null)
                ?.takeIf { id -> DisplayCategory.entries.any { it.id == id } }
                ?: DisplayCategory.ENGINE.id,
        )
    }

    fun setThemeMode(mode: ThemeMode) = update(KEY_THEME, mode.name) {
        it.copy(themeMode = mode)
    }

    fun setLanguageMode(mode: LanguageMode) = update(KEY_LANGUAGE, mode.name) {
        it.copy(languageMode = mode)
    }

    fun setCategory(categoryId: String) = update(KEY_CATEGORY, categoryId) {
        it.copy(categoryId = categoryId)
    }

    private fun update(key: String, value: String, block: (CockpitSettings) -> CockpitSettings) {
        val context = app ?: return
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(key, value)
            .apply()
        _state.update(block)
    }
}
