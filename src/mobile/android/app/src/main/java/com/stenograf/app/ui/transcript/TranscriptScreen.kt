package com.stenograf.app.ui.transcript

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stenograf.app.data.Speaker
import com.stenograf.app.data.Transcript
import com.stenograf.app.data.Utterance
import com.stenograf.app.export.Exporter
import com.stenograf.app.ui.appContainer
import com.stenograf.app.ui.components.ConfirmDialog
import com.stenograf.app.ui.components.DesktopBackground
import com.stenograf.app.ui.components.MenuBar
import com.stenograf.app.ui.components.RetroButton
import com.stenograf.app.ui.components.RetroDialog
import com.stenograf.app.ui.components.RetroIconButton
import com.stenograf.app.ui.components.RetroTextField
import com.stenograf.app.ui.components.RetroWindow
import com.stenograf.app.ui.components.SpeakerChip
import com.stenograf.app.ui.components.SpeakerSwatch
import com.stenograf.app.ui.components.Stroke
import com.stenograf.app.ui.components.Tag
import com.stenograf.app.ui.components.TaskBar
import com.stenograf.app.ui.components.hardShadow
import com.stenograf.app.ui.theme.Reco
import com.stenograf.app.ui.theme.RecoPalette
import com.stenograf.app.ui.theme.TabularNumbers
import com.stenograf.app.ui.theme.speakerColor
import com.stenograf.app.util.Format

/** Сколько элементов списка идёт до первой реплики (заголовок). */
private const val HEADER_ITEMS = 1

