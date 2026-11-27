package io.github.nchalexz.aaperfmon.cockpit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nchalexz.aaperfmon.cockpit.ui.CatalogOverlay
import io.github.nchalexz.aaperfmon.cockpit.ui.CockpitChrome
import io.github.nchalexz.aaperfmon.cockpit.ui.DashboardScreen
import io.github.nchalexz.aaperfmon.cockpit.ui.DashboardTheme
import io.github.nchalexz.aaperfmon.cockpit.ui.DisplayCategory
import io.github.nchalexz.aaperfmon.cockpit.ui.ExtendedDashboardScreen
import io.github.nchalexz.aaperfmon.cockpit.ui.MetricRegistry
import io.github.nchalexz.aaperfmon.cockpit.ui.PageDots
import io.github.nchalexz.aaperfmon.cockpit.ui.dayDashboardColors
import io.github.nchalexz.aaperfmon.cockpit.ui.nightDashboardColors
import io.github.nchalexz.aaperfmon.cockpit.ui.pick
import io.github.nchalexz.aaperfmon.settings.SettingsActivity
import io.github.nchalexz.aaperfmon.telemetry.ConnectionKind
import kotlinx.coroutines.launch

/**
 * Полноэкранная приборка.
 *
 * Это обычная Activity, а не шаблон Car App Library: она предназначена для
 * AARE/DashGrid и для телефона, где Android разрешает произвольный рисунок.
 * Два экрана листаются свайпом; нажатие на прибор открывает его категорию
 * на расширенном экране. Настройки и выход — в верхнем углу, статус — плашкой.
 */
class CockpitActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Приборка не должна гаснуть, пока на неё смотрят с головного устройства.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Приборка занимает весь экран: в автомобиле строка состояния и
        // панель навигации только крадут место у шкал.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { CockpitRoot() }
    }
}

@Composable
fun CockpitRoot(model: CockpitViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as? Activity
    val state by model.state.collectAsState()
    val settings by CockpitPrefs.state.collectAsState()
    val language = settings.resolveLanguage()
    val dark = settings.themeMode.isDark(androidx.compose.foundation.isSystemInDarkTheme())
    val colors = if (dark) nightDashboardColors() else dayDashboardColors()
    val scope = rememberCoroutineScope()

    var categoryId by rememberSaveable { mutableStateOf(CockpitPrefs.state.value.categoryId) }
    var catalogOpen by rememberSaveable { mutableStateOf(false) }
    val pager = rememberPagerState(pageCount = { 2 })

    val includeVirtual = state.demo
    val categories = MetricRegistry.categories(includeVirtual)
    val selected = DisplayCategory.byId(categoryId)
        .let { if (it in categories) it else categories.first() }

    LaunchedEffect(selected) {
        if (selected.id != categoryId) categoryId = selected.id
    }
    LaunchedEffect(pager.currentPage, selected, catalogOpen) {
        if (catalogOpen) {
            model.setCatalogVisible(true)
        } else {
            model.setCatalogVisible(false)
            model.show(CockpitPage.of(pager.currentPage), selected)
        }
    }
    BackHandler(enabled = catalogOpen || pager.currentPage > 0) {
        if (catalogOpen) {
            catalogOpen = false
        } else {
            scope.launch { pager.animateScrollToPage(CockpitPage.DASHBOARD.index) }
        }
    }

    DashboardTheme(dark) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                CockpitChrome(
                    connection = state.connection,
                    sourceLabel = sourceLabel(state.connection, language),
                    channel = when (state.connection) {
                        ConnectionKind.ERROR -> state.error
                        else -> state.channel
                    },
                    language = language,
                    onCatalog = { catalogOpen = true },
                    onSettings = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    },
                    onClose = { activity?.finish() },
                )
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !catalogOpen,
                    beyondViewportPageCount = 0,
                ) { page ->
                    val onGauge: (io.github.nchalexz.aaperfmon.cockpit.ui.MetricSpec) -> Unit = { spec ->
                        categoryId = spec.category.id
                        scope.launch { pager.animateScrollToPage(CockpitPage.EXTENDED.index) }
                    }
                    when (CockpitPage.of(page)) {
                        CockpitPage.DASHBOARD -> DashboardScreen(
                            values = state.values,
                            now = state.now,
                            canShowData = state.canShowData,
                            language = language,
                            onGaugeClick = onGauge,
                            modifier = Modifier.fillMaxSize(),
                        )
                        CockpitPage.EXTENDED -> ExtendedDashboardScreen(
                            values = state.values,
                            now = state.now,
                            canShowData = state.canShowData,
                            language = language,
                            categories = categories,
                            selected = selected,
                            includeVirtual = includeVirtual,
                            onCategory = { categoryId = it.id },
                            onGaugeClick = onGauge,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            PageDots(
                current = pager.currentPage,
                count = 2,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
            )

            if (catalogOpen) {
                CatalogOverlay(
                    values = state.values,
                    now = state.now,
                    canShowData = state.canShowData,
                    language = language,
                    onClose = { catalogOpen = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun sourceLabel(connection: ConnectionKind, language: UiLanguage): String = when (connection) {
    ConnectionKind.DEMO -> language.pick("DEMO", "ДЕМО")
    ConnectionKind.CONNECTED -> "Live"
    ConnectionKind.CONNECTING -> language.pick("CONNECTING", "ПОДКЛЮЧЕНИЕ")
    ConnectionKind.ERROR -> language.pick("ERROR", "ОШИБКА")
    ConnectionKind.DISCONNECTED -> language.pick("NO LINK", "НЕТ СВЯЗИ")
}
