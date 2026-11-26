package io.github.nchalexz.aaperfmon.settings

import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import io.github.nchalexz.aaperfmon.cockpit.CockpitPrefs
import io.github.nchalexz.aaperfmon.telemetry.AdapterConfig
import io.github.nchalexz.aaperfmon.telemetry.AdapterStore
import io.github.nchalexz.aaperfmon.telemetry.ConnectionKind
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.SignalValue
import io.github.nchalexz.aaperfmon.telemetry.Telemetry
import io.github.nchalexz.aaperfmon.telemetry.VehicleProfile
import io.github.nchalexz.aaperfmon.telemetry.VehicleProfiles
import io.github.nchalexz.aaperfmon.telemetry.VehicleStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DeviceOption(val id: String, val label: String, val type: String)

data class SettingsForm(
    val adapterType: String = "wifi",
    val host: String = "192.168.0.10",
    val port: String = "35000",
    val devices: List<DeviceOption> = emptyList(),
    val selectedDevice: String = "",
    val profile: VehicleProfile = VehicleProfiles.mqb,
    val message: String = "",
)

data class SettingsUiState(
    val form: SettingsForm = SettingsForm(),
    val connection: ConnectionKind = ConnectionKind.DEMO,
    val connectionLabel: String = "ДЕМО",
    val values: Map<String, SignalValue> = emptyMap(),
)

