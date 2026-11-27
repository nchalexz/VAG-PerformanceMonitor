package io.github.nchalexz.aaperfmon.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.nchalexz.aaperfmon.R
import io.github.nchalexz.aaperfmon.cockpit.CockpitActivity
import io.github.nchalexz.aaperfmon.telemetry.ConnectionKind
import io.github.nchalexz.aaperfmon.telemetry.MonotonicClock
import io.github.nchalexz.aaperfmon.telemetry.ReadingState
import io.github.nchalexz.aaperfmon.telemetry.SignalStatus
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.SignalDefinition
import io.github.nchalexz.aaperfmon.telemetry.SignalValue
import io.github.nchalexz.aaperfmon.telemetry.Telemetry
import io.github.nchalexz.aaperfmon.telemetry.TelemetryState
import io.github.nchalexz.aaperfmon.telemetry.ValueDisplay
import kotlin.math.roundToInt

/**
 * Собирает RemoteViews для всех экземпляров виджета.
 *
 * Частота обновления ограничена здесь, а не в движке: OBD опрашивается
 * со своими частотами, а виджет перерисовывается не чаще ~3 раз в секунду.
 * AppWidgetManager не любит шквал обновлений, а лаунчер — тем более.
 */
object WidgetPublisher {

    private const val MIN_INTERVAL_MS = 350L
    private val slotIds = intArrayOf(
        R.id.slot1_label, R.id.slot1_value, R.id.slot1_unit, R.id.slot1_bar,
    )

    @Volatile
    private var lastPublish = 0L

