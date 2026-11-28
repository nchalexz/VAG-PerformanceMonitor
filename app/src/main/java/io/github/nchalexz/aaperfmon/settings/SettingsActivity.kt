package io.github.nchalexz.aaperfmon.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nchalexz.aaperfmon.BuildConfig
import io.github.nchalexz.aaperfmon.telemetry.ConnectionKind
import io.github.nchalexz.aaperfmon.cockpit.CockpitPrefs
import io.github.nchalexz.aaperfmon.cockpit.LanguageMode
import io.github.nchalexz.aaperfmon.cockpit.ThemeMode
import io.github.nchalexz.aaperfmon.telemetry.MonotonicClock
import io.github.nchalexz.aaperfmon.telemetry.SignalStatus
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.SignalValue
import io.github.nchalexz.aaperfmon.telemetry.Telemetry
import io.github.nchalexz.aaperfmon.telemetry.TelemetryMetrics
import io.github.nchalexz.aaperfmon.telemetry.VehicleProfile
import io.github.nchalexz.aaperfmon.telemetry.VehicleProfiles
import io.github.nchalexz.aaperfmon.ui.BgColor
import io.github.nchalexz.aaperfmon.ui.Card
import io.github.nchalexz.aaperfmon.ui.DarkScheme
import io.github.nchalexz.aaperfmon.ui.DimColor
import io.github.nchalexz.aaperfmon.ui.TextColor
import io.github.nchalexz.aaperfmon.ui.WarnColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {

    /**
     * Настройки можно открыть прямо из приборки, минуя главный экран,
     * поэтому разрешения на Bluetooth запрашиваются и здесь. Иначе первое
     * же подключение к адаптеру падало бы с SecurityException.
     */
    private val permissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }.toTypedArray()

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askPermissions.launch(permissions)
        setContent { SettingsScreen(onClose = { finish() }) }
    }
}

