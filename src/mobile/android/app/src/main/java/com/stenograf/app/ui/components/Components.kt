package com.stenograf.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.stenograf.app.R
import com.stenograf.app.ui.theme.Reco
import com.stenograf.app.ui.theme.RecoPalette
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

/** Толщина обводки всех элементов. */
val Stroke = 2.dp

private val WindowShape = RoundedCornerShape(10.dp)
private val ButtonShape = RoundedCornerShape(8.dp)

/** Жёсткая тень без размытия, сдвинутая вправо-вниз, — как у окон старых систем. */
fun Modifier.hardShadow(offset: Dp, color: Color, shape: Shape): Modifier =
    if (offset <= 0.dp) this else drawBehind {
        val o = offset.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)
        translate(left = o, top = o) { drawOutline(outline, color) }
    }

/** Фон «рабочего стола»: бумага с двумя широкими волнами. */
@Composable
fun DesktopBackground(modifier: Modifier = Modifier) {
    val c = Reco.colors
    Canvas(modifier.fillMaxSize().background(c.desktop)) {
        band(c.bandA, y0 = 0.08f, y1 = 0.30f, thick = 0.15f)
        band(c.bandB, y0 = 0.66f, y1 = 0.50f, thick = 0.12f)
    }
}

private fun DrawScope.band(color: Color, y0: Float, y1: Float, thick: Float) {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(0f, h * y0)
        cubicTo(w * 0.35f, h * (y0 - 0.08f), w * 0.65f, h * (y1 + 0.08f), w, h * y1)
        lineTo(w, h * (y1 + thick))
        cubicTo(w * 0.65f, h * (y1 + thick + 0.08f), w * 0.35f, h * (y0 + thick - 0.08f), 0f, h * (y0 + thick))
        close()
    }
    drawPath(p, color)
}

/** Логотип RECO — иконка приложения (звуковая волна) на тёмной плитке. */
@Composable
fun RecoLogo(size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.24f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(RecoPalette.IconInk)
            .border(Stroke, Reco.colors.ink, shape)
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/** Верхняя панель, как строка меню рабочего стола. */
@Composable
fun MenuBar(
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    leading: @Composable RowScope.() -> Unit,
) {
    val c = Reco.colors
    Column(modifier.fillMaxWidth().background(c.window)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = leading)
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        Box(Modifier.fillMaxWidth().height(Stroke).background(c.ink))
    }
}

/** Нижняя панель, как панель задач. */
@Composable
fun TaskBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Reco.colors
    Column(modifier.fillMaxWidth().background(c.window)) {
        Box(Modifier.fillMaxWidth().height(Stroke).background(c.ink))
        content()
    }
}

/** Часы в строке меню (формат 12/24 ч — как в системе). */
@Composable
fun MenuClock() {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    Text(
        android.text.format.DateFormat.getTimeFormat(context).format(Date(now)),
        style = MaterialTheme.typography.labelLarge,
        color = Reco.colors.ink,
    )
}

/** Три кружка в заголовке окна. Декоративные. */
@Composable
fun WindowDots(modifier: Modifier = Modifier) {
    val c = Reco.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf(RecoPalette.Red, RecoPalette.Yellow, c.window).forEach { fill ->
            Box(Modifier.size(11.dp).clip(CircleShape).background(fill).border(1.5.dp, c.ink, CircleShape))
        }
    }
}

/**
 * Окно: заголовок с кружками и названием по центру, толстая обводка, жёсткая тень.
 * Место под тень входит в размер элемента.
 */
@Composable
fun RetroWindow(
    title: String,
    modifier: Modifier = Modifier,
    titleFill: Color = Reco.colors.titleBar,
    bodyFill: Color = Reco.colors.window,
    shadow: Dp = 5.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Reco.colors
    Column(
        modifier
            .padding(end = shadow, bottom = shadow)
            .hardShadow(shadow, c.shadow, WindowShape)
            .clip(WindowShape)
            .background(bodyFill)
            .border(Stroke, c.ink, WindowShape)
    ) {
        Box(Modifier.fillMaxWidth().background(titleFill).height(40.dp).padding(horizontal = 12.dp)) {
            WindowDots(Modifier.align(Alignment.CenterStart))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (titleFill == c.titleBar) c.ink else RecoPalette.IconInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            )
            if (trailing != null) {
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        }
        Box(Modifier.fillMaxWidth().height(Stroke).background(c.ink))
        content()
    }
}

