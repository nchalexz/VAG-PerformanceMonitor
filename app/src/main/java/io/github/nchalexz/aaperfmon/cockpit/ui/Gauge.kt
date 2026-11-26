package io.github.nchalexz.aaperfmon.cockpit.ui

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cyclone
import androidx.compose.material.icons.rounded.OilBarrel
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import io.github.nchalexz.aaperfmon.R
import io.github.nchalexz.aaperfmon.cockpit.UiLanguage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Круглый аналогово-цифровой прибор.
 *
 * Геометрия одна на все разрешения: центр, внешний радиус R, диск 0.44R,
 * значение с реальными границами глифов, единица на 0.54R, пиктограмма
 * на 0.73R, название на 1.15R. Дуги прогресса нет: в эталоне её нет.
 */
@Composable
fun CircularGauge(
    spec: MetricSpec,
    reading: MetricReading,
    language: UiLanguage,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalDashboardColors.current
    val context = LocalContext.current
    val typeface = remember {
        ResourcesCompat.getFont(context, R.font.titillium_web_light)
            ?: Typeface.DEFAULT
    }
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            this.typeface = typeface
        }
    }
    val density = LocalDensity.current

    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val side = minOf(maxWidth, maxHeight)
            val radius = side / 2f
            var lastFraction by remember(spec.signalId) { mutableStateOf(0f) }
            LaunchedEffect(reading.fraction) {
                reading.fraction?.let { lastFraction = it }
            }
            val target = if (reading.isOk) (reading.fraction ?: lastFraction) else lastFraction
            val needle by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(durationMillis = 150, easing = LinearEasing),
                label = "needle",
            )
            val needleAlpha by animateFloatAsState(
                targetValue = if (reading.isOk) 1f else 0f,
                animationSpec = tween(durationMillis = 200),
                label = "needle-alpha",
            )

            Box(
                Modifier.size(side)
                    .semantics { contentDescription = gaugeDescription(spec, reading, language) },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawGauge(spec, needle, needleAlpha, colors)
                    drawValue(paint, reading, colors)
                }

                val unit = spec.unit(language).uppercase()
                if (unit.isNotEmpty()) {
                    Text(
                        unit,
                        color = colors.dim,
                        fontFamily = DashboardFont,
                        fontSize = with(density) { (radius.toPx() * 0.080f).toSp() },
                        letterSpacing = 1.2.sp,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.Center).offset(y = radius * 0.54f),
                    )
                }
                val icon = spec.icon.vector()
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = colors.text.copy(alpha = 0.55f),
                        modifier = Modifier.align(Alignment.Center)
                            .offset(y = radius * 0.73f)
                            .size(radius * 0.18f),
                    )
                }
            }
        }
        Text(
            spec.label(language).uppercase(),
            color = colors.dim,
            fontFamily = DashboardFont,
            fontSize = 12.sp,
            letterSpacing = 1.2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Значение центрируется по реальным границам глифов, а не по baseline. */
private fun DrawScope.drawValue(
    paint: Paint,
    reading: MetricReading,
    colors: DashboardColors,
) {
    val r = size.minDimension / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val text = reading.text
    if (text.isBlank()) return

    val color = when {
        reading.spec.criticalValue(reading.display) -> colors.critical
        reading.spec.warningValue(reading.display) -> colors.warning
        !reading.isOk -> colors.text.copy(alpha = 0.55f)
        else -> colors.text
    }
    paint.color = color.toArgb()
    var textSize = r * 0.62f
    val bounds = Rect()
    while (textSize > r * 0.12f) {
        paint.textSize = textSize
        paint.getTextBounds(text, 0, text.length, bounds)
        if (bounds.width() <= r * 0.78f && bounds.height() <= r * 0.62f) break
        textSize *= 0.94f
    }
    paint.textSize = textSize
    paint.getTextBounds(text, 0, text.length, bounds)
    val baseline = center.y - (bounds.top + bounds.bottom) / 2f
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(text, center.x, baseline, paint)
    }
}

private fun gaugeDescription(
    spec: MetricSpec,
    reading: MetricReading,
    language: UiLanguage,
): String {
    val label = spec.label(language).lowercase()
    val unit = spec.unit(language)
    val note = reading.state.note(language)
    return if (reading.isOk) {
        "$label ${reading.text} $unit".trim()
    } else {
        "$label — $note".trim()
    }
}