@Composable
fun SettingsScreen(onClose: () -> Unit, model: SettingsViewModel = viewModel()) {
    val form by model.form.collectAsState()
    val message by model.message.collectAsState()
    val telemetry by Telemetry.state.collectAsState()
    var pickerOpen by remember { mutableStateOf(false) }
    LaunchedEffect(telemetry.connection) {
        if (telemetry.connection == ConnectionKind.CONNECTED) model.clearMessage()
    }

    MaterialTheme(colorScheme = DarkScheme) {
        Column(
            Modifier.fillMaxSize().background(BgColor).safeDrawingPadding()
                .padding(horizontal = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Автомобиль и адаптер",
                    color = TextColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, "Закрыть", tint = DimColor)
                }
            }

            Column(Modifier.verticalScroll(rememberScrollState()).weight(1f)) {
                StatusCard(telemetry.connection, telemetry.connectionLabel, telemetry.message)
                CockpitSettingsCard()

                if (message.isNotBlank()) {
                    Text(
                        message,
                        color = WarnColor,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                Card("Адаптер ELM327") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "usb" to "USB")
                            .forEach { (type, label) ->
                                FilterChip(
                                    selected = form.adapterType == type,
                                    onClick = { model.setAdapterType(type) },
                                    label = { Text(label, fontSize = 12.sp) },
                                )
                            }
                    }
                    Spacer(Modifier.height(8.dp))
                    when (form.adapterType) {
                        "wifi" -> {
                            OutlinedTextField(
                                value = form.host,
                                onValueChange = model::setHost,
                                label = { Text("Адрес адаптера") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = form.port,
                                onValueChange = model::setPort,
                                label = { Text("Порт") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        "bluetooth", "usb" -> DevicePicker(
                            form = form,
                            onRefresh = model::loadDevices,
                            onSelect = model::selectDevice,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = model::connect) { Text("Подключиться") }
                        OutlinedButton(onClick = model::useDemo) { Text("Демо") }
                        OutlinedButton(onClick = model::disconnect) { Text("Стоп") }
                    }
                }

                Card("Профиль автомобиля") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        VehicleProfiles.builtIn.forEach { profile ->
                            FilterChip(
                                selected = form.profile.id == profile.id,
                                onClick = { model.selectBuiltIn(profile.id) },
                                label = { Text(profile.name, fontSize = 11.sp) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = form.profile.name,
                        onValueChange = model::updateName,
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = form.profile.platform,
                        onValueChange = model::updatePlatform,
                        label = { Text("Платформа") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = form.profile.engine,
                        onValueChange = model::updateEngine,
                        label = { Text("Двигатель") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = form.profile.transmission,
                        onValueChange = model::updateTransmission,
                        label = { Text("Коробка передач") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = form.profile.vin,
                        onValueChange = model::updateVin,
                        label = { Text("VIN (необязательно)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Параметров в профиле: ${form.profile.signalIds.size}",
                            color = DimColor,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { pickerOpen = true }) { Text("Выбрать") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = model::saveProfile) { Text("Сохранить профиль") }
                    TextButton(onClick = model::resetProfile) {
                        Text("Сбросить на VAG MQB", color = DimColor, fontSize = 12.sp)
                    }
                }

                DiagnosticsCard()

                Card("Сохранённые параметры") {
                    if (form.profile.signalIds.isEmpty()) {
                        Text("Профиль пуст — выберите параметры.", color = DimColor, fontSize = 12.sp)
                    } else {
                        val now = MonotonicClock.now()
                        form.profile.signalIds.sorted().forEach { id ->
                            SavedValueRow(
                                id = id,
                                value = telemetry.values[id],
                                now = now,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (pickerOpen) {
        SignalPickerDialog(
            initial = form.profile.signalIds,
            onDismiss = { pickerOpen = false },
            onConfirm = {
                model.setSignals(it)
                pickerOpen = false
            },
        )
    }
}

/**
 * Сводка производительности опроса и экспорт журнала.
 *
 * Числа обновляются раз в секунду с той же частотой, с какой их считает
 * движок: это диагностика, а не элемент приборки. Экспорт отдаёт JSON
 * соседнему приложению через системный «Поделиться»; VIN в файле нет.
 */
@Composable
private fun DiagnosticsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<TelemetryMetrics.Snapshot?>(null) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = Telemetry.diagnosticsSnapshot()
            delay(1_000)
        }
    }
    Card("Диагностика телеметрии") {
        val current = snapshot
        if (current == null || current.requests == 0L) {
            Text(
                "Опрос ещё не начат. Подключите адаптер и вернитесь сюда.",
                color = DimColor,
                fontSize = 12.sp,
            )
        } else {
            Text(
                "Запросов/с: ${current.requestRateHz} · средняя ${current.averageLatencyMs} мс · " +
                    "P95 ${current.p95LatencyMs} мс",
                color = TextColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "Успешных ${current.successPercent}% · таймаутов ${current.timeouts} · " +
                    "смен ECU ${current.ecuSwitches} · очередь ${current.queueDepth}",
                color = DimColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "Обновлено параметров: ${current.signals.size} · " +
                    "переходов в «устарело» ${current.staleTransitions}",
                color = DimColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !exporting,
                onClick = {
                    exporting = true
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { Telemetry.exportDiagnostics(context) }
                            message = if (file == null) {
                                "Не удалось экспортировать журнал"
                            } else {
                                runCatching {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${BuildConfig.APPLICATION_ID}.files",
                                        file,
                                    )
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Журнал телеметрии"))
                                    "Журнал: ${file.name}"
                                }.getOrElse { "Не удалось открыть журнал для отправки" }
                            }
                        } finally {
                            exporting = false
                        }
                    }
                },
            ) { Text("Экспорт журнала", fontSize = 11.sp) }
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(message, color = DimColor, fontSize = 10.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Журнал — технические числа: частоты, задержки, таймауты. VIN не пишется.",
            color = DimColor,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun CockpitSettingsCard() {
    val settings by CockpitPrefs.state.collectAsState()
    Card("Приборка") {
        Text("Тема", color = DimColor, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                ThemeMode.AUTO to "Авто",
                ThemeMode.DAY to "День",
                ThemeMode.NIGHT to "Ночь",
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = settings.themeMode == mode,
                    onClick = { CockpitPrefs.setThemeMode(mode) },
                    label = { Text(label, fontSize = 11.sp) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Язык подписей", color = DimColor, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                LanguageMode.SYSTEM to "Система",
                LanguageMode.ENGLISH to "English",
                LanguageMode.RUSSIAN to "Русский",
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = settings.languageMode == mode,
                    onClick = { CockpitPrefs.setLanguageMode(mode) },
                    label = { Text(label, fontSize = 11.sp) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "По умолчанию английский, на русской системе включается русский. " +
                "Тема «Авто» следует системной ночи/дню.",
            color = DimColor,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun StatusCard(connection: ConnectionKind, label: String, message: String) {
    Card("Состояние") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(8.dp).height(8.dp)
                    .background(
                        when (connection) {
                            ConnectionKind.CONNECTED -> Color(0xFF4ADE80)
                            ConnectionKind.DEMO -> WarnColor
                            ConnectionKind.ERROR -> Color(0xFFF87171)
                            ConnectionKind.CONNECTING -> Color(0xFF38BDF8)
                            ConnectionKind.DISCONNECTED -> DimColor
                        },
                        RoundedCornerShape(4.dp),
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, color = TextColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (message.isNotBlank()) {
            Text(message, color = DimColor, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun DevicePicker(
    form: SettingsForm,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = form.devices.firstOrNull { it.id == form.selectedDevice }
    // Пока список не открывали, показываем запомненный адаптер, а не
    // «Выберите устройство»: перечислять все спаренные устройства при
    // каждом входе в настройки не нужно.
    val label = selected?.label
        ?: form.savedDeviceLabel.takeIf { it.isNotBlank() }
        ?: form.selectedDevice.takeIf { it.isNotBlank() }
        ?: "Выберите устройство"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = {
                    onRefresh()
                    expanded = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (form.devices.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Ничего не найдено — нажмите «Обновить»", fontSize = 12.sp) },
                        onClick = { expanded = false },
                    )
                }
                form.devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.label, fontSize = 12.sp) },
                        onClick = {
                            onSelect(device.id)
                            expanded = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = onRefresh) { Text("Обновить", fontSize = 11.sp) }
    }
}

@Composable
private fun SavedValueRow(id: String, value: SignalValue?, now: Long) {
    val definition = SignalCatalog.get(id) ?: return
    val status = value?.statusAt(now, definition) ?: SignalStatus.NEVER_READ
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(definition.nameRu, color = DimColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            when (status) {
                SignalStatus.NEVER_READ -> "—"
                SignalStatus.STALE -> "${definition.format(value?.value)} ${definition.unit} · устарело"
                SignalStatus.VALID -> "${definition.format(value?.value)} ${definition.unit}"
            },
            color = if (status == SignalStatus.VALID) TextColor else DimColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SignalPickerDialog(
    initial: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    val filtered = SignalCatalog.all.filter {
        query.isBlank() ||
            it.nameRu.contains(query, ignoreCase = true) ||
            it.nameEn.contains(query, ignoreCase = true) ||
            it.id.contains(query, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Параметры профиля") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Поиск", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(filtered, key = { it.id }) { definition ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected = if (definition.id in selected) {
                                    selected - definition.id
                                } else {
                                    selected + definition.id
                                }
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = definition.id in selected,
                                onCheckedChange = {
                                    selected = if (definition.id in selected) {
                                        selected - definition.id
                                    } else {
                                        selected + definition.id
                                    }
                                },
                            )
                            Column {
                                Text(definition.nameRu, color = TextColor, fontSize = 13.sp)
                                Text(
                                    "${definition.id} · ${definition.unit}",
                                    color = DimColor,
                                    fontSize = 9.sp,
                                )
                            }
                        }
                    }
                }
                Text(
                    "Выбрано: ${selected.size}",
                    color = DimColor,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("Готово") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