/** Квадратная кнопка «×» в заголовке окна. */
@Composable
fun TitleBarClose(contentDescription: String, onClick: () -> Unit) {
    val c = Reco.colors
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier
            .size(28.dp)
            .clip(shape)
            .background(c.window)
            .border(Stroke, c.ink, shape)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Close, contentDescription = contentDescription, tint = c.ink, modifier = Modifier.size(18.dp))
    }
}

/**
 * Кнопка с обводкой и жёсткой тенью. При нажатии «вдавливается»: сдвигается на место тени.
 */
@Composable
fun RetroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    fill: Color = Reco.colors.window,
    contentColor: Color = Reco.colors.ink,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    shadow: Dp = 4.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    leading: (@Composable () -> Unit)? = null,
) {
    PressableBox(
        onClick = onClick,
        modifier = modifier,
        fill = fill,
        enabled = enabled,
        height = height,
        shadow = shadow,
        description = null,
    ) {
        val color = if (enabled) contentColor else Reco.colors.muted
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                leading != null -> {
                    leading()
                    Spacer(Modifier.width(10.dp))
                }
                icon != null -> {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
            }
            Text(text, style = textStyle, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Квадратная кнопка со значком. */
@Composable
fun RetroIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    iconSize: Dp = 22.dp,
    fill: Color = Reco.colors.window,
    contentColor: Color = Reco.colors.ink,
    enabled: Boolean = true,
    shadow: Dp = 3.dp,
) {
    PressableBox(
        onClick = onClick,
        modifier = modifier.width(size + shadow),
        fill = fill,
        enabled = enabled,
        height = size,
        shadow = shadow,
        description = contentDescription,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) contentColor else Reco.colors.muted,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun PressableBox(
    onClick: () -> Unit,
    modifier: Modifier,
    fill: Color,
    enabled: Boolean,
    height: Dp,
    shadow: Dp,
    description: String?,
    content: @Composable () -> Unit,
) {
    val c = Reco.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = if (pressed && enabled) shadow else 0.dp
    Box(
        modifier
            .padding(end = shadow, bottom = shadow)
            .height(height)
            .offset(x = press, y = press)
            .hardShadow(if (enabled) shadow - press else 0.dp, c.shadow, ButtonShape)
            .clip(ButtonShape)
            .background(if (enabled) fill else c.track)
            .border(Stroke, if (enabled) c.ink else c.muted, ButtonShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = description,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Индикатор выполнения из отдельных блоков, как в старых системах; блоки окрашены
 * градиентом иконки. `progress == null` — неопределённое ожидание (бегущие блоки).
 */
@Composable
fun RetroProgress(progress: Float?, modifier: Modifier = Modifier) {
    val c = Reco.colors
    val shape = RoundedCornerShape(5.dp)
    val sweep = if (progress == null) {
        val t = rememberInfiniteTransition(label = "progress")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "sweep").value
    } else 0f
    Canvas(
        modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(shape)
            .background(c.window)
            .border(Stroke, c.ink, shape)
    ) {
        val pad = 5.dp.toPx()
        val gap = 3.dp.toPx()
        val innerW = size.width - pad * 2
        val n = max(1, ((innerW + gap) / (8.dp.toPx() + gap)).toInt())
        val segW = (innerW - gap * (n - 1)) / n
        val h = size.height - pad * 2
        val from: Int
        val to: Int
        if (progress != null) {
            from = 0
            to = (progress.coerceIn(0f, 1f) * n).roundToInt()
        } else {
            val w = max(3, n / 5)
            from = ((n + w) * sweep).toInt() - w
            to = from + w
        }
        for (i in max(0, from) until minOf(n, to)) {
            val color = lerp(RecoPalette.Red, RecoPalette.Yellow, if (n > 1) i / (n - 1f) else 0f)
            drawRect(color, topLeft = Offset(pad + i * (segW + gap), pad), size = Size(segW, h))
        }
    }
}

/** Квадратик цвета участника. */
@Composable
fun SpeakerSwatch(color: Color, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.dp)
    Box(modifier.size(size).clip(shape).background(color).border(1.5.dp, Reco.colors.ink, shape))
}

/** Плашка участника: цветная заливка, обводка, тёмная подпись. */
@Composable
fun SpeakerChip(text: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .padding(end = 3.dp, bottom = 3.dp)
            .hardShadow(3.dp, Reco.colors.shadow, shape)
            .clip(shape)
            .background(color)
            .border(Stroke, Reco.colors.ink, shape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = RecoPalette.IconInk, maxLines = 1)
    }
}

/** Небольшая плашка-подпись. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, fill: Color = RecoPalette.Yellow) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .border(1.5.dp, Reco.colors.ink, shape)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = RecoPalette.IconInk)
    }
}

/** Столбики уровня громкости (новые справа), окрашенные цветами иконки. */
@Composable
fun LevelBars(levels: List<Float>, active: Boolean, modifier: Modifier = Modifier, bars: Int = 32) {
    val c = Reco.colors
    Canvas(modifier.fillMaxWidth().height(80.dp)) {
        val gap = 4.dp.toPx()
        val barWidth = max(3f, (size.width - gap * (bars - 1)) / bars)
        val outline = 1.5.dp.toPx()
        val shown = levels.takeLast(bars)
        val offset = bars - shown.size
        for (i in 0 until bars) {
            val level = if (i < offset) 0f else shown[i - offset]
            val h = max(barWidth, size.height * level.coerceIn(0f, 1f))
            val x = i * (barWidth + gap)
            val top = (size.height - h) / 2
            val fill = when {
                i < offset -> c.track
                !active -> c.muted
                else -> RecoPalette.Wave[i % RecoPalette.Wave.size]
            }
            drawRect(c.ink, topLeft = Offset(x, top), size = Size(barWidth, h))
            if (barWidth > outline * 2 && h > outline * 2) {
                drawRect(fill, topLeft = Offset(x + outline, top + outline), size = Size(barWidth - outline * 2, h - outline * 2))
            }
        }
    }
}

/** Поле ввода в рамке. */
@Composable
fun RetroTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
) {
    val c = Reco.colors
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.window)
            .border(Stroke, c.ink, shape)
            .padding(12.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
        minLines = minLines,
        maxLines = maxLines,
        cursorBrush = SolidColor(c.ink),
    )
}

/**
 * Диалог-окно. Содержимое прокручивается, кнопки всегда видны внизу.
 * @param dismissible закрывается ли кнопкой «Назад», касанием вне окна и крестиком.
 */
@Composable
fun RetroDialog(
    title: String,
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val close: (@Composable RowScope.() -> Unit)? =
        if (dismissible) ({ TitleBarClose("Закрыть", onDismiss) }) else null
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
    ) {
        RetroWindow(title = title, modifier = Modifier.fillMaxWidth(), shadow = 6.dp, trailing = close) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp),
                content = content,
            )
            if (buttons != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
    }
}

