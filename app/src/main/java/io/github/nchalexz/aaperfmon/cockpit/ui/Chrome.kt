package io.github.nchalexz.aaperfmon.cockpit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nchalexz.aaperfmon.cockpit.UiLanguage
import io.github.nchalexz.aaperfmon.telemetry.ConnectionKind

/** Полоска категорий: активная подчёркнута бирюзой, между вкладками тонкие разделители. */
@Composable
fun CategoryTabBar(
    categories: List<DisplayCategory>,
    selected: DisplayCategory,
    language: UiLanguage,
    onSelect: (DisplayCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDashboardColors.current
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        categories.forEachIndexed { index, category ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight(0.55f)
                        .background(colors.border.copy(alpha = 0.8f)),
                )
            }
            val active = category == selected
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSelect(category) }
                    .semantics { contentDescription = category.label(language) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    category.label(language),
                    color = if (active) colors.text else colors.dim,
                    fontFamily = DashboardFont,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .height(2.dp)
                        .width(30.dp)
                        .background(if (active) colors.accent else Color.Transparent),
                )
            }
        }
    }
}

/** Маленькая плашка источника: ДЕМО, LIVE, ошибка. Занимает один угол, не больше. */
@Composable
fun SourcePill(
    label: String,
    color: Color,
    channel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = color,
            fontFamily = DashboardFont,
            fontSize = 10.sp,
            letterSpacing = 1.1.sp,
            maxLines = 1,
        )
        if (channel.isNotBlank()) {
            Text(
                " · $channel",
                color = LocalDashboardColors.current.dim,
                fontFamily = DashboardFont,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Верхняя строка управления: статус слева, действия справа. */
@Composable
fun CockpitChrome(
    connection: ConnectionKind,
    sourceLabel: String,
    channel: String,
    language: UiLanguage,
    onCatalog: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDashboardColors.current
    Row(
        modifier.fillMaxWidth().height(38.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = when (connection) {
            ConnectionKind.DEMO -> colors.warning
            ConnectionKind.CONNECTED -> colors.accent
            ConnectionKind.CONNECTING -> colors.accent
            ConnectionKind.ERROR -> colors.critical
            ConnectionKind.DISCONNECTED -> colors.dim
        }
        SourcePill(sourceLabel, color, channel)
        Spacer(Modifier.weight(1f))
        ChromeAction(Icons.AutoMirrored.Rounded.List, language.pick("Data catalog", "Каталог данных"), onCatalog)
        ChromeAction(Icons.Rounded.Settings, language.pick("Settings", "Настройки"), onSettings)
        ChromeAction(Icons.Rounded.Close, language.pick("Close", "Закрыть"), onClose)
    }
}

@Composable
private fun ChromeAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val colors = LocalDashboardColors.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.dim.copy(alpha = 0.85f),
            modifier = Modifier.size(17.dp),
        )
    }
}

/** Две точки внизу: видно, что экранов два и можно свайпнуть. */
@Composable
fun PageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val colors = LocalDashboardColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(if (index == current) 5.dp else 4.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == current) colors.accent else colors.dim.copy(alpha = 0.45f),
                    ),
            )
        }
    }
}

/** Пустой экран категории — честная надпись вместо нарисованных нулей. */
@Composable
fun EmptyCategory(language: UiLanguage, modifier: Modifier = Modifier) {
    val colors = LocalDashboardColors.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            language.pick(
                "No live parameters in this category",
                "В этой категории нет доступных параметров",
            ),
            color = colors.dim,
            fontFamily = DashboardFont,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}
