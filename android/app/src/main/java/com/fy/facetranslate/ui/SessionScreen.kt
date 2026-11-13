package com.fy.facetranslate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fy.facetranslate.data.Language
import com.fy.facetranslate.session.Phase
import com.fy.facetranslate.session.UiState

class SessionActions(
    val side: SideActions,
    val onEnd: () -> Unit,
    val onToggleFace: () -> Unit,
    val onToggleHold: () -> Unit,
    val onReplay: (Long) -> Unit,
    val onCloseZoom: () -> Unit,
    val onSendTyped: (Language, String) -> Unit,
    val onCancelTyping: () -> Unit,
    val onRequestMic: () -> Unit,
    val onPauseForDialog: () -> Unit,
    val onResumeAfterDialog: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionScreen(ui: UiState, actions: SessionActions) {
    val owner = ui.owner
    val guest = owner.other()
    var confirmEnd by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Palette.Background).safeDrawingPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("双向对话", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            SecondaryButton("结束", { actions.onPauseForDialog(); confirmEnd = true })
        }
        ui.errorDetail?.let { Text("连接错误：$it", color = Palette.Error, fontSize = 12.sp, maxLines = 2) }
        if (!ui.micAllowed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("未获得麦克风权限，可用打字交流。", fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.onRequestMic) { Text("重新授权") }
            }
        }

        SidePanel(guest, isGuest = true, ui = ui, actions = actions.side,
            modifier = Modifier.weight(1f).fillMaxWidth().flipped(ui.faceToFace))
        SidePanel(owner, isGuest = false, ui = ui, actions = actions.side,
            modifier = Modifier.weight(1f).fillMaxWidth())

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton(if (ui.faceToFace) "并排阅读" else "面对面 ⇅", actions.onToggleFace)
            SecondaryButton(if (ui.holdMode) "切到自动" else "按住说话模式", actions.onToggleHold, enabled = ui.micAllowed)
            val last = ui.records.lastOrNull()
            if (last != null) {
                SecondaryButton("放大 / 记录", { actions.side.onZoom(last.id) })
                val busy = ui.phase is Phase.Translating || (ui.phase as? Phase.Playing)?.replay == false
                SecondaryButton("重播最近译文", { actions.onReplay(last.id) }, enabled = !busy && last.audio != null)
            }
        }
    }

    ui.typingSide?.let { TypingDialog(it, actions) }
    ui.zoomRecordId?.let { ZoomDialog(ui, it, actions) }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false; actions.onResumeAfterDialog() },
            title = { Text("结束并清除本次对话？") },
            text = { Text("将停止录音和播放，并清除本次所有记录。") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; actions.onEnd() }) { Text("结束") } },
            dismissButton = {
                TextButton(onClick = { confirmEnd = false; actions.onResumeAfterDialog() }) { Text("继续对话") }
            },
        )
    }
}

@Composable
private fun TypingDialog(side: Language, actions: SessionActions) {
    var text by remember(side) { mutableStateOf("") }
    Dialog(onDismissRequest = actions.onCancelTyping) {
        Column(
            Modifier.fillMaxWidth().background(Palette.Background, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .imePadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(side.typeButton + " · " + side.label, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(side.send, { actions.onSendTyped(side, text) }, enabled = text.isNotBlank())
                SecondaryButton(side.cancel, actions.onCancelTyping)
            }
        }
    }
}

@Composable
private fun ZoomDialog(ui: UiState, recordId: Long, actions: SessionActions) {
    val record = ui.records.firstOrNull { it.id == recordId } ?: return
    var selected by remember(recordId) { mutableStateOf(record.id) }
    val shown = ui.records.firstOrNull { it.id == selected } ?: record
    val listener = shown.speaker.other()
    Dialog(onDismissRequest = actions.onCloseZoom, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Palette.Background).safeDrawingPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("放大译文 · ${listener.label}", color = Palette.Muted)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(shown.translation.ifEmpty { "…" }, fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
                Text(shown.original, fontSize = 20.sp, color = Palette.Muted, modifier = Modifier.padding(top = 12.dp))
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                ui.records.asReversed().forEach { r -> RecordLine(r) { selected = r.id } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("返回 / Back", actions.onCloseZoom)
                SecondaryButton("重播 / ${listener.replayButton}", { actions.onCloseZoom(); actions.onReplay(shown.id) }, enabled = shown.audio != null)
            }
        }
    }
}
