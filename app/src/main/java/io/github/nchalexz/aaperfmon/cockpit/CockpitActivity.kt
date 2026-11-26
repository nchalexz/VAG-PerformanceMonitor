package io.github.nchalexz.aaperfmon.cockpit

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.OilBarrel
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nchalexz.aaperfmon.R
import io.github.nchalexz.aaperfmon.settings.SettingsActivity
import io.github.nchalexz.aaperfmon.telemetry.DashboardSpec
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.SignalCategory
import io.github.nchalexz.aaperfmon.telemetry.SignalDefinition
import io.github.nchalexz.aaperfmon.telemetry.SignalValue
import io.github.nchalexz.aaperfmon.telemetry.WidgetKind
import io.github.nchalexz.aaperfmon.telemetry.WidgetSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Экран приборов; Activity ниже — только хост. */
class CockpitActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CockpitRoot() }
    }
}

// -- оформление -------------------------------------------------------------

private val Ink = Color.White
private val Bg = Color(0xFF080A0C)
private val Panel = Color(0xFF0E1216)
private val Dim = Color(0xFF98A1AB)
private val Hair = Color(0x22FFFFFF)
private val Red = Color(0xFFE5484D)
private val Amber = Color(0xFFF5B33C)
private val Green = Color(0xFF4ADE80)
private val Blue = Color(0xFF38BDF8)

private val CockpitScheme = darkColorScheme(
    primary = Blue,
    onPrimary = Color(0xFF06202E),
    background = Bg,
    surface = Panel,
    onSurface = Ink,
    surfaceVariant = Color(0xFF151B21),
    onSurfaceVariant = Dim,
    outline = Color(0xFF2A333D),
)

private val CockpitFont = FontFamily(
    Font(R.font.titillium_web_light, FontWeight.Light),
    Font(R.font.titillium_web_regular, FontWeight.Normal),
    Font(R.font.titillium_web_semibold, FontWeight.SemiBold),
)

// -- корень -----------------------------------------------------------------

