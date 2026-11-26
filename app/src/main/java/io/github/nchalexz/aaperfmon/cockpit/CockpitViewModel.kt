package io.github.nchalexz.aaperfmon.cockpit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nchalexz.aaperfmon.telemetry.ConnectionKind
import io.github.nchalexz.aaperfmon.telemetry.DashboardSpec
import io.github.nchalexz.aaperfmon.telemetry.Dashboards
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.SignalValue
import io.github.nchalexz.aaperfmon.telemetry.Telemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** Что сейчас открыто в приборке. */
enum class CockpitScreen { DRIVING, PERFORMANCE, DATA }

/** Состояние экрана приборов. */
data class CockpitUiState(
    val values: Map<String, SignalValue> = emptyMap(),
    val now: Long = 0,
    val demo: Boolean = true,
    val live: Boolean = false,
    val sourceLabel: String = "ДЕМО",
    val channel: String = "",
    val error: String = "",
)

/**
 * Экран приборов больше не владеет движком: он только подписывается на
 * общий [Telemetry] и просит нужные сигналы. Соединение открывается в
 * настройках и живёт в сервисе, поэтому виджет и автомобильный экран
 * видят те же данные.
 */
class CockpitViewModel(application: Application) : AndroidViewModel(application) {

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(500)
        }
    }

    init {
        Telemetry.init(application)
        selectScreen(CockpitScreen.DRIVING)
    }

    val state: StateFlow<CockpitUiState> = combine(
        Telemetry.state,
        ticker,
    ) { telemetry, now ->
        CockpitUiState(
            values = telemetry.values,
            now = now,
            demo = telemetry.connection == ConnectionKind.DEMO,
            live = telemetry.connection == ConnectionKind.CONNECTED,
            sourceLabel = when (telemetry.connection) {
                ConnectionKind.DEMO -> "ДЕМО"
                ConnectionKind.CONNECTED -> "LIVE"
                ConnectionKind.CONNECTING -> "ПОДКЛЮЧЕНИЕ"
                ConnectionKind.ERROR -> "ОШИБКА"
                ConnectionKind.DISCONNECTED -> "НЕТ СВЯЗИ"
            },
            channel = telemetry.message.ifBlank { telemetry.connectionLabel },
            error = if (telemetry.connection == ConnectionKind.ERROR) telemetry.message else "",
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(2_000),
        CockpitUiState(),
    )

    fun selectScreen(screen: CockpitScreen) {
        val panel = listOf(
            SignalCatalog.Ids.POWER_KW,
            SignalCatalog.Ids.TORQUE_NM,
            SignalCatalog.Ids.LOAD,
            SignalCatalog.Ids.VOLTAGE,
        )
        when (screen) {
            CockpitScreen.DRIVING -> {
                Telemetry.subscribe("cockpit", Dashboards.driving.signalIds + panel)
                Telemetry.setCatalogEnabled(false)
            }
            CockpitScreen.PERFORMANCE -> {
                Telemetry.subscribe("cockpit", Dashboards.performance.signalIds + panel)
                Telemetry.setCatalogEnabled(false)
            }
            CockpitScreen.DATA -> {
                Telemetry.subscribe("cockpit", emptyList())
                Telemetry.setCatalogEnabled(true)
            }
        }
    }

    fun useDemo() {
        Telemetry.useDemo()
    }

    fun dashboard(screen: CockpitScreen): DashboardSpec? = when (screen) {
        CockpitScreen.DRIVING -> Dashboards.driving
        CockpitScreen.PERFORMANCE -> Dashboards.performance
        CockpitScreen.DATA -> null
    }

    override fun onCleared() {
        Telemetry.subscribe("cockpit", emptyList())
        Telemetry.setCatalogEnabled(false)
        super.onCleared()
    }
}
