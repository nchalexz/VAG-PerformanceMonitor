package io.github.nchalexz.aaperfmon.cockpit

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.OilBarrel
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nchalexz.aaperfmon.R
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The screen itself; the activity below is a thin host. */
class CockpitActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CockpitScreen(onClose = { finish() }) }
    }
}

private val Ink = Color.White
private val Bg = Color.Black
private val Panel = Color(0xFF0A0D10)
private val Dim = Color(0xFF98A1AB)
private val Hair = Color(0x1FFFFFFF)
private val Red = Color(0xFFE5484D)
private val Amber = Color(0xFFF5B33C)

private val CockpitFont = FontFamily(
    Font(R.font.titillium_web_light, FontWeight.Light),
    Font(R.font.titillium_web_regular, FontWeight.Normal),
    Font(R.font.titillium_web_semibold, FontWeight.SemiBold),
)

/**
 * The driving dashboard: three dials and five tiles, drawn in the spirit of the MIB cluster — a
 * black background, hairline scales, a thick white needle, big digital values. The numbers come
 * from a demo loop until the diagnostics layer feeds it; nothing here touches the car.
 */
@Composable
fun CockpitScreen(onClose: () -> Unit) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            t += 0.08f
            delay(80)
        }
    }
    val speed = (62f + 52f * sin(t * 0.55f)).coerceIn(0f, 160f)
    val rpm = 780f + speed * 27f
    val gear = when {
        speed < 15f -> 1
        speed < 35f -> 2
        speed < 55f -> 3
        speed < 75f -> 4
        speed < 95f -> 5
        speed < 115f -> 6
        else -> 7
    }
    val coolant = 88f + 3f * sin(t * 0.07f)
    val boost = if (rpm > 1400f) 0.25f + 0.55f * sin(t * 0.9f) else -0.32f
    val oil = 94f + 4f * sin(t * 0.05f)
    val use = 4.5f + 5.5f * kotlin.math.abs(sin(t * 0.4f))
    val fuel = 57f - (t * 0.01f) % 40f

    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VAG Cockpit", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = CockpitFont)
            Spacer(Modifier.width(10.dp))
            Text("ДЕМО", color = Amber, fontSize = 12.sp, letterSpacing = 1.2.sp, fontFamily = CockpitFont)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть", tint = Dim) }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Dial(coolant, 40f, 120f, 110f, "°C", "ОЖ", Icons.Rounded.Thermostat, Modifier.weight(1f).fillMaxHeight())
            Dial(boost, -1f, 2f, 1.6f, "bar", "Наддув", Icons.Rounded.Air, Modifier.weight(1f).fillMaxHeight(), decimals = 2)
            Dial(oil, 40f, 150f, 130f, "°C", "Масло", Icons.Rounded.OilBarrel, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().weight(0.55f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Скорость", "%.0f".format(speed), "км/ч", Modifier.weight(1f).fillMaxHeight())
            Tile("Обороты", "%.0f".format(rpm), "об/мин", Modifier.weight(1f).fillMaxHeight())
            Tile("Передача", "$gear", "", Modifier.weight(1f).fillMaxHeight())
            Tile("Расход", "%.1f".format(use), "л/100", Modifier.weight(1f).fillMaxHeight())
            Tile("Топливо", "%.0f".format(fuel), "%", Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun Dial(
    value: Float,
    min: Float,
    max: Float,
    redFrom: Float?,
    unit: String,
    label: String,
    icon: ImageVector,
    modifier: Modifier,
    decimals: Int = 0,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, (maxHeight - 22.dp).coerceAtLeast(40.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(side).aspectRatio(1f), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val start = 135f
                    val sweep = 270f
                    fun ang(v: Float) = start + sweep * ((v - min) / (max - min)).coerceIn(0f, 1f)
                    fun at(len: Float, a: Float) = Offset(
                        c.x + len * cos(a * PI.toFloat() / 180f),
                        c.y + len * sin(a * PI.toFloat() / 180f),
                    )
                    drawCircle(Ink.copy(alpha = 0.55f), r - r * 0.014f, c, style = Stroke(r * 0.012f))
                    for (i in 0..60) {
                        val major = i % 10 == 0
                        val a = start + sweep * i / 60f
                        drawLine(
                            Ink.copy(alpha = if (major) 0.75f else 0.3f),
                            at(r * (if (major) 0.80f else 0.855f), a),
                            at(r * 0.925f, a),
                            strokeWidth = r * (if (major) 0.014f else 0.007f),
                            cap = StrokeCap.Round,
                        )
                    }
                    val track = Offset(c.x - r * 0.95f, c.y - r * 0.95f)
                    val trackSize = Size(r * 1.9f, r * 1.9f)
                    redFrom?.let {
                        drawArc(Red.copy(alpha = 0.9f), ang(it), start + sweep - ang(it), false, track, trackSize, style = Stroke(r * 0.022f))
                    }
                    drawArc(Ink.copy(alpha = 0.9f), start, ang(value) - start, false, track, trackSize, style = Stroke(r * 0.022f, cap = StrokeCap.Round))
                    drawLine(Ink, c, at(r * 0.80f, ang(value)), strokeWidth = r * 0.028f, cap = StrokeCap.Round)
                    drawCircle(Bg, r * 0.42f, c)
                }
                Text(
                    "%.${decimals}f".format(value),
                    color = Ink,
                    fontFamily = CockpitFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = with(LocalDensity.current) { (side * 0.24f).toSp() },
                    lineHeight = with(LocalDensity.current) { (side * 0.24f).toSp() },
                    maxLines = 1,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = side * 0.30f)) {
                    Text(unit, color = Dim, fontSize = with(LocalDensity.current) { (side * 0.075f).toSp() }, fontFamily = CockpitFont)
                }
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Ink.copy(alpha = 0.55f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = side * 0.075f).size(side * 0.13f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                label.uppercase(),
                color = Dim,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp,
                fontFamily = CockpitFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String, modifier: Modifier) {
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(10.dp)).background(Panel).border(1.dp, Hair, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val h = maxHeight
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label.uppercase(),
                color = Dim,
                fontSize = with(LocalDensity.current) { (h * 0.16f).toSp() },
                lineHeight = with(LocalDensity.current) { (h * 0.16f).toSp() },
                letterSpacing = 0.8.sp,
                fontFamily = CockpitFont,
                maxLines = 1,
            )
            Text(
                value,
                color = Ink,
                fontSize = with(LocalDensity.current) { (h * 0.40f).toSp() },
                lineHeight = with(LocalDensity.current) { (h * 0.40f).toSp() },
                fontWeight = FontWeight.SemiBold,
                fontFamily = CockpitFont,
                maxLines = 1,
            )
            if (unit.isNotEmpty()) {
                Text(
                    unit,
                    color = Dim,
                    fontSize = with(LocalDensity.current) { (h * 0.14f).toSp() },
                    lineHeight = with(LocalDensity.current) { (h * 0.14f).toSp() },
                    fontFamily = CockpitFont,
                    maxLines = 1,
                )
            }
        }
    }
}
