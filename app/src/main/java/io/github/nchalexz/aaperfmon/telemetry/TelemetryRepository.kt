package io.github.nchalexz.aaperfmon.telemetry

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import io.github.nchalexz.aaperfmon.core.BluetoothTransport
import io.github.nchalexz.aaperfmon.core.Elm327
import io.github.nchalexz.aaperfmon.core.UsbSerialTransport
import io.github.nchalexz.aaperfmon.core.WifiTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectionKind { DEMO, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

data class TelemetryState(
    val connection: ConnectionKind = ConnectionKind.DEMO,
    val connectionLabel: String = "ДЕМО",
    val message: String = "",
    val values: Map<String, SignalValue> = emptyMap(),
    val profile: VehicleProfile = VehicleProfiles.golf75,
    val lastUpdateMs: Long = 0L,
) {
    val isDemo: Boolean get() = connection == ConnectionKind.DEMO
    val isLive: Boolean get() = connection == ConnectionKind.CONNECTED
    val canShowData: Boolean get() = isDemo || isLive
}

/**
 * Единственный владелец соединения с ELM327 и движка телеметрии.
 *
 * Все представления — полноэкранный дашборд, AppWidget, автомобильный
 * экран — читают один и тот же StateFlow и подписываются на одни и те же
 * сигналы. Второй OBD-сессии не существует: это и есть требование
 * «одно подключение на все экраны».
 *
 * Живёт в процессе приложения. Пока нужно работать в фоне, рядом живёт
 * [TelemetryService], который держит процесс и обновляет уведомление и
 * виджеты. Сам движок привязан к процессу, а не к Activity.
 */
object Telemetry {

    private const val PREFS = "aapm_prefs"

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(TelemetryState())
    val state: StateFlow<TelemetryState> = _state.asStateFlow()

    @Volatile
    private var initialized = false

    private var engine: TelemetryEngine? = null
    private var source: SignalSource? = null
    private var valuesJob: Job? = null
    private var connectJob: Job? = null

    /** Подписки разных экранов: дашборд, виджеты, профиль. */
    private val watches = mutableMapOf<String, Set<String>>()
    private var catalogEnabled = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        initialized = true
        val profile = VehicleStore.load(app)
        val cached = ValueCache.load(app)
        _state.value = TelemetryState(
            values = cached,
            profile = profile,
            connection = ConnectionKind.DEMO,
            connectionLabel = "ДЕМО",
        )
        watches["profile"] = profile.signalIds
        installLocked(DemoSignalSource(), ConnectionKind.DEMO, "ДЕМО", "")

        val config = AdapterStore.load(app)
        if (config != null && AdapterStore.autoConnect(app)) {
            connect(config)
        }
    }

    fun connect(config: AdapterConfig) {
        if (!initialized) return
        connectJob?.cancel()
        _state.update {
            it.copy(
                connection = ConnectionKind.CONNECTING,
                connectionLabel = "Подключение",
                message = config.label,
            )
        }
        connectJob = scope.launch {
            try {
                stopEngineLocked()
                val transport = openTransport(config)
                val elm = Elm327(transport)
                elm.connect()
                elm.init()
                val (version, voltage) = elm.identify()
                installLocked(
                    Elm327SignalSource(elm),
                    ConnectionKind.CONNECTED,
                    config.label,
                    "$version · $voltage",
                )
                AdapterStore.save(app, config)
                startService()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        connection = ConnectionKind.ERROR,
                        connectionLabel = "Ошибка",
                        message = error.message ?: "не удалось подключиться",
                    )
                }
            }
        }
    }

    @Synchronized
    fun useDemo() {
        if (!initialized) return
        connectJob?.cancel()
        AdapterStore.setAutoConnect(app, false)
        installLocked(DemoSignalSource(), ConnectionKind.DEMO, "ДЕМО", "")
        _state.update { it.copy(message = "Демонстрационные данные, не автомобиль") }
    }

    @Synchronized
    fun disconnect() {
        if (!initialized) return
        connectJob?.cancel()
        AdapterStore.setAutoConnect(app, false)
        valuesJob?.cancel()
        valuesJob = null
        engine?.stop()
        engine?.close()
        engine = null
        source = null
        _state.update {
            it.copy(
                connection = ConnectionKind.DISCONNECTED,
                connectionLabel = "Нет связи",
                message = "Адаптер отключён",
            )
        }
        stopService()
    }

    /**
     * Подписка представления. Ключи не пересекаются: "cockpit", "widgets",
     * "profile". Движок получает объединение.
     */
    fun subscribe(key: String, ids: Collection<String>) {
        if (!initialized) return
        val filtered = ids.filter { SignalCatalog.get(it) != null }.toSet()
        synchronized(watches) { watches[key] = filtered }
        engine?.setDashboard(allWatched())
    }

    fun setCatalogEnabled(enabled: Boolean) {
        catalogEnabled = enabled
        engine?.setCatalogEnabled(enabled)
    }

    @Synchronized
    fun saveProfile(profile: VehicleProfile) {
        if (!initialized) return
        VehicleStore.save(app, profile)
        _state.update { it.copy(profile = profile) }
        watches["profile"] = profile.signalIds
        engine?.setDashboard(allWatched())
    }

    fun startService() {
        if (!initialized) return
        runCatching {
            ContextCompat.startForegroundService(
                app,
                android.content.Intent(app, TelemetryService::class.java),
            )
        }
    }

    fun stopService() {
        if (!initialized) return
        runCatching { app.stopService(android.content.Intent(app, TelemetryService::class.java)) }
    }

    fun shutdown() {
        synchronized(this) {
            valuesJob?.cancel()
            engine?.stop()
            engine?.close()
            engine = null
            source = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun openTransport(config: AdapterConfig): io.github.nchalexz.aaperfmon.core.Transport =
        when (config) {
            is AdapterConfig.Wifi -> WifiTransport(config.host, config.port)
            is AdapterConfig.Bluetooth -> {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: error("На устройстве нет Bluetooth")
                if (!adapter.isEnabled) error("Bluetooth выключен")
                BluetoothTransport(adapter.getRemoteDevice(config.address))
            }
            is AdapterConfig.Usb -> {
                val manager = app.getSystemService(UsbManager::class.java)
                    ?: error("USB-менеджер недоступен")
                val device = manager.deviceList.values
                    .firstOrNull { it.deviceName == config.deviceName }
                    ?: error("USB-адаптер не найден")
                if (!manager.hasPermission(device)) {
                    error("Нет разрешения USB. Подключите адаптер через диагностический экран")
                }
                UsbSerialTransport(manager, device)
            }
        }

    @Synchronized
    private fun stopEngineLocked() {
        valuesJob?.cancel()
        valuesJob = null
        engine?.stop()
        engine?.close()
        engine = null
        source = null
        _state.update { it.copy(values = emptyMap()) }
    }

    @Synchronized
    private fun installLocked(
        newSource: SignalSource,
        kind: ConnectionKind,
        label: String,
        message: String,
    ) {
        valuesJob?.cancel()
        valuesJob = null
        engine?.stop()
        engine?.close()
        source = newSource
        val newEngine = TelemetryEngine(newSource, scope)
        engine = newEngine
        newEngine.setDashboard(allWatched())
        newEngine.setCatalogEnabled(catalogEnabled)
        valuesJob = scope.launch {
            newEngine.values.collect { values ->
                if (values.isNotEmpty()) {
                    _state.update {
                        it.copy(values = values, lastUpdateMs = System.currentTimeMillis())
                    }
                    ValueCache.save(app, values)
                }
            }
        }
        newEngine.start()
        _state.update {
            it.copy(
                connection = kind,
                connectionLabel = label,
                message = message,
                values = if (kind == ConnectionKind.DEMO) it.values else emptyMap(),
            )
        }
    }

    private fun allWatched(): List<String> = synchronized(watches) {
        watches.values.flatten().distinct()
    }
}