/** Значок предупреждения: жёлтый квадрат с «!». */
@Composable
fun WarningBadge(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.size(40.dp).clip(shape).background(RecoPalette.Yellow).border(Stroke, Reco.colors.ink, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text("!", style = MaterialTheme.typography.headlineSmall, color = RecoPalette.IconInk)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RetroDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            RetroButton("Отмена", onClick = onDismiss, height = 44.dp, shadow = 3.dp)
            RetroButton(
                confirmText,
                onClick = onConfirm,
                height = 44.dp,
                shadow = 3.dp,
                fill = if (destructive) RecoPalette.Red else RecoPalette.Yellow,
                contentColor = RecoPalette.IconInk,
            )
        },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            WarningBadge()
            Spacer(Modifier.width(14.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Reco.colors.ink)
        }
    }
}

/** Всплывающее меню в рамке (по долгому нажатию). */
@Composable
fun RetroPopupMenu(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Reco.colors
    val shape = RoundedCornerShape(8.dp)
    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .padding(end = 20.dp, top = 8.dp)
                .padding(end = 4.dp, bottom = 4.dp)
                .hardShadow(4.dp, c.shadow, shape)
                .clip(shape)
                .background(c.window)
                .border(Stroke, c.ink, shape)
                .width(200.dp),
            content = content,
        )
    }
}

@Composable
fun RetroMenuItem(text: String, onClick: () -> Unit, color: Color = Reco.colors.ink) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
