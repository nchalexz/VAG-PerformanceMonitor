package io.github.nchalexz.aaperfmon.cockpit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.nchalexz.aaperfmon.R

/**
 * Цветовые токены приборки.
 *
 * Ночная тема повторяет макеты, дневная — та же композиция на светлом фоне.
 * Токены не смешиваются с телефонным интерфейсом: у диагностики своя схема,
 * у автомобильного экрана своя. Так дневную тему можно сделать читаемой
 * на солнце, не ломая остальные экраны.
 */
@Immutable
data class DashboardColors(
    val background: Color,
    val panel: Color,
    val card: Color,
    val border: Color,
    val text: Color,
    val dim: Color,
    val tick: Color,
    val ring: Color,
    val accent: Color,
    val warning: Color,
    val critical: Color,
    val dialFace: Color,
    val needle: Color,
    val shadow: Color,
)

/** Ночная тема: графит, тонкие шкалы, белые цифры. */
fun nightDashboardColors() = DashboardColors(
    background = Color(0xFF10151B),
    panel = Color(0xFF141D24),
    card = Color(0xFF1A1F25),
    border = Color(0xFF2A3037),
    text = Color(0xFFF2F4F6),
    dim = Color(0xFF89939E),
    tick = Color(0xFF59636D),
    ring = Color(0xFFB8C0C8),
    accent = Color(0xFF54B6AD),
    warning = Color(0xFFE5AE54),
    critical = Color(0xFFD96060),
    dialFace = Color(0xFF0B1015),
    needle = Color(0xFFF2F4F6),
    shadow = Color(0x66000000),
)

/**
 * Дневная тема: не инверсия картинки, а отдельные токены.
 * Фон не белый, чтобы не слепить на солнце; текст почти чёрный.
 */
fun dayDashboardColors() = DashboardColors(
    background = Color(0xFFE8ECEF),
    panel = Color(0xFFF6F8FA),
    card = Color(0xFFFFFFFF),
    border = Color(0xFFC3CBD3),
    text = Color(0xFF14191E),
    dim = Color(0xFF55606B),
    tick = Color(0xFF8A949E),
    ring = Color(0xFF4E5964),
    accent = Color(0xFF0F766E),
    warning = Color(0xFF9A5B00),
    critical = Color(0xFFB3261E),
    dialFace = Color(0xFFE1E7EC),
    needle = Color(0xFF1A2027),
    shadow = Color(0x22000000),
)

val LocalDashboardColors = staticCompositionLocalOf { nightDashboardColors() }

/**
 * Titillium Web уже лежит в ресурсах: он даёт тонкие цифры как на макетах.
 * В шрифте нет кириллицы, поэтому русские подписи Android подставит из
 * системного шрифта — цифры при этом остаются макетными.
 */
val DashboardFont = FontFamily(
    Font(R.font.titillium_web_light, FontWeight.Light),
    Font(R.font.titillium_web_regular, FontWeight.Normal),
    Font(R.font.titillium_web_semibold, FontWeight.SemiBold),
)

@Composable
fun DashboardTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) nightDashboardColors() else dayDashboardColors()
    CompositionLocalProvider(LocalDashboardColors provides colors) {
        content()
    }
}
