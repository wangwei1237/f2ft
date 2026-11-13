package com.fy.facetranslate.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fy.facetranslate.R
import com.fy.facetranslate.data.Language
import com.fy.facetranslate.session.Phase
import com.fy.facetranslate.session.Record
import com.fy.facetranslate.session.RecordStatus
import com.fy.facetranslate.session.UiState

/** Callbacks for one side's controls. */
class SideActions(
    val onSpeak: (Language) -> Unit,
    val onPress: (Language) -> Unit,
    val onRelease: (Language) -> Unit,
    val onType: (Language) -> Unit,
    val onRetry: () -> Unit,
    val onResume: () -> Unit,
    val onZoom: (Long) -> Unit,
)

/**
 * One person's half of the screen. Every label is in that person's own language;
 * in face-to-face mode the guest half is rotated 180° by the caller.
 */
@Composable
fun SidePanel(lang: Language, isGuest: Boolean, ui: UiState, actions: SideActions, modifier: Modifier = Modifier) {
    val phase = ui.phase
    val current = phase.side == lang
    val last = ui.records.lastOrNull()
    val capturing = current && (phase is Phase.Listening || phase is Phase.Recording)
    // Playback (including replay) is announced on both halves so the listener knows to listen.
    val playing = phase is Phase.Playing
    val cue = when {
        capturing && ui.connecting && phase is Phase.Listening -> Cue.CONNECTING
        capturing -> Cue.LISTENING
        playing -> Cue.PLAYING
        else -> Cue.NONE
    }

    val status = when {
        playing -> lang.playing
        !current -> lang.label
        phase is Phase.Listening -> if (ui.connecting) lang.connecting else lang.yourTurn
        phase is Phase.Recording -> lang.listening
        phase is Phase.Translating -> lang.translating
        phase is Phase.Playing -> lang.playing
        phase is Phase.NetworkError -> lang.networkError
        phase is Phase.Paused -> lang.paused
        ui.holdMode && ui.micAllowed -> lang.holdButton
        else -> lang.yourTurn
    }

    val showPartial = current && ui.partialOriginal.isNotEmpty() &&
        (phase is Phase.Listening || phase is Phase.Recording || phase is Phase.Translating)
    val mainText = when {
        showPartial -> ui.partialOriginal
        last == null -> lang.ready
        last.speaker == lang -> last.original
        last.translation.isNotEmpty() -> last.translation
        last.status == RecordStatus.FAILED -> "…"
        else -> lang.translating
    }

    Column(
        modifier
            .background(if (isGuest) Palette.GuestPanel else Palette.OwnPanel, RoundedCornerShape(18.dp))
            .border(if (current) 2.dp else 0.dp, if (current) Palette.TurnBorder else Palette.Line, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusLine(status, cue, current)
        ui.notice?.takeIf { it.first == lang }?.let { Text(it.second, color = Palette.Warn, fontSize = 15.sp) }

        Box(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .clickable(enabled = last != null && !showPartial) { last?.let { actions.onZoom(it.id) } },
        ) {
            Text(mainText, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                phase is Phase.Paused -> PrimaryButton(lang.resumeButton, actions.onResume)
                current && phase is Phase.NetworkError -> PrimaryButton(lang.retryButton, actions.onRetry)
                !ui.micAllowed -> Unit
                ui.holdMode -> HoldButton(lang, recording = current && phase is Phase.Recording, actions)
                else -> PrimaryButton(lang.speakButton, { actions.onSpeak(lang) }, enabled = !capturing)
            }
            SecondaryButton(lang.typeButton, { actions.onType(lang) })
        }
    }
}

@Composable
private fun HoldButton(lang: Language, recording: Boolean, actions: SideActions) {
    Box(
        Modifier
            .heightIn(min = 52.dp)
            .background(Palette.Primary, RoundedCornerShape(14.dp))
            .pointerInput(lang) {
                detectTapGestures(onPress = {
                    actions.onPress(lang)
                    tryAwaitRelease()
                    actions.onRelease(lang)
                })
            }
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (recording) lang.releaseButton else lang.holdButton, color = androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.SemiBold)
    }
}

private enum class Cue { NONE, CONNECTING, LISTENING, PLAYING }

/**
 * Status line above the subtitle. Connecting uses a warning color and a spinner so it is not
 * mistaken for "speak now"; listening and playback pulse an icon together with the text.
 */
@Composable
private fun StatusLine(text: String, cue: Cue, current: Boolean) {
    when (cue) {
        Cue.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Warn, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(text, color = Palette.Warn, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Cue.LISTENING, Cue.PLAYING -> {
            val transition = rememberInfiniteTransition(label = "cue")
            val a by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "alpha")
            Row(Modifier.alpha(a), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(if (cue == Cue.LISTENING) R.drawable.ic_mic else R.drawable.ic_volume),
                    contentDescription = null,
                    tint = Palette.Primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text, color = Palette.Primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Cue.NONE -> Text(text, color = Palette.Muted, fontSize = 14.sp, fontWeight = if (current) FontWeight.SemiBold else null)
    }
}

fun Modifier.flipped(enabled: Boolean) = if (enabled) rotate(180f) else this

/** Bilingual line used in the record list. */
@Composable
fun RecordLine(record: Record, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(record.original, color = Palette.Muted, fontSize = 14.sp)
        Text(record.translation.ifEmpty { "…" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}
