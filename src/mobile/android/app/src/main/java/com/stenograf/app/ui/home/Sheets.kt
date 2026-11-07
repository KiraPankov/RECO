package com.stenograf.app.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stenograf.app.record.RecordingState
import com.stenograf.app.ui.components.ConfirmDialog
import com.stenograf.app.ui.components.LevelBars
import com.stenograf.app.ui.components.RetroIconButton
import com.stenograf.app.ui.components.RetroWindow
import com.stenograf.app.ui.theme.Reco
import com.stenograf.app.ui.theme.RecoPalette
import com.stenograf.app.ui.theme.RecordRed
import com.stenograf.app.ui.theme.TabularNumbers
import com.stenograf.app.util.Format

/**
 * Окно записи. Не закрывается касанием вне окна и кнопкой «Назад» — только кнопками
 * «Отменить» и «Завершить», чтобы запись не потерялась случайно.
 */
@Composable
fun RecordSheet(
    state: RecordingState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    onFinish: () -> Unit,
) {
    val c = Reco.colors
    var confirmDiscard by remember { mutableStateOf(false) }

    val blink = rememberInfiniteTransition(label = "blink")
    val dotAlpha by blink.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dot",
    )

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        RetroWindow(
            title = if (state.paused) "Запись — пауза" else "Запись",
            titleFill = if (state.paused) c.titleBar else RecoPalette.Red,
            shadow = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(
                        Modifier
                            .size(12.dp)
                            .alpha(if (state.paused) 1f else dotAlpha)
                            .clip(CircleShape)
                            .background(if (state.paused) c.muted else RecordRed)
                            .border(1.5.dp, c.ink, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.paused) "ПАУЗА" else "ИДЁТ ЗАПИСЬ",
                        style = MaterialTheme.typography.labelLarge,
                        color = c.ink,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    Format.clock(state.elapsedMs / 1000.0),
                    style = MaterialTheme.typography.displayMedium.merge(TabularNumbers),
                    color = c.ink,
                )
                Spacer(Modifier.height(20.dp))
                LevelBars(levels = state.levels, active = !state.paused)
                Spacer(Modifier.height(26.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RetroIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = "Отменить запись",
                        onClick = { if (state.elapsedMs > 5_000) confirmDiscard = true else onDiscard() },
                        size = 56.dp,
                    )
                    RetroIconButton(
                        icon = Icons.Rounded.Check,
                        contentDescription = "Завершить запись",
                        onClick = onFinish,
                        size = 76.dp,
                        iconSize = 36.dp,
                        fill = RecoPalette.Yellow,
                        contentColor = RecoPalette.IconInk,
                        shadow = 5.dp,
                    )
                    RetroIconButton(
                        icon = if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = if (state.paused) "Продолжить" else "Пауза",
                        onClick = if (state.paused) onResume else onPause,
                        size = 56.dp,
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Отменить запись?",
            text = "Записанный звук будет удалён.",
            confirmText = "Удалить",
            destructive = true,
            onDismiss = { confirmDiscard = false },
            onConfirm = {
                confirmDiscard = false
                onDiscard()
            },
        )
    }
}