@Composable
fun CockpitRoot(model: CockpitViewModel = viewModel()) {
    val state by model.state.collectAsState()
    var screen by remember { mutableStateOf(CockpitScreen.DRIVING) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<SignalCategory?>(null) }

    LaunchedEffect(screen) { model.selectScreen(screen) }
    val context = LocalContext.current

    MaterialTheme(colorScheme = CockpitScheme) {
        Column(
            Modifier.fillMaxSize().background(Bg).safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Header(
                state = state,
                onSettings = {
                    context.startActivity(android.content.Intent(context, SettingsActivity::class.java))
                },
                onDemo = model::useDemo,
                closeScreen = { (context as? android.app.Activity)?.finish() },
            )
            Spacer(Modifier.height(6.dp))
            ScreenTabs(current = screen, onSelect = { screen = it })
            Spacer(Modifier.height(8.dp))
            when (screen) {
                CockpitScreen.DRIVING -> DashboardView(
                    spec = model.dashboard(CockpitScreen.DRIVING)!!,
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                )
                CockpitScreen.PERFORMANCE -> DashboardView(
                    spec = model.dashboard(CockpitScreen.PERFORMANCE)!!,
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                )
                CockpitScreen.DATA -> LiveDataView(
                    state = state,
                    query = query,
                    onQuery = { query = it },
                    category = category,
                    onCategory = { category = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun Header(
    state: CockpitUiState,
    onSettings: () -> Unit,
    onDemo: () -> Unit,
    closeScreen: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "VAG Cockpit",
            color = Ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = CockpitFont,
        )
        Spacer(Modifier.width(10.dp))
        SourceBadge(state)
        Spacer(Modifier.weight(1f))
        if (state.live) {
            TextButton(onClick = onDemo) {
                Text("Демо", color = Dim, fontSize = 12.sp, fontFamily = CockpitFont)
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, "Настройки и автомобиль", tint = Dim)
        }
        IconButton(onClick = closeScreen) {
            Icon(Icons.Filled.Close, "Закрыть", tint = Dim)
        }
    }
}

@Composable
private fun SourceBadge(state: CockpitUiState) {
    val color = when {
        state.error.isNotBlank() -> Red
        state.live -> Green
        state.demo -> Amber
        else -> Dim
    }
    val label = state.sourceLabel
    Row(
        Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = color,
            fontSize = 10.sp,
            letterSpacing = 1.1.sp,
            fontFamily = CockpitFont,
        )
        if (!state.demo && state.channel.isNotBlank()) {
            Text(" · ${state.channel}", color = Dim, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ScreenTabs(current: CockpitScreen, onSelect: (CockpitScreen) -> Unit) {
    val labels = listOf(
        CockpitScreen.DRIVING to "Движение",
        CockpitScreen.PERFORMANCE to "Динамика",
        CockpitScreen.DATA to "Данные",
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        labels.forEach { (screen, label) ->
            val selected = screen == current
            Column(Modifier.padding(vertical = 2.dp).width(IntrinsicSize.Max)) {
                Text(
                    label,
                    color = if (selected) Ink else Dim,
                    fontSize = 14.sp,
                    fontFamily = CockpitFont,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(2.dp))
                        .clickable { onSelect(screen) },
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier.height(2.dp).fillMaxWidth()
                        .background(if (selected) Red else Color.Transparent),
                )
            }
        }
    }
}

// -- приборная панель -------------------------------------------------------

@Composable
private fun DashboardView(spec: DashboardSpec, state: CockpitUiState, modifier: Modifier) {
    val dials = spec.widgets.filter { it.kind == WidgetKind.DIAL }
    val tiles = spec.widgets.filter { it.kind == WidgetKind.TILE }
    BoxWithConstraints(modifier) {
        val width = maxWidth
        val portrait = width < maxHeight
        Column(Modifier.fillMaxSize()) {
            if (portrait) {
                Row(
                    Modifier.fillMaxWidth().height(width * 0.44f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dials.forEach { widget ->
                        DialWidget(widget, state, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    tiles.chunked(3).forEachIndexed { index, row ->
                        Row(
                            Modifier.fillMaxWidth().height(104.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            row.forEach { widget ->
                                TileWidget(widget, state, Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                        if (index < tiles.chunked(3).lastIndex) Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    PerformancePanel(state, Modifier.fillMaxWidth().weight(1f))
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dials.forEach { widget ->
                        DialWidget(widget, state, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().height(96.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tiles.forEach { widget ->
                        TileWidget(widget, state, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

private data class Presented(
    val text: String,
    val unit: String,
    val fraction: Float?,
    val warning: Boolean,
    val stale: Boolean,
    val stateNote: String,
)

private fun present(widget: WidgetSpec, state: CockpitUiState): Presented {
    val definition = SignalCatalog.get(widget.signalId)
        ?: return Presented("—", "", null, false, true, "нет в каталоге")
    val value = state.values[widget.signalId]
    val stale = value == null || value.isStale(state.now, definition)
    val number = if (stale) null else value?.value
    val fraction = number?.let {
        ((it - widget.min) / (widget.max - widget.min)).coerceIn(0.0, 1.0).toFloat()
    }
    val note = when {
        value == null -> "нет данных"
        stale -> "устарело"
        else -> ""
    }
    return Presented(
        text = if (number == null) "—" else definition.format(number),
        unit = definition.unit,
        fraction = fraction,
        warning = number != null && widget.warnFrom != null && number >= widget.warnFrom,
        stale = stale,
        stateNote = note,
    )
}

@Composable
private fun DialWidget(widget: WidgetSpec, state: CockpitUiState, modifier: Modifier) {
    val p = present(widget, state)
    val icon = when (widget.signalId) {
        SignalCatalog.Ids.COOLANT, SignalCatalog.Ids.OIL_TEMP -> Icons.Rounded.Thermostat
        SignalCatalog.Ids.BOOST, SignalCatalog.Ids.LOAD -> Icons.Rounded.Air
        else -> Icons.Rounded.OilBarrel
    }
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            val side = minOf(maxWidth, maxHeight)
            Box(Modifier.size(side).aspectRatio(1f), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawDial(widget, p)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        p.text,
                        color = if (p.warning) Red else if (p.stale) Dim else Ink,
                        fontFamily = CockpitFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = with(LocalDensity.current) { (side.toPx() * 0.21f).toSp() },
                        lineHeight = with(LocalDensity.current) { (side.toPx() * 0.21f).toSp() },
                        maxLines = 1,
                    )
                    Text(
                        if (p.unit.isNotEmpty()) p.unit else widget.title,
                        color = Dim,
                        fontSize = with(LocalDensity.current) { (side.toPx() * 0.065f).toSp() },
                        fontFamily = CockpitFont,
                        maxLines = 1,
                    )
                }
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Ink.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = side * 0.04f).size(side * 0.11f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            widget.title.uppercase(),
            color = Dim,
            fontSize = 10.sp,
            letterSpacing = 1.0.sp,
            fontFamily = CockpitFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Рисует шкалу. Вынесено из Composable, чтобы геометрия не зависела от
 * пересборки текста и её было проще держать в голове.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDial(
    widget: WidgetSpec,
    p: Presented,
) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val start = 135f
    val sweep = 270f
    fun angle(value: Float) = start + sweep * value.coerceIn(0f, 1f)
    fun at(len: Float, a: Float) = Offset(
        c.x + len * cos(a * PI.toFloat() / 180f),
        c.y + len * sin(a * PI.toFloat() / 180f),
    )

    drawCircle(Ink.copy(alpha = 0.45f), r - r * 0.014f, c, style = Stroke(r * 0.010f))
    for (i in 0..60) {
        val major = i % 10 == 0
        val a = start + sweep * i / 60f
        drawLine(
            Ink.copy(alpha = if (major) 0.72f else 0.24f),
            at(r * (if (major) 0.80f else 0.85f), a),
            at(r * 0.925f, a),
            strokeWidth = r * (if (major) 0.013f else 0.006f),
            cap = StrokeCap.Round,
        )
    }

    val track = Offset(c.x - r * 0.95f, c.y - r * 0.95f)
    val trackSize = Size(r * 1.9f, r * 1.9f)
    widget.warnFrom?.let { threshold ->
        val from = ((threshold - widget.min) / (widget.max - widget.min)).toFloat()
        if (from in 0f..1f) {
            drawArc(
                Red.copy(alpha = 0.75f),
                angle(from),
                sweep * (1f - from),
                false,
                track,
                trackSize,
                style = Stroke(r * 0.020f),
            )
        }
    }
    p.fraction?.let { fraction ->
        val color = if (p.warning) Red else Ink.copy(alpha = 0.92f)
        drawArc(
            color,
            start,
            sweep * fraction,
            false,
            track,
            trackSize,
            style = Stroke(r * 0.022f, cap = StrokeCap.Round),
        )
        drawLine(
            if (p.warning) Red else Ink,
            c,
            at(r * 0.79f, angle(fraction)),
            strokeWidth = r * 0.026f,
            cap = StrokeCap.Round,
        )
        drawCircle(Bg, r * 0.40f, c)
    }
}

@Composable
private fun TileWidget(widget: WidgetSpec, state: CockpitUiState, modifier: Modifier) {
    val p = present(widget, state)
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(8.dp)).background(Panel)
            .border(1.dp, Hair, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val h = maxHeight
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                widget.title.uppercase(),
                color = Dim,
                fontSize = 9.sp,
                letterSpacing = 0.6.sp,
                fontFamily = CockpitFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val valueSize = with(LocalDensity.current) {
                minOf(h.toPx() * 0.29f, 30.sp.toPx()).toSp()
            }
            Text(
                p.text,
                color = if (p.warning) Red else if (p.stale) Dim else Ink,
                fontSize = valueSize,
                lineHeight = valueSize,
                fontWeight = FontWeight.SemiBold,
                fontFamily = CockpitFont,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            if (p.unit.isNotEmpty()) {
                Text(
                    p.unit,
                    color = Dim,
                    fontSize = 9.sp,
                    fontFamily = CockpitFont,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PerformancePanel(state: CockpitUiState, modifier: Modifier) {
    val ids = listOf(
        SignalCatalog.Ids.POWER_KW,
        SignalCatalog.Ids.TORQUE_NM,
        SignalCatalog.Ids.LOAD,
        SignalCatalog.Ids.VOLTAGE,
    )
    val now = state.now
    Column(modifier) {
        Text(
            "ДИНАМИКА",
            color = Dim,
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            fontFamily = CockpitFont,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        ids.forEach { id ->
            val definition = SignalCatalog.get(id) ?: return@forEach
            val value = state.values[id]
            val stale = value == null || value.isStale(now, definition)
            val number = if (stale) null else value?.value
            val fraction = if (number == null) 0f else {
                val min = definition.minValue ?: 0.0
                val max = definition.maxValue ?: 1.0
                ((number - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
            }
            Row(
                Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    definition.nameRu,
                    color = Dim,
                    fontSize = 11.sp,
                    fontFamily = CockpitFont,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (number == null) "—" else "${definition.format(number)} ${definition.unit}",
                    color = if (number == null) Dim else Ink,
                    fontSize = 13.sp,
                    fontFamily = CockpitFont,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box(
                Modifier.fillMaxWidth().height(3.dp)
                    .background(Color(0x1FFFFFFF), RoundedCornerShape(2.dp)),
            ) {
                Box(
                    Modifier.fillMaxWidth(fraction).height(3.dp)
                        .background(if (fraction > 0.8f) Red else Ink.copy(alpha = 0.65f),
                            RoundedCornerShape(2.dp)),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// -- каталог данных ---------------------------------------------------------

@Composable
private fun LiveDataView(
    state: CockpitUiState,
    query: String,
    onQuery: (String) -> Unit,
    category: SignalCategory?,
    onCategory: (SignalCategory?) -> Unit,
    modifier: Modifier,
) {
    val filtered = SignalCatalog.all.filter { definition ->
        (category == null || definition.category == category) &&
            (query.isBlank() || matches(definition, query))
    }
    val categories = SignalCategory.entries.filter { cat ->
        SignalCatalog.all.any { it.category == cat }
    }
    val sampled = state.values.count { it.value.state == io.github.nchalexz.aaperfmon.telemetry.ReadingState.OK }

    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Поиск параметра", fontSize = 13.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Ink),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Blue,
                unfocusedBorderColor = Hair,
                focusedTextColor = Ink,
                unfocusedTextColor = Ink,
                cursorColor = Blue,
            ),
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                FilterChip(
                    selected = category == null,
                    onClick = { onCategory(null) },
                    label = { Text("Все", fontSize = 11.sp) },
                )
            }
            items(categories) { cat ->
                FilterChip(
                    selected = category == cat,
                    onClick = { onCategory(if (category == cat) null else cat) },
                    label = { Text(cat.labelRu, fontSize = 11.sp) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${filtered.size} из ${SignalCatalog.all.size} · прочитано $sampled · ${state.sourceLabel}",
            color = Dim,
            fontSize = 10.sp,
            fontFamily = CockpitFont,
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { definition ->
                SignalRow(definition, state.values[definition.id], state.now)
                HorizontalDivider(color = Hair, thickness = 0.5.dp)
            }
        }
    }
}

private fun matches(definition: SignalDefinition, query: String): Boolean {
    val needle = query.trim().lowercase()
    return definition.nameRu.lowercase().contains(needle) ||
        definition.nameEn.lowercase().contains(needle) ||
        definition.id.lowercase().contains(needle) ||
        definition.unit.lowercase().contains(needle)
}

@Composable
private fun SignalRow(definition: SignalDefinition, value: SignalValue?, now: Long) {
    val stale = value == null || value.isStale(now, definition)
    val number = if (stale) null else value?.value
    val note = when {
        value == null -> "не опрашивался"
        stale && value.state != io.github.nchalexz.aaperfmon.telemetry.ReadingState.OK -> value.state.name
        stale -> "устарело"
        else -> definition.verification.labelRu
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                definition.nameRu,
                color = Ink,
                fontSize = 13.sp,
                fontFamily = CockpitFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${definition.requestHex} · ${definition.protocol.labelRu} · ${definition.ecu.labelRu}",
                color = Dim,
                fontSize = 9.sp,
                fontFamily = CockpitFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (number == null) "—" else "${definition.format(number)} ${definition.unit}",
                color = if (number == null) Dim else Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = CockpitFont,
                maxLines = 1,
            )
            Text(note, color = Dim, fontSize = 9.sp, fontFamily = CockpitFont, maxLines = 1)
        }
    }
}