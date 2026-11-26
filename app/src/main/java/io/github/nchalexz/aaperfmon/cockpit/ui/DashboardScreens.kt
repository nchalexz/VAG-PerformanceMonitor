package io.github.nchalexz.aaperfmon.cockpit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.nchalexz.aaperfmon.cockpit.UiLanguage
import io.github.nchalexz.aaperfmon.telemetry.SignalValue

/**
 * Главный экран: три прибора и пять плиток, без панели категорий.
 * Именно этот экран открывается по умолчанию и соответствует второму макету.
 */
@Composable
fun DashboardScreen(
    values: Map<String, SignalValue>,
    now: Long,
    canShowData: Boolean,
    language: UiLanguage,
    onGaugeClick: (MetricSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val columns = when {
            maxWidth >= 700.dp -> 5
            maxWidth >= 500.dp -> 3
            else -> 2
        }
        val portraitPanelHeight = maxWidth * 0.44f
        val tileRowHeight = ((maxWidth - 6.dp * (columns - 1)) / columns) * 0.48f
        Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp)) {
            GaugePanel(
                gauges = MetricRegistry.gauges,
                values = values,
                now = now,
                canShowData = canShowData,
                language = language,
                onGaugeClick = onGaugeClick,
                modifier = if (portrait) {
                    Modifier.fillMaxWidth().height(portraitPanelHeight)
                } else {
                    Modifier.fillMaxWidth().weight(1f)
                },
            )
            Spacer(Modifier.height(8.dp))
            TileRows(
                specs = MetricRegistry.mainTiles,
                columns = columns,
                values = values,
                now = now,
                canShowData = canShowData,
                language = language,
                rowHeight = if (portrait) {
                    portraitPanelHeight * 0.82f
                } else {
                    tileRowHeight
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (portrait) Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Расширенный экран: те же три прибора меньше, панель категорий и
 * адаптивная сетка. Помещается пять колонок на широком экране и меньше
 * на узком — без обрезания значений.
 */
@Composable
fun ExtendedDashboardScreen(
    values: Map<String, SignalValue>,
    now: Long,
    canShowData: Boolean,
    language: UiLanguage,
    categories: List<DisplayCategory>,
    selected: DisplayCategory,
    includeVirtual: Boolean,
    onCategory: (DisplayCategory) -> Unit,
    onGaugeClick: (MetricSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val portraitPanelHeight = maxWidth * 0.44f
        Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp)) {
            GaugePanel(
                gauges = MetricRegistry.gauges,
                values = values,
                now = now,
                canShowData = canShowData,
                language = language,
                onGaugeClick = onGaugeClick,
                modifier = if (portrait) {
                    Modifier.fillMaxWidth().height(portraitPanelHeight)
                } else {
                    Modifier.fillMaxWidth().weight(0.50f)
                },
            )
            Spacer(Modifier.height(4.dp))
            CategoryTabBar(
                categories = categories,
                selected = selected,
                language = language,
                onSelect = onCategory,
            )
            Spacer(Modifier.height(6.dp))
            MetricGrid(
                specs = MetricRegistry.category(selected, includeVirtual = includeVirtual),
                values = values,
                now = now,
                canShowData = canShowData,
                language = language,
                modifier = if (portrait) {
                    Modifier.fillMaxWidth().weight(1f)
                } else {
                    Modifier.fillMaxWidth().weight(0.50f)
                },
            )
        }
    }
}

@Composable
private fun GaugePanel(
    gauges: List<MetricSpec>,
    values: Map<String, SignalValue>,
    now: Long,
    canShowData: Boolean,
    language: UiLanguage,
    onGaugeClick: (MetricSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDashboardColors.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .clip(shape)
            .background(colors.panel)
            .border(1.dp, colors.border.copy(alpha = 0.7f), shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        gauges.forEach { spec ->
            CircularGauge(
                spec = spec,
                reading = readMetric(
                    spec = spec,
                    values = values,
                    now = now,
                    canShowData = canShowData,
                ),
                language = language,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = { onGaugeClick(spec) },
            )
        }
    }
}

@Composable
private fun TileRows(
    specs: List<MetricSpec>,
    columns: Int,
    values: Map<String, SignalValue>,
    now: Long,
    canShowData: Boolean,
    language: UiLanguage,
    rowHeight: androidx.compose.ui.unit.Dp? = null,
    modifier: Modifier = Modifier,
) {
    val rows = specs.chunked(columns)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(
                (if (rowHeight != null) Modifier.height(rowHeight) else Modifier.weight(1f))
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { spec ->
                    MetricTile(
                        spec = spec,
                        reading = readMetric(
                            spec = spec,
                            values = values,
                            now = now,
                            canShowData = canShowData,
                        ),
                        language = language,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun MetricGrid(
    specs: List<MetricSpec>,
    values: Map<String, SignalValue>,
    now: Long,
    canShowData: Boolean,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val columns = (maxWidth.value / 236f).toInt().coerceIn(2, 5)
        // Две полные строки должны влезать в видимую область: так на макете.
        val tileHeight = ((maxHeight - 8.dp) / 2f).coerceIn(88.dp, 220.dp)
        if (specs.isEmpty()) {
            EmptyCategory(language, Modifier.fillMaxSize())
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(specs, key = { it.signalId }) { spec ->
                    MetricTile(
                        spec = spec,
                        reading = readMetric(
                            spec = spec,
                            values = values,
                            now = now,
                            canShowData = canShowData,
                        ),
                        language = language,
                        modifier = Modifier.fillMaxWidth().height(tileHeight),
                    )
                }
            }
        }
    }
}
