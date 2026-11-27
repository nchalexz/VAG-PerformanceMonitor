package io.github.nchalexz.aaperfmon.cockpit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nchalexz.aaperfmon.cockpit.UiLanguage
import io.github.nchalexz.aaperfmon.telemetry.ReadingState
import io.github.nchalexz.aaperfmon.telemetry.SignalValue

/**
 * Каталог всех сигналов поверх приборки. Не третий экран навигации:
 * открывается из угла и закрывается, не занимая место постоянно.
 * Пока он открыт, движок опрашивает каталог по кругу.
 */
@Composable
fun CatalogOverlay(
    values: Map<String, SignalValue>,
    now: Long,
    canShowData: Boolean,
    language: UiLanguage,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDashboardColors.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<DisplayCategory?>(null) }
    val all = MetricRegistry.all()
    val needle = query.trim().lowercase()
    val filtered = all.filter { spec ->
        (category == null || spec.category == category) &&
            (needle.isEmpty() ||
                spec.labelEn.lowercase().contains(needle) ||
                spec.labelRu.lowercase().contains(needle) ||
                spec.signalId.lowercase().contains(needle) ||
                spec.unitEn.lowercase().contains(needle))
    }
    val sampled = values.count { it.value.state == ReadingState.OK }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                language.pick("Data catalog", "Каталог данных"),
                color = colors.text,
                fontFamily = DashboardFont,
                fontSize = 17.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "$sampled / ${all.size}",
                color = colors.dim,
                fontFamily = DashboardFont,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    language.pick("Close", "Закрыть"),
                    tint = colors.dim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Rounded.Search, null, tint = colors.dim, modifier = Modifier.size(18.dp))
            },
            placeholder = {
                Text(
                    language.pick("Search parameter", "Поиск параметра"),
                    fontSize = 13.sp,
                    color = colors.dim,
                )
            },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = colors.text),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.border,
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                cursorColor = colors.accent,
            ),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CatalogChip(
                text = language.pick("All", "Все"),
                selected = category == null,
                onClick = { category = null },
            )
            DisplayCategory.entries.forEach { item ->
                CatalogChip(
                    text = item.label(language),
                    selected = category == item,
                    onClick = { category = if (category == item) null else item },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.signalId }) { spec ->
                val definition = MetricRegistry.definition(spec)
                val reading = readMetric(spec, values, now, canShowData)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            spec.label(language),
                            color = colors.text,
                            fontFamily = DashboardFont,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${definition.requestHex} · ${protocolLabel(definition.protocol, language)} · ${ecuLabel(definition.ecu, language)}",
                            color = colors.dim,
                            fontFamily = DashboardFont,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (reading.isOk) {
                                "${reading.text} ${spec.unit(language)}".trim()
                            } else {
                                // «Нет данных» и «ещё не прочитан» — разные
                                // вещи: N/A вместо прочерка честнее.
                                reading.text
                            },
                            color = when {
                                !reading.isOk -> colors.dim
                                spec.criticalValue(reading.display) -> colors.critical
                                spec.warningValue(reading.display) -> colors.warning
                                else -> colors.text
                            },
                            fontFamily = DashboardFont,
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                        Text(
                            if (reading.isOk) {
                                verificationLabel(definition.verification, language)
                            } else {
                                reading.state.note(language)
                            },
                            color = colors.dim,
                            fontFamily = DashboardFont,
                            fontSize = 9.sp,
                            maxLines = 1,
                        )
                    }
                }
                HorizontalDivider(color = colors.border, thickness = 0.5.dp)
            }
        }
    }
}

private fun protocolLabel(protocol: io.github.nchalexz.aaperfmon.telemetry.Protocol, language: UiLanguage): String =
    when (protocol) {
        io.github.nchalexz.aaperfmon.telemetry.Protocol.OBD2 -> "OBD-II"
        io.github.nchalexz.aaperfmon.telemetry.Protocol.UDS -> "UDS"
        io.github.nchalexz.aaperfmon.telemetry.Protocol.KWP2000 -> "KWP2000"
        io.github.nchalexz.aaperfmon.telemetry.Protocol.VIRTUAL -> language.pick("Virtual", "Виртуальный")
    }

private fun ecuLabel(ecu: io.github.nchalexz.aaperfmon.telemetry.Ecu, language: UiLanguage): String {
    if (language == UiLanguage.RU) return ecu.labelRu
    return when (ecu) {
        io.github.nchalexz.aaperfmon.telemetry.Ecu.ENGINE -> "Engine"
        io.github.nchalexz.aaperfmon.telemetry.Ecu.TRANSMISSION -> "Transmission"
        io.github.nchalexz.aaperfmon.telemetry.Ecu.ABS -> "ABS / ESP"
        io.github.nchalexz.aaperfmon.telemetry.Ecu.CLIMATE -> "Climate"
        io.github.nchalexz.aaperfmon.telemetry.Ecu.BODY -> "Body electronics"
        io.github.nchalexz.aaperfmon.telemetry.Ecu.ACC -> "Adaptive cruise"
        io.github.nchalexz.aaperfmon.telemetry.Ecu.SUSPENSION -> "Suspension"
        io.github.nchalexz.aaperfmon.telemetry.Ecu.CLUSTER -> "Instrument cluster"
        io.github.nchalexz.aaperfmon.telemetry.Ecu.GATEWAY -> "CAN gateway"
        io.github.nchalexz.aaperfmon.telemetry.Ecu.AWD -> "All-wheel drive"
        io.github.nchalexz.aaperfmon.telemetry.Ecu.STEERING -> "Steering assist"
        io.github.nchalexz.aaperfmon.telemetry.Ecu.INFOTAINMENT -> "Infotainment"
        io.github.nchalexz.aaperfmon.telemetry.Ecu.UNKNOWN -> "Unknown"
    }
}

private fun verificationLabel(
    verification: io.github.nchalexz.aaperfmon.telemetry.Verification,
    language: UiLanguage,
): String {
    if (language == UiLanguage.RU) return verification.labelRu
    return when (verification) {
        io.github.nchalexz.aaperfmon.telemetry.Verification.DESCRIBED -> "described"
        io.github.nchalexz.aaperfmon.telemetry.Verification.DISCOVERED -> "discovered"
        io.github.nchalexz.aaperfmon.telemetry.Verification.READ -> "read"
        io.github.nchalexz.aaperfmon.telemetry.Verification.DECODED -> "decoded"
        io.github.nchalexz.aaperfmon.telemetry.Verification.CONFIRMED -> "confirmed on car"
    }
}

@Composable
private fun CatalogChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalDashboardColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) colors.accent else colors.border,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            color = if (selected) colors.accent else colors.dim,
            fontFamily = DashboardFont,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}