/**
 * Логика экрана настроек: подключение к адаптеру, выбор автомобиля и
 * набор сохраняемых параметров. Соединение не открывается здесь напрямую —
 * только через общий [Telemetry].
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val _form = MutableStateFlow(loadForm())
    val form: StateFlow<SettingsForm> = _form.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private var pendingUsb: UsbDevice? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                _message.value = "Доступ к USB получен, подключаюсь"
                connectUsb(device)
            } else {
                _message.value = "USB-доступ не выдан"
            }
            pendingUsb = null
        }
    }

    init {
        Telemetry.init(app)
        CockpitPrefs.init(app)
        ContextCompat.registerReceiver(
            app,
            usbReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        loadDevices()
    }

    fun setAdapterType(type: String) {
        _form.update { it.copy(adapterType = type) }
        loadDevices()
    }

    fun setHost(value: String) = _form.update { it.copy(host = value) }

    fun setPort(value: String) = _form.update { it.copy(port = value) }

    fun selectDevice(id: String) = _form.update { it.copy(selectedDevice = id) }

    fun connect() {
        val form = _form.value
        when (form.adapterType) {
            "wifi" -> {
                val port = form.port.toIntOrNull() ?: 35000
                if (form.host.isBlank()) {
                    _message.value = "Укажите адрес Wi-Fi-адаптера"
                    return
                }
                _message.value = "Подключение к ${form.host}:$port"
                Telemetry.connect(AdapterConfig.Wifi(form.host.trim(), port))
            }
            "bluetooth" -> {
                val device = form.devices.firstOrNull { it.id == form.selectedDevice }
                if (device == null) {
                    _message.value = "Выберите Bluetooth-адаптер"
                    return
                }
                _message.value = "Подключение к ${device.label}"
                Telemetry.connect(AdapterConfig.Bluetooth(device.id, device.label))
            }
            "usb" -> {
                val manager = app.getSystemService(UsbManager::class.java)
                val device = manager?.deviceList?.values
                    ?.firstOrNull { it.deviceName == form.selectedDevice }
                if (device == null) {
                    _message.value = "USB-адаптер не найден"
                    return
                }
                if (!manager.hasPermission(device)) {
                    pendingUsb = device
                    val pi = PendingIntent.getBroadcast(
                        app,
                        0,
                        Intent(ACTION_USB_PERMISSION).setPackage(app.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    manager.requestPermission(device, pi)
                    _message.value = "Подтвердите доступ к USB"
                    return
                }
                connectUsb(device)
            }
        }
    }

    private fun connectUsb(device: UsbDevice) {
        val label = runCatching { device.productName }.getOrNull() ?: "USB"
        _message.value = "Подключение к $label"
        Telemetry.connect(AdapterConfig.Usb(device.deviceName, label))
    }

    fun useDemo() {
        Telemetry.useDemo()
        _message.value = "Включён демонстрационный режим"
    }

    fun disconnect() {
        Telemetry.disconnect()
        _message.value = "Адаптер отключён"
    }

    fun loadDevices() {
        val list = mutableListOf<DeviceOption>()
        when (_form.value.adapterType) {
            "bluetooth" -> {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                runCatching {
                    adapter?.bondedDevices?.forEach { device ->
                        val name = runCatching { device.name }.getOrNull() ?: device.address
                        list += DeviceOption(device.address, name, "bluetooth")
                    }
                }
            }
            "usb" -> {
                val manager = app.getSystemService(UsbManager::class.java)
                manager?.deviceList?.values?.forEach { device ->
                    val name = runCatching { device.productName }.getOrNull()
                        ?: "USB ${device.deviceId}"
                    list += DeviceOption(device.deviceName, name, "usb")
                }
            }
        }
        _form.update {
            it.copy(
                devices = list,
                selectedDevice = if (it.selectedDevice in list.map { d -> d.id }) {
                    it.selectedDevice
                } else {
                    list.firstOrNull()?.id.orEmpty()
                },
            )
        }
    }

    // -- профиль ------------------------------------------------------------

    fun selectBuiltIn(id: String) {
        val profile = VehicleProfiles.byId(id).copy(
            name = VehicleProfiles.byId(id).name,
            builtIn = true,
        )
        _form.update { it.copy(profile = profile) }
    }

    fun updateName(value: String) = updateProfile { it.copy(name = value, builtIn = false) }

    fun updatePlatform(value: String) = updateProfile { it.copy(platform = value, builtIn = false) }

    fun updateEngine(value: String) = updateProfile { it.copy(engine = value, builtIn = false) }

    fun updateTransmission(value: String) = updateProfile { it.copy(transmission = value, builtIn = false) }

    fun updateVin(value: String) = updateProfile { it.copy(vin = value, builtIn = false) }

    fun toggleSignal(id: String) = updateProfile {
        val next = it.signalIds.toMutableSet()
        if (!next.add(id)) next.remove(id)
        it.copy(signalIds = next, builtIn = false)
    }

    fun setSignals(ids: Set<String>) = updateProfile {
        it.copy(signalIds = ids, builtIn = false)
    }

    fun saveProfile() {
        val profile = _form.value.profile
        if (profile.name.isBlank()) {
            _message.value = "У профиля должно быть название"
            return
        }
        Telemetry.saveProfile(profile)
        _message.value = "Профиль сохранён: ${profile.name}"
    }

    fun resetProfile() {
        val profile = VehicleStore.reset(app)
        _form.update { it.copy(profile = profile) }
        Telemetry.saveProfile(profile)
        _message.value = "Профиль сброшен на VAG MQB"
    }

    fun clearMessage() {
        _message.value = ""
    }

    private fun updateProfile(block: (VehicleProfile) -> VehicleProfile) {
        _form.update { it.copy(profile = block(it.profile)) }
    }

    private fun loadForm(): SettingsForm {
        val config = AdapterStore.load(app)
        return SettingsForm(
            adapterType = when (config) {
                is AdapterConfig.Wifi -> "wifi"
                is AdapterConfig.Bluetooth -> "bluetooth"
                is AdapterConfig.Usb -> "usb"
                null -> "wifi"
            },
            host = (config as? AdapterConfig.Wifi)?.host ?: "192.168.0.10",
            port = ((config as? AdapterConfig.Wifi)?.port ?: 35000).toString(),
            selectedDevice = when (config) {
                is AdapterConfig.Bluetooth -> config.address
                is AdapterConfig.Usb -> config.deviceName
                else -> ""
            },
            profile = VehicleStore.load(app),
        )
    }

    override fun onCleared() {
        runCatching { app.unregisterReceiver(usbReceiver) }
        super.onCleared()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "io.github.nchalexz.aaperfmon.USB_PERMISSION"
    }
}
