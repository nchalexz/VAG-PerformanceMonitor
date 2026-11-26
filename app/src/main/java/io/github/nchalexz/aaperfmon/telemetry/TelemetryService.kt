package io.github.nchalexz.aaperfmon.telemetry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.nchalexz.aaperfmon.MainActivity
import io.github.nchalexz.aaperfmon.R
import io.github.nchalexz.aaperfmon.widget.WidgetPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Держит процесс живым, пока идёт телеметрия или существуют виджеты.
 *
 * Сам движок живёт в [Telemetry]; сервис не открывает второе соединение.
 * Его задачи: foreground-статус, уведомление, обновление виджетов и
 * переживание сворачивания Activity.
 */
class TelemetryService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Telemetry.init(this)
        Telemetry.autoConnectOnce()
        createChannel()
        startForegroundCompat(notification(Telemetry.state.value))
        scope.launch {
            Telemetry.state.collectLatest { state ->
                updateNotification(state)
                WidgetPublisher.publishAll(this@TelemetryService, state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Telemetry.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        Telemetry.init(this)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
        }
    }

    private fun updateNotification(state: TelemetryState) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification(state))
        }
    }

    private fun notification(state: TelemetryState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, TelemetryService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state.connection) {
            ConnectionKind.CONNECTED -> "Подключено: ${state.connectionLabel}"
            ConnectionKind.CONNECTING -> "Подключение: ${state.connectionLabel}"
            ConnectionKind.DEMO -> "Демонстрационный режим"
            ConnectionKind.DISCONNECTED -> "Нет связи с адаптером"
            ConnectionKind.ERROR -> state.message.ifBlank { "Ошибка подключения" }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_telemetry)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Отключить", disconnect)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Телеметрия",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Подключение к адаптеру и обновление виджетов"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_DISCONNECT = "io.github.nchalexz.aaperfmon.DISCONNECT"
        private const val CHANNEL_ID = "telemetry"
        private const val NOTIFICATION_ID = 42
    }
}
