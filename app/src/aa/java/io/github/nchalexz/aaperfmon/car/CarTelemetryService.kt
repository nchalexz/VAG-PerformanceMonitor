package io.github.nchalexz.aaperfmon.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.nchalexz.aaperfmon.telemetry.ConnectionKind
import io.github.nchalexz.aaperfmon.telemetry.MonotonicClock
import io.github.nchalexz.aaperfmon.telemetry.SignalStatus
import io.github.nchalexz.aaperfmon.telemetry.Dashboards
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.Telemetry
import io.github.nchalexz.aaperfmon.telemetry.TelemetryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android Auto / Car App Library.
 *
 * Штатный автомобильный хост не даёт произвольный Canvas-интерфейс:
 * доступны только шаблоны. Поэтому здесь — шаблонный список с реальными
 * показателями и статусом. Полноэкранная приборка остаётся в
 * CockpitActivity и запускается на AARE/DashGrid как обычное Android-
 * приложение.
 *
 * HostValidator.ALLOW_ALL нужен для AARE/сайдлоада. Для публикации в
 * Google Play приложение обязано пройти проверку категории и использовать
 * белый список хостов; это ограничение описано в документации.
 */
class CarTelemetryService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session {
        Telemetry.init(applicationContext)
        return TelemetrySession()
    }
}

private class TelemetrySession : Session() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screen: TelemetryScreen? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val created = TelemetryScreen(carContext)
        screen = created
        scope.launch {
            Telemetry.state.collect { state ->
                created.update(state)
                created.invalidate()
            }
        }
        // Новые значения приходят с частотой опроса, но «устарело» и `N/A`
        // наступают без нового ответа. Экран обязан перерисоваться сам.
        scope.launch {
            while (true) {
                delay(5_000)
                created.invalidate()
            }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
        return created
    }
}

private class TelemetryScreen(carContext: androidx.car.app.CarContext) : Screen(carContext) {

    @Volatile
    private var state: TelemetryState = Telemetry.state.value

    fun update(newState: TelemetryState) {
        state = newState
    }

    override fun onGetTemplate(): Template {
        val connection = when (state.connection) {
            ConnectionKind.CONNECTED -> "ELM327 · подключено"
            ConnectionKind.DEMO -> "ДЕМО · данные модели"
            ConnectionKind.CONNECTING -> "Подключение…"
            ConnectionKind.ERROR -> state.message.ifBlank { "Ошибка" }
            ConnectionKind.DISCONNECTED -> "Нет связи"
        }
        val list = ItemList.Builder()
        list.addItem(
            Row.Builder()
                .setTitle("Состояние")
                .addText(connection)
                .build(),
        )
        val ids = state.profile.signalIds.ifEmpty {
            Dashboards.performance.signalIds.toSet()
        }
        val now = MonotonicClock.now()
        ids.mapNotNull { SignalCatalog.get(it) }
            .take(8)
            .forEach { definition ->
                val value = state.values[definition.id]
                val status = value?.statusAt(now, definition) ?: SignalStatus.NEVER_READ
                val text = when (status) {
                    SignalStatus.VALID -> "${definition.format(value?.value)} ${definition.unit}".trim()
                    SignalStatus.STALE ->
                        "${definition.format(value?.value)} ${definition.unit} · устарело".trim()
                    SignalStatus.NEVER_READ -> "—"
                }
                list.addItem(
                    Row.Builder()
                        .setTitle(definition.nameRu)
                        .addText(text)
                        .build(),
                )
            }
        list.setNoItemsMessage("Нет доступных параметров")
        return ListTemplate.Builder()
            .setTitle("AA Performance")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .build()
    }
}
