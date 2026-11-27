package io.github.nchalexz.aaperfmon.telemetry

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.nchalexz.aaperfmon.core.AdapterHolder
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
    val adapterVersion: String = "",
    val voltage: String = "",
    val values: Map<String, SignalValue> = emptyMap(),
    val profile: VehicleProfile = VehicleProfiles.mqb,
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

    /** Автоподключение предлагается один раз за процесс, а не на каждое чтение. */
    @Volatile
    private var autoConnectOffered = false

    private var engine: TelemetryEngine? = null
    private var source: SignalSource? = null
    private var valuesJob: Job? = null
    private var connectJob: Job? = null

    /**
     * Метрики последней сессии. Держим их и после отключения: журнал
     * производительности нужен как раз тогда, когда связь уже потеряна.
     */
    @Volatile
    private var lastMetrics: TelemetryMetrics? = null

    /** Подписки разных экранов: дашборд, виджеты, профиль. */
    private val watches = mutableMapOf<String, Set<String>>()
    private var catalogEnabled = false

    /** Запасные источники: переживают переподключение адаптера. */
    @Volatile
    private var fallbacks: Map<String, String> = emptyMap()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        initialized = true
        // Возраст данных считается по монотонным часам, а не по календарю.
        MonotonicClock.install { SystemClock.elapsedRealtime() }
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
    }

    /**
     * Однократное автоподключение при первом пользовательском входе.
     *
     * Инициализация [init] больше не подключает адаптер сама: процесс может
     * быть поднят чтением read-only провайдера из DashGrid, и это не должно
     * открывать OBD. Экран, служба или виджет вызывают этот метод, когда
     * пользователь действительно открыл приложение.
     */
    @Synchronized
    fun autoConnectOnce() {
        if (!initialized || autoConnectOffered) return
        autoConnectOffered = true
        if (_state.value.connection == ConnectionKind.CONNECTED ||
            _state.value.connection == ConnectionKind.CONNECTING
        ) return
        val config = AdapterStore.load(app) ?: return
        if (AdapterStore.autoConnect(app)) connect(config)
    }

    fun connect(config: AdapterConfig) {
        if (!initialized) return
        connectJob?.cancel()
        _state.update {
            it.copy(
                connection = ConnectionKind.CONNECTING,
                connectionLabel = "Подключение",
                message = config.label,
                adapterVersion = "",
                voltage = "",
            )
        }
        connectJob = scope.launch {
            var opening: Elm327? = null
            try {
                stopEngineLocked()
                val transport = openTransport(config)
                val elm = Elm327(transport)
                opening = elm
                elm.connect()
                elm.init()
                val (version, voltage) = elm.identify()
                installLocked(
                    Elm327SignalSource(elm),
                    ConnectionKind.CONNECTED,
                    config.label,
                    "$version · $voltage",
                    version = version,
                    voltage = voltage,
                )
                AdapterHolder.attach(elm, config.label)
                AdapterStore.save(app, config)
                startService()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Недооткрытый канал надо закрыть, иначе повторная попытка
                // упрётся в занятый последовательный порт.
                runCatching { opening?.close() }
                // Следующий вход в приложение должен попробовать снова:
                // иначе одна неудачная попытка навсегда оставляет ДЕМО.
                autoConnectOffered = false
                _state.update {
                    it.copy(
                        connection = ConnectionKind.ERROR,
                        connectionLabel = "Ошибка",
                        message = error.message ?: "не удалось подключиться",
                        adapterVersion = "",
                        voltage = "",
                    )
                }
            }
        }
    }

    /**
     * Принимает уже открытый адаптер от диагностического экрана.
     *
     * В приложении один ELM327 и один последовательный канал. Диагностика
     * исторически открывала своё соединение; теперь она отдаёт его сюда,
     * и приборка, виджет и автомобильный экран сразу получают живые данные
     * вместо ДЕМО. Второго соединения не появляется.
     */
    @Synchronized
    fun adopt(adapter: Elm327, label: String, version: String, voltage: String) {
        if (!initialized) return
        autoConnectOffered = true
        connectJob?.cancel()
        installLocked(
            Elm327SignalSource(adapter),
            ConnectionKind.CONNECTED,
            label,
            listOf(version, voltage).filter { it.isNotBlank() }.joinToString(" · "),
            version = version,
            voltage = voltage,
        )
        AdapterHolder.attach(adapter, label)
        startService()
    }

    /** Готов ли общий адаптер к диагностике. */
    fun hasAdapter(): Boolean = initialized && AdapterHolder.elm != null

    @Synchronized
    fun useDemo() {
        if (!initialized) return
        autoConnectOffered = true
        connectJob?.cancel()
        AdapterStore.setAutoConnect(app, false)
        installLocked(DemoSignalSource(), ConnectionKind.DEMO, "ДЕМО", "")
        AdapterHolder.detach()
        _state.update {
            it.copy(
                message = "Демонстрационные данные, не автомобиль",
                adapterVersion = "",
                voltage = "",
            )
        }
    }

    @Synchronized
    fun disconnect() {
        if (!initialized) return
        autoConnectOffered = true
        connectJob?.cancel()
        AdapterStore.setAutoConnect(app, false)
        valuesJob?.cancel()
        valuesJob = null
        engine?.stop()
        engine?.close()
        engine = null
        source = null
        AdapterHolder.detach()
        _state.update {
            it.copy(
                connection = ConnectionKind.DISCONNECTED,
                connectionLabel = "Нет связи",
                message = "Адаптер отключён",
                adapterVersion = "",
                voltage = "",
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
        engine?.setBackground(profileWatched())
    }

    fun setCatalogEnabled(enabled: Boolean) {
        catalogEnabled = enabled
        engine?.setCatalogEnabled(enabled)
    }

    /**
     * Запасные источники показателей: запасной читается, только пока
     * основной молчит. Соответствие строит приборка из [MetricSpec].
     */
    fun setFallbacks(map: Map<String, String>) {
        fallbacks = map
        engine?.setFallbacks(map)
    }

    /** Сводка производительности текущей (или последней) сессии. */
    fun diagnosticsSnapshot(): TelemetryMetrics.Snapshot? =
        (engine?.metrics ?: lastMetrics)?.snapshot(MonotonicClock.now())

    /** Журнал опроса для экспорта. */
    fun diagnosticsEvents(): List<TelemetryMetrics.Event> =
        (engine?.metrics ?: lastMetrics)?.events().orEmpty()

    /** Состояние очереди опроса: приоритеты, интервалы, отказы. */
    fun scheduleSnapshot(): List<TelemetryScheduler.Snapshot> =
        engine?.schedule().orEmpty()

    /**
     * Экспортирует журнал производительности в JSON и возвращает файл.
     * Второй экземпляр диагностики не создаётся: это снимок того, что
     * движок уже посчитал.
     */
    fun exportDiagnostics(context: Context): java.io.File? {
        if (!initialized) return null
        return TelemetryDiagnostics.export(
            context = context,
            source = engine?.sourceLabel ?: _state.value.connectionLabel,
            connection = _state.value,
            metrics = diagnosticsSnapshot(),
            schedule = scheduleSnapshot(),
            events = diagnosticsEvents(),
        )
    }

    /** Какие сигналы опрашивать, пока открыт каталог. */
    fun setCatalog(ids: Collection<String>) {
        engine?.setCatalog(ids)
    }

    @Synchronized
    fun saveProfile(profile: VehicleProfile) {
        if (!initialized) return
        VehicleStore.save(app, profile)
        _state.update { it.copy(profile = profile) }
        watches["profile"] = profile.signalIds
        engine?.setDashboard(allWatched())
        engine?.setBackground(profileWatched())
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
        AdapterHolder.detach()
        _state.update { it.copy(values = emptyMap()) }
    }

    @Synchronized
    private fun installLocked(
        newSource: SignalSource,
        kind: ConnectionKind,
        label: String,
        message: String,
        version: String = "",
        voltage: String = "",
    ) {
        valuesJob?.cancel()
        valuesJob = null
        engine?.let { lastMetrics = it.metrics }
        engine?.stop()
        engine?.close()
        source = newSource
        val newEngine = TelemetryEngine(newSource, scope)
        engine = newEngine
        newEngine.setDashboard(allWatched())
        newEngine.setBackground(profileWatched())
        newEngine.setCatalogEnabled(catalogEnabled)
        newEngine.setFallbacks(fallbacks)
        valuesJob = scope.launch {
            newEngine.values.collect { values ->
                if (values.isNotEmpty()) {
                    _state.update {
                        it.copy(values = values, lastUpdateMs = MonotonicClock.now())
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
                adapterVersion = version,
                voltage = voltage,
                values = if (kind == ConnectionKind.DEMO) it.values else emptyMap(),
            )
        }
    }

    private fun allWatched(): List<String> = synchronized(watches) {
        val cockpit = watches["cockpit"].orEmpty()
        // Когда приборка открыта, профиль уходит в фон и не отбирает шину.
        // Когда её нет, профиль — единственный источник данных для виджета.
        val profile = watches["profile"].orEmpty()
        if (cockpit.isEmpty()) (cockpit + profile).distinct() else cockpit.toList()
    }

    /** Фон: профиль (виджет) и запасные источники приборки. */
    private fun profileWatched(): List<String> = synchronized(watches) {
        val cockpit = watches["cockpit"].orEmpty()
        val profile = if (cockpit.isEmpty()) emptyList() else watches["profile"].orEmpty()
        (profile + watches["cockpit-alt"].orEmpty()).distinct()
    }
}
