package com.stenograf.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stenograf.app.data.Speaker
import com.stenograf.app.data.Transcript
import com.stenograf.app.models.ModelsState
import com.stenograf.app.ui.appContainer
import com.stenograf.app.ui.components.ConfirmDialog
import com.stenograf.app.ui.components.DesktopBackground
import com.stenograf.app.ui.components.MenuBar
import com.stenograf.app.ui.components.MenuClock
import com.stenograf.app.ui.components.RecoLogo
import com.stenograf.app.ui.components.RetroButton
import com.stenograf.app.ui.components.RetroPopupMenu
import com.stenograf.app.ui.components.RetroMenuItem
import com.stenograf.app.ui.components.RetroProgress
import com.stenograf.app.ui.components.RetroWindow
import com.stenograf.app.ui.components.SpeakerSwatch
import com.stenograf.app.ui.components.Stroke
import com.stenograf.app.ui.components.TaskBar
import com.stenograf.app.ui.components.TitleBarClose
import com.stenograf.app.ui.components.hardShadow
import com.stenograf.app.ui.theme.Reco
import com.stenograf.app.ui.theme.RecoPalette
import com.stenograf.app.ui.theme.speakerColor
import com.stenograf.app.util.Format
import com.stenograf.app.work.JobUi

/** Типы файлов в системном диалоге выбора: аудио и видео со звуковой дорожкой. */
private val AUDIO_MIME = arrayOf("audio/*", "video/*", "application/ogg")