    @Synchronized
    fun publishAll(context: Context, state: TelemetryState, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < MIN_INTERVAL_MS) return
        lastPublish = now
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, TelemetryWidgetProvider::class.java),
        )
        ids.forEach { id ->
            runCatching { manager.updateAppWidget(id, build(context, id, state)) }
        }
    }

    fun publishOne(context: Context, widgetId: Int, state: TelemetryState = Telemetry.state.value) {
        val manager = AppWidgetManager.getInstance(context)
        runCatching { manager.updateAppWidget(widgetId, build(context, widgetId, state)) }
    }

    private fun build(context: Context, widgetId: Int, state: TelemetryState): RemoteViews {
        val manager = AppWidgetManager.getInstance(context)
        val options = manager.getAppWidgetOptions(widgetId)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
        val compact = minWidth < 250 || minHeight < 130
        val config = WidgetStore.load(context, widgetId)
            ?: WidgetStore.defaultFor(state.profile)
        val layout = if (compact) R.layout.widget_telemetry_small else R.layout.widget_telemetry

        val views = RemoteViews(context.packageName, layout)
        views.setTextViewText(R.id.widget_title, config.title.ifBlank { "Телеметрия" })
        views.setTextViewText(R.id.widget_status, statusText(state))
        views.setTextColor(R.id.widget_status, statusColor(context, state))

        val slots = if (compact) config.signalIds.take(2) else config.signalIds.take(4)
        val now = MonotonicClock.now()
        slots.forEachIndexed { index, signalId ->
            bindSlot(context, views, index, signalId, state.values[signalId], now)
        }
        for (index in slots.size until if (compact) 2 else 4) {
            hideSlot(views, index)
        }

        val open = PendingIntent.getActivity(
            context,
            20 + widgetId,
            Intent(context, CockpitActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        return views
    }

    private fun bindSlot(
        context: Context,
        views: RemoteViews,
        index: Int,
        signalId: String,
        value: SignalValue?,
        now: Long,
    ) {
        val definition = SignalCatalog.get(signalId) ?: run {
            hideSlot(views, index)
            return
        }
        views.setTextViewText(slotLabel(index), definition.nameRu.uppercase())
        views.setTextViewText(slotUnit(index), definition.unit)

        val status = value?.statusAt(now, definition) ?: SignalStatus.NEVER_READ
        // Устаревшее значение остаётся числом, только приглушённым:
        // прочерк — признак того, что параметр не прочитан ни разу.
        val number = if (status == SignalStatus.VALID || status == SignalStatus.STALE) {
            value?.value
        } else {
            null
        }
        views.setTextViewText(
            slotValue(index),
            if (number == null) "—" else definition.format(number),
        )
        views.setTextColor(
            slotValue(index),
            if (number == null || status == SignalStatus.STALE) {
                ContextCompat.getColor(context, R.color.widget_dim)
            } else if (isWarning(definition, number)) {
                ContextCompat.getColor(context, R.color.widget_warn)
            } else {
                ContextCompat.getColor(context, R.color.widget_text)
            },
        )
        // У передачи нет шкалы: P/R/N и номер — это подпись, а не доля.
        val fraction = if (definition.display == ValueDisplay.GEAR) null else fraction(definition, number)
        if (fraction == null) {
            views.setViewVisibility(slotValue(index), android.view.View.VISIBLE)
            views.setProgressBar(slotBar(index), 100, 0, false)
            views.setViewVisibility(slotBar(index), android.view.View.INVISIBLE)
        } else {
            views.setViewVisibility(slotBar(index), android.view.View.VISIBLE)
            views.setProgressBar(slotBar(index), 100, (fraction * 100).roundToInt(), false)
        }
    }

    private fun hideSlot(views: RemoteViews, index: Int) {
        views.setViewVisibility(slotLabel(index), android.view.View.GONE)
        views.setViewVisibility(slotValue(index), android.view.View.GONE)
        views.setViewVisibility(slotUnit(index), android.view.View.GONE)
        views.setViewVisibility(slotBar(index), android.view.View.GONE)
    }

    private fun fraction(definition: SignalDefinition, value: Double?): Float? {
        if (value == null) return null
        val min = definition.minValue ?: return null
        val max = definition.maxValue ?: return null
        if (max <= min) return null
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
    }

    private fun isWarning(definition: SignalDefinition, value: Double): Boolean = when (definition.id) {
        SignalCatalog.Ids.COOLANT -> value >= 110.0
        SignalCatalog.Ids.OIL_TEMP -> value >= 140.0
        SignalCatalog.Ids.BOOST -> value >= 200.0
        SignalCatalog.Ids.POWER_KW -> value >= 240.0
        SignalCatalog.Ids.TORQUE_NM -> value >= 450.0
        else -> false
    }

    private fun statusText(state: TelemetryState): String = when (state.connection) {
        ConnectionKind.CONNECTED -> "LIVE"
        ConnectionKind.DEMO -> "ДЕМО"
        ConnectionKind.CONNECTING -> "…"
        ConnectionKind.ERROR -> "ОШИБКА"
        ConnectionKind.DISCONNECTED -> "НЕТ СВЯЗИ"
    }

    private fun statusColor(context: Context, state: TelemetryState): Int = when (state.connection) {
        ConnectionKind.CONNECTED -> ContextCompat.getColor(context, R.color.widget_ok)
        ConnectionKind.DEMO -> ContextCompat.getColor(context, R.color.widget_accent)
        ConnectionKind.ERROR -> ContextCompat.getColor(context, R.color.widget_warn)
        else -> ContextCompat.getColor(context, R.color.widget_dim)
    }

    private fun slotLabel(index: Int) = intArrayOf(
        R.id.slot1_label, R.id.slot2_label, R.id.slot3_label, R.id.slot4_label,
    )[index]

    private fun slotValue(index: Int) = intArrayOf(
        R.id.slot1_value, R.id.slot2_value, R.id.slot3_value, R.id.slot4_value,
    )[index]

    private fun slotUnit(index: Int) = intArrayOf(
        R.id.slot1_unit, R.id.slot2_unit, R.id.slot3_unit, R.id.slot4_unit,
    )[index]

    private fun slotBar(index: Int) = intArrayOf(
        R.id.slot1_bar, R.id.slot2_bar, R.id.slot3_bar, R.id.slot4_bar,
    )[index]
}
