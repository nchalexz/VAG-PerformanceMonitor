package io.github.nchalexz.aaperfmon.cockpit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nchalexz.aaperfmon.cockpit.ui.DisplayCategory
import io.github.nchalexz.aaperfmon.cockpit.ui.MetricRegistry
import io.github.nchalexz.aaperfmon.telemetry.ConnectionKind
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.SignalValue
import io.github.nchalexz.aaperfmon.telemetry.Telemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** Состояние приборки: только данные, без Compose и без знания о раскладке. */
data class CockpitUiState(
    val values: Map<String, SignalValue> = emptyMap(),
    val now: Long = 0L,
    val connection: ConnectionKind = ConnectionKind.DEMO,
    val demo: Boolean = true,
    val live: Boolean = false,
    val canShowData: Boolean = true,
    val channel: String = "",
    val error: String = "",
)

/**
 * Экран приборов не владеет движком: он подписывается на общий [Telemetry]
 * и просит только те сигналы, которые сейчас видны. Главный экран — три
 * прибора и пять плиток; расширенный — те же приборы и одна категория.
 * Поэтому смена категории меняет набор опрашиваемых PID, а не грузит шину
 * всем каталогом.
 */
class CockpitViewModel(application: Application) : AndroidViewModel(application) {

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(500)
        }
    }

    private var page: CockpitPage = CockpitPage.DASHBOARD
    private var category: DisplayCategory = DisplayCategory.ENGINE
    private var catalogEnabled = false

    init {
        Telemetry.init(application)
        Telemetry.autoConnectOnce()
        CockpitPrefs.init(application)
        category = DisplayCategory.byId(CockpitPrefs.state.value.categoryId)
        subscribe()
    }

    val state: StateFlow<CockpitUiState> = combine(
        Telemetry.state,
        ticker,
    ) { telemetry, now ->
        CockpitUiState(
            values = telemetry.values,
            now = now,
            connection = telemetry.connection,
            demo = telemetry.connection == ConnectionKind.DEMO,
            live = telemetry.connection == ConnectionKind.CONNECTED,
            canShowData = telemetry.canShowData,
            channel = when (telemetry.connection) {
                ConnectionKind.CONNECTED -> telemetry.connectionLabel
                else -> ""
            },
            error = if (telemetry.connection == ConnectionKind.ERROR) telemetry.message else "",
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(2_000),
        CockpitUiState(),
    )

    /** Переключение экрана или категории: подписка меняется сразу. */
    fun show(newPage: CockpitPage, newCategory: DisplayCategory) {
        val categoryChanged = newCategory != category
        page = newPage
        category = newCategory
        if (categoryChanged && newPage == CockpitPage.EXTENDED) {
            CockpitPrefs.setCategory(newCategory.id)
        }
        subscribe()
    }

    /** Каталог открывается поверх приборки и один опрашивает все сигналы. */
    fun setCatalogVisible(visible: Boolean) {
        catalogEnabled = visible
        Telemetry.setCatalogEnabled(visible)
        if (visible) {
            Telemetry.setCatalog(SignalCatalog.all.map { it.id })
            Telemetry.subscribe(SUBSCRIPTION, emptyList())
        } else {
            subscribe()
        }
    }

    fun useDemo() {
        Telemetry.useDemo()
    }

    private fun subscribe() {
        if (catalogEnabled) return
        val includeVirtual = Telemetry.state.value.connection == ConnectionKind.DEMO
        val ids = buildList {
            addAll(MetricRegistry.gauges.flatMap { it.allSignalIds })
            when (page) {
                CockpitPage.DASHBOARD -> addAll(MetricRegistry.mainTiles.flatMap { it.allSignalIds })
                CockpitPage.EXTENDED -> addAll(
                    MetricRegistry.category(category, includeVirtual)
                        .flatMap { it.allSignalIds },
                )
            }
        }
        Telemetry.subscribe(SUBSCRIPTION, ids)
        Telemetry.setCatalogEnabled(false)
    }

    override fun onCleared() {
        Telemetry.subscribe(SUBSCRIPTION, emptyList())
        Telemetry.setCatalogEnabled(false)
        super.onCleared()
    }

    private companion object {
        const val SUBSCRIPTION = "cockpit"
    }
}
