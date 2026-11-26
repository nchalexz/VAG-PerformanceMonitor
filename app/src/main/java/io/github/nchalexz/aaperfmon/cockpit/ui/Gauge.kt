package io.github.nchalexz.aaperfmon.cockpit.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nchalexz.aaperfmon.cockpit.UiLanguage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Круглый аналогово-цифровой прибор.
 *
 * Рисуется одним Canvas: двойное кольцо, деления, красная зона и стрелка.
 * Значение, единица и пиктограмма лежат поверх тёмного диска. Стрелка
 * интерполируется коротким tween — это сглаживание между кадрами
 * телеметрии, а не искусственная задержка: новое значение приходит
 * в состояние сразу.
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
            val sidePx = with(density) { side.toPx() }
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

            Box(Modifier.size(side)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawGauge(spec, needle, needleAlpha, colors)
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = (-side * 0.012f))
                        .fillMaxWidth(0.62f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        reading.text,
                        color = when {
                            reading.spec.criticalValue(reading.display) -> colors.critical
                            reading.spec.warningValue(reading.display) -> colors.warning
                            !reading.isOk -> colors.dim
                            else -> colors.text
                        },
                        fontFamily = DashboardFont,
                        fontWeight = FontWeight.Light,
                        fontSize = valueFontSize(side, reading.text.length),
                        lineHeight = valueFontSize(side, reading.text.length),
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics {
                            contentDescription = gaugeDescription(spec, reading, language)
                        },
                    )
                    val unit = spec.unit(language).uppercase()
                    if (unit.isNotEmpty()) {
                        Text(
                            unit,
                            color = colors.dim,
                            fontFamily = DashboardFont,
                            fontSize = with(density) { (sidePx * 0.075f).toSp() },
                            letterSpacing = 1.4.sp,
                            maxLines = 1,
                        )
                    }
                    val icon = spec.icon.vector()
                    if (icon != null) {
                        Spacer(Modifier.height(side * 0.012f))
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = colors.text.copy(alpha = 0.5f),
                            modifier = Modifier.size(side * 0.11f),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            spec.label(language).uppercase(),
            color = colors.dim,
            fontFamily = DashboardFont,
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun valueFontSize(side: Dp, length: Int): androidx.compose.ui.unit.TextUnit {
    val factor = when {
        length <= 2 -> 0.285f
        length == 3 -> 0.255f
        length == 4 -> 0.210f
        else -> 0.170f
    }
    return (side.value * factor).sp
}

private fun gaugeDescription(spec: MetricSpec, reading: MetricReading, language: UiLanguage): String {
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

    // Двойное тонкое кольцо как на макетах: внешнее ярче, внутреннее почти тает.
    drawCircle(
        colors.ring.copy(alpha = 0.40f),
        radius = r * 0.975f,
        center = center,
        style = Stroke(width = 2.dp.toPx()),
    )
    drawCircle(
        colors.ring.copy(alpha = 0.16f),
        radius = r * 0.945f,
        center = center,
        style = Stroke(width = 1.1.dp.toPx()),
    )

    // Дуга заполнения: сегмент между двумя кольцами от начала шкалы
    // до текущего значения. Она рисуется под стрелкой и делениями.
    if (needleAlpha > 0.01f) {
        drawArc(
            color = colors.text.copy(alpha = 0.90f * needleAlpha),
            startAngle = start,
            sweepAngle = sweep * needle.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(center.x - r * 0.958f, center.y - r * 0.958f),
            size = androidx.compose.ui.geometry.Size(r * 1.916f, r * 1.916f),
            style = Stroke(width = 4.2.dp.toPx(), cap = StrokeCap.Round),
        )
    }

    // Красная и предупреждающая зоны лежат на шкале, а не поверх стрелки.
    val criticalStart = spec.criticalFrom?.let {
        ((it - spec.scaleMin) / (spec.scaleMax - spec.scaleMin)).toFloat()
    }
    spec.warnFrom?.let { warn ->
        val from = ((warn - spec.scaleMin) / (spec.scaleMax - spec.scaleMin)).toFloat()
            .coerceIn(0f, 1f)
        val to = criticalStart?.coerceIn(0f, 1f) ?: 1f
        if (to > from) {
            drawArc(
                color = colors.warning.copy(alpha = 0.7f),
                startAngle = fractionToAngle(from),
                sweepAngle = sweep * (to - from),
                useCenter = false,
                topLeft = Offset(center.x - r * 0.905f, center.y - r * 0.905f),
                size = androidx.compose.ui.geometry.Size(r * 1.81f, r * 1.81f),
                style = Stroke(width = 4.2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
    spec.criticalFrom?.let { critical ->
        val from = ((critical - spec.scaleMin) / (spec.scaleMax - spec.scaleMin)).toFloat()
            .coerceIn(0f, 1f)
        if (from < 1f) {
            drawArc(
                color = colors.critical.copy(alpha = 0.85f),
                startAngle = fractionToAngle(from),
                sweepAngle = sweep * (1f - from),
                useCenter = false,
                topLeft = Offset(center.x - r * 0.905f, center.y - r * 0.905f),
                size = androidx.compose.ui.geometry.Size(r * 1.81f, r * 1.81f),
                style = Stroke(width = 3.4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }

    // 60 делений: длинные через каждые 10, средние через 5.
    for (i in 0..60) {
        val major = i % 10 == 0
        val middle = i % 5 == 0
        val degrees = start + sweep * i / 60f
        val inner = when {
            major -> r * 0.785f
            middle -> r * 0.835f
            else -> r * 0.862f
        }
        drawLine(
            color = colors.ring.copy(alpha = if (major) 0.9f else if (middle) 0.55f else 0.30f),
            start = point(inner, degrees),
            end = point(r * 0.925f, degrees),
            strokeWidth = when {
                major -> 2.4.dp.toPx()
                middle -> 1.6.dp.toPx()
                else -> 1.05.dp.toPx()
            },
            cap = StrokeCap.Butt,
        )
    }

    if (needleAlpha > 0.01f) {
        val degrees = fractionToAngle(needle)
        drawLine(
            color = colors.needle.copy(alpha = needleAlpha),
            start = point(r * 0.42f, degrees),
            end = point(r * 0.89f, degrees),
            strokeWidth = (r * 0.024f).coerceAtLeast(3.dp.toPx()),
            cap = StrokeCap.Round,
        )
    }

    // Диск перекрывает основание стрелки — так на макетах.
    drawCircle(colors.dialFace, radius = r * 0.505f, center = center)
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
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(colors.card)
            .border(1.dp, colors.border, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .semantics {
                contentDescription = buildString {
                    append(spec.label(language))
                    append(": ")
                    append(if (reading.isOk) "${reading.text} ${spec.unit(language)}" else note)
                }
            },
    ) {
        val h = maxHeight
        val valueSize = with(density) {
            (h.toPx() * 0.30f).coerceIn(14f, 56f).toSp()
        }
        val unitSize = with(density) {
            (h.toPx() * 0.052f).coerceIn(10f, 14f).toSp()
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                spec.label(language).uppercase(),
                color = colors.dim,
                fontFamily = DashboardFont,
                fontSize = with(density) { (h.toPx() * 0.042f).coerceIn(8f, 13f).toSp() },
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
                        fontWeight = FontWeight.Light,
                        fontSize = valueSize,
                        lineHeight = valueSize,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                    )
                    // Единица сразу под значением, как на макетах, а не
                    // прижата к нижнему краю плитки.
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
                            modifier = Modifier.padding(top = 1.dp),
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