@Composable
fun HomeScreen(onOpenTranscript: (String) -> Unit) {
    val container = appContainer()
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val context = LocalContext.current

    val transcripts by vm.transcripts.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()

    var deleteTarget by remember { mutableStateOf<Transcript?>(null) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }

    // Уведомление нужно, чтобы видеть прогресс, когда приложение свёрнуто
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun beginRecording() {
        if (!vm.startRecording()) Toast.makeText(context, "Не удалось начать запись", Toast.LENGTH_SHORT).show()
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording()
        else Toast.makeText(context, "Без доступа к микрофону запись невозможна", Toast.LENGTH_LONG).show()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ensureNotifications()
            vm.import(uri)
        }
    }

    Box(Modifier.fillMaxSize()) {
        DesktopBackground()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                MenuBar(trailing = { MenuClock() }) {
                    RecoLogo(34.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("RECO", style = MaterialTheme.typography.titleLarge, color = Reco.colors.ink)
                }
            },
            bottomBar = {
                BottomActions(
                    onImport = { picker.launch(AUDIO_MIME) },
                    onRecord = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) beginRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = padding.calculateTopPadding() + 14.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (models !is ModelsState.Ready) {
                    item(key = "models") { ModelsCard(models) }
                }

                if (jobs.isNotEmpty()) {
                    item(key = "jobs-label") { SectionTab("В работе", jobs.size) }
                    items(jobs, key = { "job-" + it.id }) { job ->
                        JobCard(
                            job = job,
                            onCancel = { vm.cancelJob(job.id) },
                            onDismiss = { vm.dismissJob(job.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                if (transcripts.isNotEmpty()) {
                    item(key = "list-label") { SectionTab("Расшифровки", transcripts.size) }
                    items(transcripts, key = { it.id }) { t ->
                        TranscriptCard(
                            t = t,
                            onClick = { onOpenTranscript(t.id) },
                            onDelete = { deleteTarget = t },
                            modifier = Modifier.animateItem(),
                        )
                    }
                } else if (loaded && jobs.isEmpty()) {
                    item(key = "empty") { EmptyState() }
                }
            }
        }
    }

    if (recording.active) {
        RecordSheet(
            state = recording,
            onPause = vm::pauseRecording,
            onResume = vm::resumeRecording,
            onDiscard = vm::discardRecording,
            onFinish = {
                ensureNotifications()
                if (!vm.finishRecording()) Toast.makeText(context, "Запись пустая", Toast.LENGTH_SHORT).show()
            },
        )
    }

    deleteTarget?.let { t ->
        ConfirmDialog(
            title = "Удалить расшифровку?",
            text = "«${t.title}» и аудиозапись будут удалены без возможности восстановления.",
            confirmText = "Удалить",
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                vm.delete(t.id)
                deleteTarget = null
            },
        )
    }
}

/** Нижняя панель (как панель задач) с двумя основными действиями. */
@Composable
private fun BottomActions(onImport: () -> Unit, onRecord: () -> Unit) {
    TaskBar {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RetroButton(
                text = "Импорт",
                icon = Icons.Rounded.FileOpen,
                onClick = onImport,
                fill = RecoPalette.Yellow,
                contentColor = RecoPalette.IconInk,
                modifier = Modifier.weight(1f),
            )
            RetroButton(
                text = "Запись",
                icon = Icons.Rounded.Mic,
                onClick = onRecord,
                fill = RecoPalette.Red,
                contentColor = RecoPalette.IconInk,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Заголовок раздела — «вкладка папки» с числом элементов. */
@Composable
private fun SectionTab(text: String, count: Int) {
    val c = Reco.colors
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .padding(top = 6.dp)
            .clip(shape)
            .background(c.window)
            .border(Stroke, c.ink, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = c.ink)
        Spacer(Modifier.width(8.dp))
        Text("[$count]", style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

/** Первый запуск: модели копируются из APK во внутреннюю память. */
@Composable
private fun ModelsCard(state: ModelsState) {
    val c = Reco.colors
    val failed = state is ModelsState.Failed
    RetroWindow(
        title = if (failed) "Ошибка" else "Подготовка моделей",
        titleFill = if (failed) RecoPalette.Red else c.titleBar,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is ModelsState.Failed -> {
                    Text("Модели не готовы", style = MaterialTheme.typography.titleSmall, color = c.danger)
                    Spacer(Modifier.height(4.dp))
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = c.muted)
                }

                else -> {
                    val p = (state as? ModelsState.Preparing)?.progress
                    Text(
                        "Первый запуск: модели распаковываются из приложения. Интернет не нужен.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.ink,
                    )
                    Spacer(Modifier.height(12.dp))
                    RetroProgress(p)
                    if (p != null) {
                        Spacer(Modifier.height(6.dp))
                        Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = c.muted)
                    }
                }
            }
        }
    }
}

/** Задача в очереди — окно с именем файла, этапом и индикатором, как окно копирования файлов. */
@Composable
private fun JobCard(job: JobUi, onCancel: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Reco.colors
    val failed = job.error != null
    RetroWindow(
        title = job.title,
        titleFill = if (failed) RecoPalette.Red else c.titleBar,
        modifier = modifier.fillMaxWidth(),
        trailing = {
            TitleBarClose(
                contentDescription = if (failed) "Закрыть" else "Отменить",
                onClick = if (failed) onDismiss else onCancel,
            )
        },
    ) {
        Column(Modifier.padding(16.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (failed) c.danger else c.ink,
                    modifier = Modifier.weight(1f),
                )
                if (!failed) job.progress?.let {
                    Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = c.ink)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (failed) {
                Text(job.error ?: "", style = MaterialTheme.typography.bodySmall, color = c.muted)
            } else {
                RetroProgress(if (job.waiting) null else job.progress)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptCard(t: Transcript, onClick: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val c = Reco.colors
    val shape = RoundedCornerShape(10.dp)
    var menu by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(end = 4.dp, bottom = 4.dp)
                .hardShadow(4.dp, c.shadow, shape)
                .clip(shape)
                .background(c.window)
                .border(Stroke, c.ink, shape)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeakerStack(t.activeSpeakers)
                Spacer(Modifier.weight(1f))
                Text(Format.date(t.createdAt), style = MaterialTheme.typography.labelSmall, color = c.muted)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                t.title,
                style = MaterialTheme.typography.titleMedium,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${Format.clock(t.durationSec)}  ·  ${Format.participants(t.activeSpeakers.size)}",
                style = MaterialTheme.typography.labelMedium,
                color = c.muted,
            )
            t.utterances.firstOrNull()?.let { u ->
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.5.dp).background(c.track))
                Spacer(Modifier.height(8.dp))
                Text(
                    "${Speaker.label(u.speakerId)}: ${u.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (menu) {
            RetroPopupMenu(onDismiss = { menu = false }) {
                RetroMenuItem("Удалить", color = c.danger, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}

/** Цветные квадратики участников, слегка наложенные друг на друга. */
@Composable
private fun SpeakerStack(speakers: List<Speaker>) {
    val shown = speakers.take(6)
    if (shown.isEmpty()) {
        Spacer(Modifier.height(16.dp))
        return
    }
    Box(Modifier.width((16 + 11 * (shown.size - 1)).dp).height(16.dp)) {
        shown.forEachIndexed { i, s ->
            SpeakerSwatch(speakerColor(s.color), size = 16.dp, modifier = Modifier.offset(x = (11 * i).dp))
        }
    }
}

/** Пустой список — окно приветствия. */
@Composable
private fun EmptyState() {
    val c = Reco.colors
    RetroWindow(title = "Добро пожаловать!", modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RecoLogo(76.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                "Здесь появятся расшифровки",
                style = MaterialTheme.typography.titleMedium,
                color = c.ink,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Импортируйте аудиозапись из памяти телефона или запишите разговор. Приложение определит участников и разложит диалог по репликам с временными метками — полностью на устройстве.",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 1, 2, 3).forEach { SpeakerSwatch(speakerColor(it), size = 14.dp) }
            }
        }
    }
}
