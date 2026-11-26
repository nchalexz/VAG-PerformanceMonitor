package io.github.nchalexz.aaperfmon.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.Telemetry
import io.github.nchalexz.aaperfmon.telemetry.VehicleStore
import io.github.nchalexz.aaperfmon.ui.BgColor
import io.github.nchalexz.aaperfmon.ui.Card
import io.github.nchalexz.aaperfmon.ui.DarkScheme
import io.github.nchalexz.aaperfmon.ui.DimColor
import io.github.nchalexz.aaperfmon.ui.TextColor

/**
 * Конфигуратор экземпляра виджета. Хост передаёт appWidgetId, здесь
 * выбираются заголовок и до четырёх параметров. Результат возвращается
 * только после сохранения — иначе Android удалит экземпляр.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(RESULT_CANCELED)
        enableEdgeToEdge()
        setContent {
            WidgetConfigScreen(
                widgetId = widgetId,
                onSave = { save(it) },
                onDone = { finish() },
            )
        }
    }

    private fun save(config: WidgetConfig) {
        WidgetStore.save(this, widgetId, config)
        WidgetPublisher.publishOne(this, widgetId)
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
        )
        finish()
    }
}

@Composable
private fun WidgetConfigScreen(
    widgetId: Int,
    onSave: (WidgetConfig) -> Unit,
    onDone: () -> Unit,
) {
    val telemetry by Telemetry.state.collectAsState()
    val context = LocalContext.current
    val initial = remember(widgetId) {
        WidgetStore.load(context, widgetId)
            ?: WidgetStore.defaultFor(VehicleStore.load(context))
    }
    var title by remember { mutableStateOf(initial.title) }
    var signals by remember { mutableStateOf(initial.signalIds.take(4)) }
    var editorSlot by remember { mutableStateOf<Int?>(null) }

    MaterialTheme(colorScheme = DarkScheme) {
        Column(
            Modifier.fillMaxSize().background(BgColor).safeDrawingPadding()
                .padding(horizontal = 14.dp),
        ) {
            Text(
                "Настройка виджета",
                color = TextColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            Column(Modifier.verticalScroll(rememberScrollState()).weight(1f)) {
                Card("Заголовок") {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Card("Показатели (до четырёх)") {
                    signals.forEachIndexed { index, id ->
                        val definition = SignalCatalog.get(id)
                        Row(
                            Modifier.fillMaxWidth().clickable { editorSlot = index }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                color = DimColor,
                                fontSize = 12.sp,
                                modifier = Modifier.width(20.dp),
                            )
                            Text(
                                definition?.nameRu ?: "Не выбран",
                                color = TextColor,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                definition?.unit.orEmpty(),
                                color = DimColor,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    OutlinedButton(onClick = { editorSlot = signals.size }) {
                        Text("Добавить показатель")
                    }
                    Text(
                        "Светлая и тёмная тема — по системной теме. Размер и форма — от хоста.",
                        color = DimColor,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                WidgetPreview(title, signals, telemetry.values)

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val config = WidgetConfig(
                            title = title.ifBlank { "Телеметрия" },
                            signalIds = signals.ifEmpty {
                                WidgetStore.defaultFor(VehicleStore.load(context)).signalIds
                            },
                        )
                        onSave(config)
                    }) { Text("Сохранить") }
                    OutlinedButton(onClick = onDone) { Text("Отмена") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    editorSlot?.let { slot ->
        SingleSignalDialog(
            current = signals.getOrNull(slot),
            onDismiss = { editorSlot = null },
            onPick = { id ->
                val next = signals.toMutableList()
                if (slot < next.size) next[slot] = id else next += id
                signals = next.take(4)
                editorSlot = null
            },
        )
    }
}

@Composable
private fun WidgetPreview(
    title: String,
    signals: List<String>,
    values: Map<String, io.github.nchalexz.aaperfmon.telemetry.SignalValue>,
) {
    val now = System.currentTimeMillis()
    Card("Предпросмотр") {
        Column(
            Modifier.fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color(0xFF101418), RoundedCornerShape(14.dp))
                .padding(12.dp),
        ) {
            Row {
                Text(title, color = TextColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("ДЕМО", color = DimColor, fontSize = 9.sp)
            }
            Spacer(Modifier.height(6.dp))
            signals.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { id ->
                        val definition = SignalCatalog.get(id)
                        val value = values[id]
                        val stale = definition == null || value == null ||
                            value.isStale(now, definition)
                        Column(Modifier.weight(1f).padding(4.dp)) {
                            Text(
                                definition?.nameRu.orEmpty().uppercase(),
                                color = DimColor,
                                fontSize = 8.sp,
                                maxLines = 1,
                            )
                            Text(
                                if (definition == null || stale) "—"
                                else definition.format(value.value),
                                color = TextColor,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(definition?.unit.orEmpty(), color = DimColor, fontSize = 8.sp)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SingleSignalDialog(
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = SignalCatalog.all.filter {
        query.isBlank() ||
            it.nameRu.contains(query, true) ||
            it.nameEn.contains(query, true) ||
            it.id.contains(query, true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Показатель") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Поиск", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.id }) { definition ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(definition.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                definition.nameRu,
                                color = if (definition.id == current) TextColor else DimColor,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(definition.unit, color = DimColor, fontSize = 10.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
