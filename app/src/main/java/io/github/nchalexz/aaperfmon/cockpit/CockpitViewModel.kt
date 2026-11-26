package io.github.nchalexz.aaperfmon.cockpit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nchalexz.aaperfmon.core.AdapterHolder
import io.github.nchalexz.aaperfmon.telemetry.DashboardSpec
import io.github.nchalexz.aaperfmon.telemetry.Dashboards
import io.github.nchalexz.aaperfmon.telemetry.DemoSignalSource
import io.github.nchalexz.aaperfmon.telemetry.Elm327SignalSource
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.SignalSource
import io.github.nchalexz.aaperfmon.telemetry.SignalValue
import io.github.nchalexz.aaperfmon.telemetry.TelemetryEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Что сейчас открыто в приборке. */
enum class CockpitScreen { DRIVING, PERFORMANCE, DATA }

/** Состояние экрана приборов. */
data class CockpitUiState(
    val values: Map<String, SignalValue> = emptyMap(),
    val now: Long = 0,
    val demo: Boolean = true,
    val sourceLabel: String = "ДЕМО",
    val adapterAvailable: Boolean = false,
    val channel: String = "",
)

/**
 * Держит движок телеметрии, пока открыта приборка.
 *
 * По умолчанию источник демонстрационный: экран можно открыть без
 * автомобиля и без адаптера. Если диагностический экран уже подключился,
 * кнопка «ELM327» переключает на то же самое соединение — второе не
 * открывается.
 */
class CockpitViewModel(application: Application) : AndroidViewModel(application) {

    private val valuesFlow = MutableStateFlow<Map<String, SignalValue>>(emptyMap())
    private val demoMode = MutableStateFlow(true)
    private val adapterAvailable = MutableStateFlow(AdapterHolder.elm != null)

    private var engine: TelemetryEngine = newEngine(DemoSignalSource())
    private var collector: Job? = null

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(500)
        }
    }

    val state: StateFlow<CockpitUiState> = combine(
        valuesFlow,
        ticker,
        demoMode,
        adapterAvailable,
    ) { values, now, demo, hasAdapter ->
        CockpitUiState(
            values = values,
            now = now,
            demo = demo,
            sourceLabel = if (demo) "ДЕМО" else "ELM327",
            adapterAvailable = hasAdapter,
            channel = AdapterHolder.title,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(2_000),
        CockpitUiState(),
    )

    init {
        attachEngine(engine)
        selectScreen(CockpitScreen.DRIVING)
        engine.start()
    }

    fun selectScreen(screen: CockpitScreen) {
        when (screen) {
            CockpitScreen.DRIVING -> {
                engine.setDashboard(Dashboards.driving.signalIds)
                engine.setCatalogEnabled(false)
            }
            CockpitScreen.PERFORMANCE -> {
                engine.setDashboard(Dashboards.performance.signalIds)
                engine.setCatalogEnabled(false)
            }
            CockpitScreen.DATA -> {
                // На списке параметров приборка не нужна: отдаём шину
                // каталогу, который идёт по кругу по одному сигналу.
                engine.setDashboard(emptyList())
                engine.setCatalog(SignalCatalog.all.map { it.id })
                engine.setCatalogEnabled(true)
            }
        }
    }

    fun useAdapter() {
        val adapter = AdapterHolder.elm ?: return
        adapterAvailable.value = true
        switchSource(Elm327SignalSource(adapter), demo = false)
    }

    fun useDemo() {
        switchSource(DemoSignalSource(), demo = true)
    }

    private fun switchSource(source: SignalSource, demo: Boolean) {
        engine.stop()
        engine.close()
        engine = newEngine(source)
        demoMode.value = demo
        attachEngine(engine)
        selectScreen(CockpitScreen.DRIVING)
        engine.start()
    }

    private fun newEngine(source: SignalSource): TelemetryEngine =
        TelemetryEngine(source = source, scope = viewModelScope)

    private fun attachEngine(target: TelemetryEngine) {
        collector?.cancel()
        collector = viewModelScope.launch {
            target.values.collect { valuesFlow.value = it }
        }
    }

    /** Для экрана данных: сколько сигналов уже опрошено. */
    fun dashboard(screen: CockpitScreen): DashboardSpec? = when (screen) {
        CockpitScreen.DRIVING -> Dashboards.driving
        CockpitScreen.PERFORMANCE -> Dashboards.performance
        CockpitScreen.DATA -> null
    }

    override fun onCleared() {
        collector?.cancel()
        engine.close()
        super.onCleared()
    }
}