@Composable
fun TranscriptScreen(id: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm: TranscriptViewModel = viewModel(key = "transcript-$id") { TranscriptViewModel(container, id) }
    val context = LocalContext.current

    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val reprocess by vm.reprocess.collectAsStateWithLifecycle()
    var editIndex by remember { mutableStateOf<Int?>(null) }

    val t = transcript
    val listState = rememberLazyListState()

    // Экспорт: пользователь выбирает, куда сохранить JSON-файл
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Exporter.MIME)) { uri ->
        if (uri != null) {
            vm.exportJson(context, uri) { ok ->
                Toast.makeText(context, if (ok) "Файл сохранён" else "Не удалось сохранить файл", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Текущая реплика во время воспроизведения
    val currentIndex by remember(t) {
        derivedStateOf {
            val list = t?.utterances ?: return@derivedStateOf -1
            if (!player.ready || (player.positionMs == 0L && !player.playing)) return@derivedStateOf -1
            val pos = player.positionMs / 1000.0 + 0.05
            list.indexOfLast { it.start <= pos }
        }
    }
    // Автопрокрутка к текущей реплике, если она ушла за экран
    LaunchedEffect(currentIndex, player.playing) {
        if (player.playing && currentIndex >= 0) {
            val target = currentIndex + HEADER_ITEMS
            val visible = listState.layoutInfo.visibleItemsInfo
            val shown = visible.size > 1 && visible.any { it.index == target } && visible.last().index != target
            if (!shown) listState.animateScrollToItem(target, scrollOffset = -48)
        }
    }

    Box(Modifier.fillMaxSize()) {
        DesktopBackground()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                MenuBar(
                    trailing = {
                        if (t != null) {
                            RetroButton(
                                text = "JSON",
                                icon = Icons.Rounded.SaveAlt,
                                onClick = { exportLauncher.launch(Exporter.fileName(t)) },
                                height = 40.dp,
                                shadow = 3.dp,
                                fill = RecoPalette.Yellow,
                                contentColor = RecoPalette.IconInk,
                                modifier = Modifier.semantics { contentDescription = "Экспорт JSON" },
                            )
                        }
                    },
                ) {
                    RetroIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Назад",
                        onClick = onBack,
                        size = 40.dp,
                        iconSize = 20.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Расшифровка",
                        style = MaterialTheme.typography.titleSmall,
                        color = Reco.colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            bottomBar = {
                if (vm.hasAudio) {
                    PlayerBar(state = player, onToggle = vm.player::toggle, onSeek = { vm.player.seekTo(it) })
                }
            },
        ) { padding ->
            if (t == null) return@Scaffold
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = padding.calculateTopPadding() + 14.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "header") { Header(t) }
                if (t.utterances.isEmpty()) {
                    item(key = "empty") {
                        RetroWindow(title = "Пусто", modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Речь в записи не обнаружена.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Reco.colors.muted,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                }
                itemsIndexed(t.utterances, key = { i, u -> "$i-${u.start}" }) { index, u ->
                    UtteranceItem(
                        u = u,
                        speaker = t.speaker(u.speakerId),
                        current = index == currentIndex,
                        onClick = { vm.player.seekTo((u.start * 1000).toLong(), andPlay = true) },
                        onLongClick = {
                            vm.resetReprocess()
                            editIndex = index
                        },
                    )
                }
            }
        }
    }

    val ei = editIndex
    if (ei != null && t != null && ei in t.utterances.indices) {
        EditUtteranceDialog(
            t = t,
            u = t.utterances[ei],
            reprocess = reprocess,
            reprocessAllowed = vm.hasAudio && jobs.none { it.active },
            onReprocess = { vm.reprocessFragment(ei) },
            onDismiss = {
                vm.resetReprocess()
                editIndex = null
            },
            onSave = { text, speakerId, byLlm ->
                vm.editUtterance(ei, text, speakerId, byLlm)
                vm.resetReprocess()
                editIndex = null
            },
            onDelete = {
                vm.deleteUtterance(ei)
                vm.resetReprocess()
                editIndex = null
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(t: Transcript) {
    val c = Reco.colors
    RetroWindow(title = "Сведения", modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(t.title, style = MaterialTheme.typography.headlineSmall, color = c.ink)
            Spacer(Modifier.height(4.dp))
            Text(Format.dateLong(t.createdAt), style = MaterialTheme.typography.labelMedium, color = c.muted)

            // Показатели — плитками
            val stats = buildList {
                add(Format.duration(t.durationSec) to "длительность")
                add("${t.activeSpeakers.size}" to "участников")
                if (t.processingSec > 0) {
                    add(Format.duration(t.processingSec) to "обработка")
                    add("%.2f".format(t.rtf) to "RTF")
                    if (!t.llmUnavailable) {
                        add("${t.llmChanged}" to "LLM: пунктуация исправлена")
                        add("${t.llmRejected}" to "LLM: ответов отклонено")
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            stats.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (value, label) -> StatTile(value, label, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
            if (t.llmUnavailable) {
                Text("LLM недоступна на этом устройстве", style = MaterialTheme.typography.bodySmall, color = c.danger)
                Spacer(Modifier.height(8.dp))
            }

            if (t.activeSpeakers.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    t.activeSpeakers.forEach { s -> SpeakerChip(Speaker.label(s.id), speakerColor(s.color)) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Нажмите на реплику, чтобы прослушать её. Удерживайте — чтобы исправить или распознать фрагмент заново. Метка «LLM» — пунктуацию поправила локальная LLM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                )
            }
        }
    }
}

/** Плитка показателя: крупное значение и подпись. */
@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val c = Reco.colors
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(c.titleBar)
            .border(Stroke, c.ink, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), color = c.ink, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.muted)
    }
}

/** Реплика — маленькое окно: в заголовке цвет и имя участника, время; ниже текст. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UtteranceItem(
    u: Utterance,
    speaker: Speaker,
    current: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = Reco.colors
    val shape = RoundedCornerShape(8.dp)
    val color = speakerColor(speaker.color)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(end = 5.dp, bottom = 5.dp)
            .hardShadow(if (current) 5.dp else 3.dp, c.shadow, shape)
            .clip(shape)
            .background(if (current) c.highlight else c.window)
            .border(Stroke, c.ink, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            Modifier.fillMaxWidth().background(color).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (current) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Звучит", tint = RecoPalette.IconInk, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                Speaker.label(speaker.id),
                style = MaterialTheme.typography.labelLarge,
                color = RecoPalette.IconInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (u.byLlm) {
                Spacer(Modifier.width(8.dp))
                Tag("LLM", fill = c.window)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${Format.clock(u.start)} – ${Format.clock(u.end)}",
                style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                color = RecoPalette.IconInk,
            )
        }
        Box(Modifier.fillMaxWidth().height(Stroke).background(c.ink))
        Text(
            u.text,
            style = MaterialTheme.typography.bodyLarge,
            color = c.ink,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** Проигрыватель в нижней панели: кнопка, полоса перемотки, время. */
@Composable
private fun PlayerBar(state: PlayerState, onToggle: () -> Unit, onSeek: (Long) -> Unit) {
    val c = Reco.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val value = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    TaskBar {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RetroIconButton(
                icon = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.playing) "Пауза" else "Воспроизвести",
                onClick = onToggle,
                enabled = state.ready,
                size = 50.dp,
                iconSize = 26.dp,
                fill = RecoPalette.Red,
                contentColor = RecoPalette.IconInk,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                SeekBar(
                    value = value,
                    enabled = state.ready,
                    onDrag = { dragging = it },
                    onDragEnd = {
                        dragging?.let { onSeek((it * duration).toLong()) }
                        dragging = null
                    },
                    onSeekTo = { onSeek((it * duration).toLong()) },
                )
                Row {
                    val shownMs = dragging?.let { (it * duration).toLong() } ?: state.positionMs
                    Text(
                        Format.clock(shownMs / 1000.0),
                        style = MaterialTheme.typography.labelSmall.merge(TabularNumbers),
                        color = c.ink,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        Format.clock(state.durationMs / 1000.0),
                        style = MaterialTheme.typography.labelSmall.merge(TabularNumbers),
                        color = c.muted,
                    )
                }
            }
        }
    }
}

/**
 * Полоса перемотки в рамке: заполненная часть — градиент иконки, бегунок — квадратная плашка с тенью.
 * Касание — переход к месту; перетаскивание — переход по отпусканию.
 */
@Composable
private fun SeekBar(
    value: Float,
    enabled: Boolean,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onSeekTo: (Float) -> Unit,
) {
    val c = Reco.colors
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val seekTo by rememberUpdatedState(onSeekTo)
    val thumbWidth = 16.dp
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        seekTo(target.coerceIn(0f, 1f))
                        true
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val half = thumbWidth.toPx() / 2
                detectTapGestures { o ->
                    seekTo(((o.x - half) / (size.width - half * 2)).coerceIn(0f, 1f))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val half = thumbWidth.toPx() / 2
                fun frac(x: Float) = ((x - half) / (size.width - half * 2)).coerceIn(0f, 1f)
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag(frac(o.x)) },
                    onDragEnd = { dragEnd() },
                    onDragCancel = { dragEnd() },
                ) { change, _ ->
                    change.consume()
                    drag(frac(change.position.x))
                }
            }
    ) {
        val sw = Stroke.toPx()
        val trackH = 12.dp.toPx()
        val top = (size.height - trackH) / 2
        val radius = CornerRadius(4.dp.toPx())
        val thumbW = thumbWidth.toPx()
        val thumbH = 26.dp.toPx()
        val center = thumbW / 2 + (size.width - thumbW) * value

        drawRoundRect(c.window, topLeft = Offset(0f, top), size = Size(size.width, trackH), cornerRadius = radius)
        if (enabled) {
            drawRoundRect(
                brush = Brush.horizontalGradient(RecoPalette.Wave.take(4), startX = 0f, endX = size.width),
                topLeft = Offset(0f, top),
                size = Size(center, trackH),
                cornerRadius = radius,
            )
        }
        drawRoundRect(
            if (enabled) c.ink else c.muted,
            topLeft = Offset(sw / 2, top + sw / 2),
            size = Size(size.width - sw, trackH - sw),
            cornerRadius = radius,
            style = DrawStroke(sw),
        )

        val left = center - thumbW / 2
        val thumbTop = (size.height - thumbH) / 2
        val shadow = 3.dp.toPx()
        val thumbRadius = CornerRadius(3.dp.toPx())
        if (enabled) {
            drawRoundRect(c.shadow, topLeft = Offset(left + shadow, thumbTop + shadow), size = Size(thumbW, thumbH), cornerRadius = thumbRadius)
        }
        drawRoundRect(c.window, topLeft = Offset(left, thumbTop), size = Size(thumbW, thumbH), cornerRadius = thumbRadius)
        drawRoundRect(
            if (enabled) c.ink else c.muted,
            topLeft = Offset(left + sw / 2, thumbTop + sw / 2),
            size = Size(thumbW - sw, thumbH - sw),
            cornerRadius = thumbRadius,
            style = DrawStroke(sw),
        )
    }
}

/** Выбор участника в окне редактирования. */
@Composable
private fun SpeakerOption(
    label: String,
    selected: Boolean,
    fill: Color?,
    onClick: () -> Unit,
    plus: Boolean = false,
) {
    val c = Reco.colors
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .padding(end = 3.dp, bottom = 3.dp)
            .hardShadow(if (selected) 3.dp else 0.dp, c.shadow, shape)
            .clip(shape)
            .background(if (selected) fill ?: RecoPalette.Yellow else c.window)
            .border(Stroke, if (selected) c.ink else c.muted, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val textColor = if (selected) RecoPalette.IconInk else c.ink
        when {
            selected -> Icon(Icons.Rounded.Check, contentDescription = null, tint = textColor, modifier = Modifier.size(16.dp))
            plus -> Icon(Icons.Rounded.Add, contentDescription = null, tint = textColor, modifier = Modifier.size(16.dp))
            fill != null -> SpeakerSwatch(fill, size = 14.dp)
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = textColor)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditUtteranceDialog(
    t: Transcript,
    u: Utterance,
    reprocess: ReprocessState,
    reprocessAllowed: Boolean,
    onReprocess: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (text: String, speakerId: Int?, byLlm: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val c = Reco.colors
    var text by remember(u) { mutableStateOf(u.text) }
    var fromLlm by remember(u) { mutableStateOf(u.byLlm) }
    /** -1 — «новый участник». */
    var speakerId by remember(u) { mutableStateOf(u.speakerId) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Результат повторной обработки подставляется в поле — пользователь видит его и решает, сохранять ли
    LaunchedEffect(reprocess) {
        if (reprocess is ReprocessState.Done) {
            text = reprocess.text
            fromLlm = reprocess.llmChanged
        }
    }

    RetroDialog(
        title = "Реплика · ${Format.clock(u.start)} – ${Format.clock(u.end)}",
        onDismiss = onDismiss,
        buttons = {
            RetroButton("Отмена", onClick = onDismiss, height = 44.dp, shadow = 3.dp)
            RetroButton(
                "Сохранить",
                onClick = { onSave(text.trim(), if (speakerId == -1) null else speakerId, fromLlm) },
                enabled = text.isNotBlank() && reprocess !is ReprocessState.Running,
                height = 44.dp,
                shadow = 3.dp,
                fill = RecoPalette.Yellow,
                contentColor = RecoPalette.IconInk,
            )
        },
    ) {
        Text("КТО ГОВОРИТ", style = MaterialTheme.typography.labelMedium, color = c.muted)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            t.speakers.forEach { s ->
                SpeakerOption(
                    label = Speaker.label(s.id),
                    selected = speakerId == s.id,
                    fill = speakerColor(s.color),
                    onClick = { speakerId = s.id },
                )
            }
            SpeakerOption(label = "Новый", selected = speakerId == -1, fill = null, plus = true, onClick = { speakerId = -1 })
        }
        Spacer(Modifier.height(16.dp))
        RetroTextField(
            value = text,
            onValueChange = {
                text = it
                fromLlm = false
            },
            minLines = 3,
            maxLines = 8,
        )
        Spacer(Modifier.height(14.dp))
        RetroButton(
            text = if (reprocess is ReprocessState.Running) "Обработка фрагмента…" else "Распознать заново",
            onClick = onReprocess,
            enabled = reprocessAllowed && reprocess !is ReprocessState.Running,
            icon = if (reprocess is ReprocessState.Running) null else Icons.Rounded.Refresh,
            leading = if (reprocess is ReprocessState.Running) {
                { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = c.ink) }
            } else null,
            height = 46.dp,
            shadow = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        val hint = when {
            reprocess is ReprocessState.Error -> reprocess.message
            !reprocessAllowed -> "Доступно, когда не идёт обработка других записей"
            reprocess is ReprocessState.Done -> "Новый текст подставлен в поле — проверьте и сохраните"
            else -> null
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (reprocess is ReprocessState.Error) c.danger else c.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Удалить реплику",
            style = MaterialTheme.typography.labelLarge,
            color = c.danger,
            modifier = Modifier
                .clickable(role = Role.Button) { confirmDelete = true }
                .padding(vertical = 10.dp),
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить реплику?",
            text = "Реплика исчезнет из расшифровки. Аудио не изменится.",
            confirmText = "Удалить",
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
        )
    }
}
