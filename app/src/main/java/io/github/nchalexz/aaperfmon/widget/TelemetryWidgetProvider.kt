package io.github.nchalexz.aaperfmon.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import io.github.nchalexz.aaperfmon.telemetry.Telemetry

/**
 * Провайдер компактного виджета.
 *
 * Высокочастотное обновление идёт не через updatePeriodMillis (он не
 * годится для телеметрии), а из [TelemetryService] через
 * [WidgetPublisher]. Провайдер только создаёт/восстанавливает настройки
 * экземпляров и обновляет их при добавлении и изменении размера.
 */
class TelemetryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Telemetry.init(context)
        appWidgetIds.forEach { WidgetStore.ensureDefaults(context, it) }
        Telemetry.startService()
        WidgetPublisher.publishAll(context, Telemetry.state.value, force = true)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        Telemetry.init(context)
        WidgetStore.ensureDefaults(context, appWidgetId)
        WidgetPublisher.publishAll(context, Telemetry.state.value, force = true)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetStore.delete(context, it) }
        if (WidgetStore.ids(context).isEmpty() && !Telemetry.state.value.isLive) {
            Telemetry.stopService()
        }
    }

    override fun onEnabled(context: Context) {
        Telemetry.init(context)
        Telemetry.startService()
    }

    override fun onDisabled(context: Context) {
        Telemetry.stopService()
    }
}