private fun DrawScope.drawGauge(
    spec: MetricSpec,
    needle: Float,
    needleAlpha: Float,
    colors: DashboardColors,
) {
    val r = size.minDimension / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val start = 135f
    val sweep = 270f
    fun angle(degrees: Float): Float = degrees * PI.toFloat() / 180f
    fun point(radius: Float, degrees: Float) = Offset(
        center.x + radius * cos(angle(degrees)),
        center.y + radius * sin(angle(degrees)),
    )
    fun fractionToAngle(fraction: Float): Float = start + sweep * fraction.coerceIn(0f, 1f)

    // Тонкая равномерная внешняя рамка и внутреннее кольцо, как в эталоне.
    drawCircle(
        colors.ring.copy(alpha = 0.55f),
        radius = r * 0.975f,
        center = center,
        style = Stroke(width = 1.6.dp.toPx()),
    )
    drawCircle(
        colors.ring.copy(alpha = 0.22f),
        radius = r * 0.945f,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )

    // Дуга прогресса: сегмент между двумя кольцами от начала шкалы
    // до текущего значения. Она рисуется под стрелкой и делениями.
    if (needleAlpha > 0.01f) {
        drawArc(
            color = colors.text.copy(alpha = 0.90f * needleAlpha),
            startAngle = start,
            sweepAngle = sweep * needle.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(center.x - r * 0.958f, center.y - r * 0.958f),
            size = Size(r * 1.916f, r * 1.916f),
            style = Stroke(width = 4.2.dp.toPx(), cap = StrokeCap.Round),
        )
    }

    // Красная зона — единственная заливка шкалы; янтарной нет в эталоне.
    val warn = spec.warnFrom?.let {
        ((it - spec.scaleMin) / (spec.scaleMax - spec.scaleMin)).toFloat().coerceIn(0f, 1f)
    }
    if (warn != null && warn < 1f) {
        drawArc(
            color = colors.critical.copy(alpha = 0.78f),
            startAngle = fractionToAngle(warn),
            sweepAngle = sweep * (1f - warn),
            useCenter = false,
            topLeft = Offset(center.x - r * 0.905f, center.y - r * 0.905f),
            size = Size(r * 1.81f, r * 1.81f),
            style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round),
        )
    }

    // 60 делений: длинные через каждые 10, средние через 5.
    for (i in 0..60) {
        val major = i % 10 == 0
        val middle = i % 5 == 0
        val degrees = start + sweep * i / 60f
        val inner = when {
            major -> r * 0.765f
            middle -> r * 0.820f
            else -> r * 0.850f
        }
        drawLine(
            color = colors.ring.copy(alpha = if (major) 0.9f else if (middle) 0.55f else 0.30f),
            start = point(inner, degrees),
            end = point(r * 0.925f, degrees),
            strokeWidth = when {
                major -> 2.2.dp.toPx()
                middle -> 1.5.dp.toPx()
                else -> 1.dp.toPx()
            },
            cap = StrokeCap.Butt,
        )
    }

    if (needleAlpha > 0.01f) {
        val degrees = fractionToAngle(needle)
        drawLine(
            color = colors.needle.copy(alpha = needleAlpha),
            start = point(r * 0.45f, degrees),
            end = point(r * 0.90f, degrees),
            strokeWidth = (r * 0.022f).coerceAtLeast(2.8.dp.toPx()),
            cap = StrokeCap.Round,
        )
    }

    // Диск 0.44R: меньше прежнего, значение и единица не пересекают его край.
    drawCircle(colors.dialFace, radius = r * 0.44f, center = center)
}

private fun GaugeIcon.vector(): ImageVector? = when (this) {
    GaugeIcon.COOLANT -> Icons.Rounded.Thermostat
    GaugeIcon.BOOST -> Icons.Rounded.Cyclone
    GaugeIcon.OIL -> Icons.Rounded.OilBarrel
    GaugeIcon.NONE -> null
}

/** Плитка главного экрана и расширенной сетки. */
@Composable
fun MetricTile(
    spec: MetricSpec,
    reading: MetricReading,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDashboardColors.current
    val density = LocalDensity.current
    val note = reading.state.note(language)
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .semantics {
                contentDescription = buildString {
                    append(spec.label(language))
                    append(": ")
                    append(if (reading.isOk) "${reading.text} ${spec.unit(language)}" else note)
                }
            },
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        val labelSize = (w * 0.036f).coerceIn(10f, 14f).sp
        val unitSize = (w * 0.030f).coerceIn(10f, 13f).sp
        val valueSize = minOf(w * 0.16f, h * 0.36f).coerceIn(18f, 58f).sp
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                spec.label(language).uppercase(),
                color = colors.dim,
                fontFamily = DashboardFont,
                fontSize = labelSize,
                letterSpacing = 1.1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        reading.text,
                        color = when {
                            spec.criticalValue(reading.display) -> colors.critical
                            spec.warningValue(reading.display) -> colors.warning
                            !reading.isOk -> colors.dim
                            else -> colors.text
                        },
                        fontFamily = DashboardFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = valueSize,
                        lineHeight = valueSize,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                    )
                    val unit = spec.unit(language)
                    if (unit.isNotEmpty()) {
                        Text(
                            unit,
                            color = colors.dim,
                            fontFamily = DashboardFont,
                            fontSize = unitSize,
                            lineHeight = unitSize,
                            letterSpacing = 0.4.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        if (reading.state != MetricState.OK) {
            val dotColor = when (reading.state) {
                MetricState.WAITING -> colors.dim
                MetricState.STALE -> colors.warning
                MetricState.UNSUPPORTED -> colors.tick
                MetricState.ERROR, MetricState.OFFLINE -> colors.critical
                MetricState.OK -> Color.Transparent
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 7.dp)
                    .size(5.dp).clip(CircleShape).background(dotColor),
            )
        }
    }
}
