package com.fy.facetranslate.session

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.fy.facetranslate.audio.MicRecorder
import com.fy.facetranslate.audio.PcmPlayer
import com.fy.facetranslate.data.AppSettings
import com.fy.facetranslate.data.Language
import com.fy.facetranslate.data.SettingsStore
import com.fy.facetranslate.live.GeminiLiveClient
import com.fy.facetranslate.live.LiveConfig
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Turn-taking state machine for a two-person conversation.
 *
 * Each direction (ZH→EN, EN→ZH) has its own pre-connected Live API session, so the
 * microphone audio of the current side goes straight to a session that already knows
 * the source and target language. All state changes happen on the main thread.
 */
class ConversationViewModel(app: Application) : AndroidViewModel(app), GeminiLiveClient.Listener {

    var ui by mutableStateOf(UiState())
        private set

    private val store = SettingsStore(app)
    var settings: AppSettings = store.load()
        private set

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val clients = mutableMapOf<Language, GeminiLiveClient>()
    private val player = PcmPlayer()
    private val mic = MicRecorder(::onMicChunk)
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    @Volatile private var active: Turn? = null
    private var nextRecordId = 1L
    private val translatingTimer = Runnable {
        val p = ui.phase
        if (p is Phase.Listening && active?.outputStarted == false) ui = ui.copy(phase = Phase.Translating(p.side))
    }

    private enum class Kind { SPEECH, TEXT, REPLAY }

    private class Turn(val kind: Kind, val side: Language, val client: GeminiLiveClient?, val recordId: Long) {
        val original = StringBuilder()
        val translation = StringBuilder()
        val audio = ByteArrayOutputStream()
        var outputStarted = false
        var completed = false
        var sentInput = false
        var playerGen = 0

        // Hold-to-talk: audio is buffered until the press lasts long enough to count as speech.
        val lock = Any()
        var pressAt = 0L
        var activityStarted = false
        var released = false
        val preroll = ArrayList<ByteArray>()
    }

    init {
        ui = ui.copy(owner = settings.ownerLanguage, needsApiKey = settings.apiKey.isBlank())
    }

    // ---------------------------------------------------------------- navigation & settings

    fun openSettings() {
        ui = ui.copy(screen = Screen.SETTINGS)
    }

    fun saveSettings(new: AppSettings) {
        store.save(new)
        settings = store.load()
        closeClients()
        ui = ui.copy(screen = Screen.HOME, needsApiKey = settings.apiKey.isBlank(), errorDetail = null)
    }

    fun closeSettings() {
        ui = ui.copy(screen = Screen.HOME)
    }

    /** Opens a throwaway session with [candidate] and reports null on success or the error text. */
    fun testConnection(candidate: AppSettings, onResult: (String?) -> Unit) {
        val config = LiveConfig(
            apiKey = candidate.apiKey.trim(), model = candidate.model.trim(), voice = candidate.voice.trim(),
            source = Language.ZH, target = Language.EN, manualActivity = false, silenceMs = candidate.silenceMs,
        )
        var done = false
        val finish = { error: String? -> main.post { if (!done) { done = true; onResult(error) } } }
        val probe = GeminiLiveClient(http, config, object : GeminiLiveClient.Listener {
            override fun onReady(client: GeminiLiveClient) { finish(null); client.close() }
            override fun onInputTranscript(client: GeminiLiveClient, text: String) = Unit
            override fun onOutputTranscript(client: GeminiLiveClient, text: String) = Unit
            override fun onAudio(client: GeminiLiveClient, pcm: ByteArray) = Unit
            override fun onTurnComplete(client: GeminiLiveClient) = Unit
            override fun onClosed(client: GeminiLiveClient, error: String?) { finish(error ?: "connection closed") }
        })
        probe.connect()
        main.postDelayed({ if (!done) { probe.close(); finish("timeout") } }, 10_000)
    }

    fun swapLanguages() {
        settings = settings.copy(ownerLanguage = ui.owner.other())
        store.save(settings)
        ui = ui.copy(owner = settings.ownerLanguage)
    }

    fun setMicAllowed(allowed: Boolean) {
        if (ui.micAllowed == allowed) return
        ui = ui.copy(micAllowed = allowed)
        val p = ui.phase
        if (ui.screen == Screen.SESSION && allowed && p is Phase.Waiting && active == null) beginTurn(p.side)
    }

    /** Opens a session; [first] is the side that speaks first. */
    fun startSession(first: Language) {
        if (settings.apiKey.isBlank()) {
            ui = ui.copy(needsApiKey = true, screen = Screen.SETTINGS)
            return
        }
        nextRecordId = 1
        ui = ui.copy(
            screen = Screen.SESSION, records = emptyList(), partialOriginal = "", notice = null,
            errorDetail = null, typingSide = null, zoomRecordId = null,
        )
        ensureClient(first)
        ensureClient(first.other())
        beginTurn(first)
    }

    fun endSession() {
        cancelInFlight()
        closeClients()
        ui = ui.copy(
            screen = Screen.HOME, records = emptyList(), partialOriginal = "", notice = null,
            typingSide = null, zoomRecordId = null, phase = Phase.Waiting(ui.owner),
        )
    }

    fun toggleFaceToFace() {
        ui = ui.copy(faceToFace = !ui.faceToFace)
    }

    fun toggleHoldMode() {
        val side = ui.phase.side
        cancelInFlight()
        ui = ui.copy(holdMode = !ui.holdMode)
        // Server-side voice activity detection is a setup option, so both sessions are reopened.
        closeClients()
        ensureClient(side)
        ensureClient(side.other())
        beginTurn(side)
    }

    // ---------------------------------------------------------------- user actions

    /** Language button: hand the turn to [side] right away, interrupting playback or a pending request. */
    fun takeTurn(side: Language) = beginTurn(side)

    fun pressToTalk(side: Language) {
        if (!ui.micAllowed) return
        cancelInFlight()
        val client = ensureClient(side)
        val turn = Turn(Kind.SPEECH, side, client, nextRecordId++).apply { pressAt = SystemClock.elapsedRealtime() }
        active = turn
        ui = ui.copy(phase = Phase.Recording(side), partialOriginal = "", notice = null)
        if (!mic.start()) micFailed(side)
    }

    fun releaseToTalk(side: Language) {
        val turn = active ?: return
        if (turn.side != side || ui.phase !is Phase.Recording) return
        mic.stop()
        val started = synchronized(turn.lock) {
            turn.released = true
            if (turn.activityStarted) turn.client?.sendActivityEnd()
            turn.activityStarted
        }
        if (!started) {
            // Too short to be speech: nothing was sent, so the session stays clean.
            active = null
            ui = ui.copy(phase = Phase.Waiting(side), notice = side to side.retry)
            return
        }
        ui = ui.copy(phase = Phase.Translating(side))
    }

    fun openKeyboard(side: Language) {
        val resume = ui.phase.side
        cancelInFlight()
        ui = ui.copy(typingSide = side, phase = Phase.Waiting(resume), notice = null)
    }

    fun cancelKeyboard() {
        ui = ui.copy(typingSide = null)
        beginTurn(ui.phase.side)
    }

    fun sendTyped(side: Language, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        ui = ui.copy(typingSide = null)
        cancelInFlight()
        val client = ensureClient(side)
        val turn = Turn(Kind.TEXT, side, client, nextRecordId++).apply { original.append(clean); sentInput = true }
        active = turn
        upsertRecord(Record(turn.recordId, side, clean))
        ui = ui.copy(phase = Phase.Translating(side), partialOriginal = "", notice = null)
        client.sendText(clean)
    }

    /** Re-translates the kept original after a network failure, updating the same record. */
    fun retry() {
        val p = ui.phase as? Phase.NetworkError ?: return
        val record = p.recordId?.let { id -> ui.records.firstOrNull { it.id == id } }
        if (record == null || record.original.isBlank()) {
            beginTurn(p.side)
            return
        }
        val client = ensureClient(p.side)
        val turn = Turn(Kind.TEXT, p.side, client, record.id).apply { original.append(record.original); sentInput = true }
        active = turn
        upsertRecord(record.copy(translation = "", status = RecordStatus.PENDING, audio = null))
        ui = ui.copy(phase = Phase.Translating(p.side), notice = null)
        client.sendText(record.original)
    }

    /** Replays a translation with the mic closed; afterwards the turn returns to whoever had it. */
    fun replay(recordId: Long) {
        val record = ui.records.firstOrNull { it.id == recordId } ?: return
        val resume = ui.phase.side
        if (ui.phase is Phase.Translating || (ui.phase as? Phase.Playing)?.replay == false) return
        cancelInFlight()
        val audio = record.audio
        if (audio == null || audio.isEmpty()) {
            ui = ui.copy(notice = record.speaker.other() to record.speaker.other().playFailed)
            beginTurn(resume)
            return
        }
        val turn = Turn(Kind.REPLAY, resume, null, recordId)
        active = turn
        requestFocus()
        turn.playerGen = player.begin()
        player.write(turn.playerGen, audio)
        ui = ui.copy(phase = Phase.Playing(record.speaker, replay = true), notice = null)
        player.finish(turn.playerGen) {
            main.post {
                if (active !== turn) return@post
                active = null
                abandonFocus()
                beginTurn(resume)
            }
        }
    }

    fun openZoom(recordId: Long) {
        // Stop an open microphone so nothing is captured while the phone is shown to the other person.
        if (ui.phase is Phase.Listening && active?.outputStarted != true) {
            cancelInFlight()
            ui = ui.copy(phase = Phase.Waiting(ui.phase.side))
        }
        ui = ui.copy(zoomRecordId = recordId)
    }

    fun closeZoom() {
        ui = ui.copy(zoomRecordId = null)
        val p = ui.phase
        if (p is Phase.Waiting && active == null) beginTurn(p.side)
    }

    /** Background, lock screen, incoming call: stop capture and playback; user continues manually. */
    fun pause() {
        if (ui.screen != Screen.SESSION || ui.phase is Phase.Paused) return
        val side = ui.phase.side
        cancelInFlight()
        ui = ui.copy(phase = Phase.Paused(side), typingSide = null)
    }

    fun resume() {
        val p = ui.phase as? Phase.Paused ?: return
        beginTurn(p.side)
    }

    // ---------------------------------------------------------------- turn engine

    private fun beginTurn(side: Language) {
        cancelInFlight()
        ui = ui.copy(partialOriginal = "")
        if (ui.holdMode || !ui.micAllowed) {
            ui = ui.copy(phase = Phase.Waiting(side))
            return
        }
        val client = ensureClient(side)
        active = Turn(Kind.SPEECH, side, client, nextRecordId++)
        ui = ui.copy(phase = Phase.Listening(side))
        if (!mic.start()) micFailed(side)
    }

    private fun micFailed(side: Language) {
        active = null
        ui = ui.copy(phase = Phase.Waiting(side), micAllowed = false)
    }

    /**
     * Stops whatever is in progress. A session that already received part of an unfinished
     * turn is closed and reopened, so its late results can never land in a newer turn.
     */
    private fun cancelInFlight() {
        main.removeCallbacks(translatingTimer)
        val turn = active ?: return
        active = null
        mic.stop()
        player.stop()
        abandonFocus()
        if (turn.kind == Kind.REPLAY) return
        if (!turn.completed) {
            if (turn.kind == Kind.TEXT) {
                updateRecord(turn.recordId) { it.copy(status = RecordStatus.FAILED) }
            } else if (turn.outputStarted) {
                ui = ui.copy(records = ui.records.filterNot { it.id == turn.recordId })
            }
            val client = turn.client
            if (client != null && turn.sentInput && clients[turn.side] === client) {
                clients.remove(turn.side)
                client.close()
                if (ui.screen == Screen.SESSION) ensureClient(turn.side)
            }
        }
    }

    private fun ensureClient(source: Language): GeminiLiveClient {
        clients[source]?.takeIf { it.state != GeminiLiveClient.State.CLOSED }?.let { return it }
        val config = LiveConfig(
            apiKey = settings.apiKey,
            model = settings.model,
            voice = settings.voice,
            source = source,
            target = source.other(),
            manualActivity = ui.holdMode,
            silenceMs = settings.silenceMs,
        )
        val client = GeminiLiveClient(http, config, this)
        clients[source] = client
        ui = ui.copy(connecting = true)
        client.connect()
        return client
    }

    private fun closeClients() {
        clients.values.forEach { it.close() }
        clients.clear()
    }

    /** Microphone thread. */
    private fun onMicChunk(pcm: ByteArray) {
        val turn = active ?: return
        val client = turn.client ?: return
        if (turn.outputStarted) return
        if (!client.config.manualActivity) {
            turn.sentInput = true
            client.sendAudio(pcm)
            return
        }
        synchronized(turn.lock) {
            if (turn.released) return
            if (turn.activityStarted) {
                client.sendAudio(pcm)
                return
            }
            turn.preroll.add(pcm)
            if (SystemClock.elapsedRealtime() - turn.pressAt >= MIN_PRESS_MS) {
                turn.activityStarted = true
                turn.sentInput = true
                client.sendActivityStart()
                turn.preroll.forEach(client::sendAudio)
                turn.preroll.clear()
            }
        }
    }

    // ---------------------------------------------------------------- Live API callbacks

    /** Runs [block] on the main thread with the active turn, only if [client] belongs to it. */
    private inline fun onTurnOf(client: GeminiLiveClient, crossinline block: (Turn) -> Unit) {
        main.post {
            val turn = active ?: return@post
            if (turn.client === client && clients[turn.side] === client) block(turn)
        }
    }

    override fun onReady(client: GeminiLiveClient) {
        main.post {
            if (clients.values.none { it.state == GeminiLiveClient.State.CONNECTING }) ui = ui.copy(connecting = false)
        }
    }

    override fun onInputTranscript(client: GeminiLiveClient, text: String) = onTurnOf(client) { turn ->
        turn.original.append(text)
        val original = turn.original.toString().trim()
        if (turn.outputStarted) {
            updateRecord(turn.recordId) { it.copy(original = original) }
        } else {
            ui = ui.copy(partialOriginal = original, notice = null)
            // A pause in the incoming transcript means the sentence was submitted.
            main.removeCallbacks(translatingTimer)
            if (ui.phase is Phase.Listening) main.postDelayed(translatingTimer, TRANSLATING_HINT_MS)
        }
    }

    override fun onOutputTranscript(client: GeminiLiveClient, text: String) = onTurnOf(client) { turn ->
        turn.translation.append(text)
        startOutput(turn)
        updateRecord(turn.recordId) { it.copy(translation = turn.translation.toString().trim()) }
    }

    override fun onAudio(client: GeminiLiveClient, pcm: ByteArray) = onTurnOf(client) { turn ->
        startOutput(turn)
        if (turn.playerGen == 0) {
            requestFocus()
            turn.playerGen = player.begin()
        }
        turn.audio.write(pcm)
        player.write(turn.playerGen, pcm)
    }

    /** First translated text or audio: the speaker's sentence is fixed and the mic closes. */
    private fun startOutput(turn: Turn) {
        if (turn.outputStarted) return
        turn.outputStarted = true
        mic.stop()
        main.removeCallbacks(translatingTimer)
        upsertRecord(Record(turn.recordId, turn.side, turn.original.toString().trim()))
        ui = ui.copy(phase = Phase.Playing(turn.side, replay = false), partialOriginal = "", notice = null)
    }

    override fun onTurnComplete(client: GeminiLiveClient) = onTurnOf(client) { turn ->
        main.removeCallbacks(translatingTimer)
        if (!turn.outputStarted) {
            // Noise or unclear speech: say so on the same side and let them try again.
            if (turn.kind == Kind.TEXT) {
                // Keep the typed or retried original so it can be sent again without a duplicate record.
                active = null
                updateRecord(turn.recordId) { it.copy(status = RecordStatus.FAILED) }
                ui = ui.copy(phase = Phase.NetworkError(turn.side, turn.recordId), notice = turn.side to turn.side.retry)
                return@onTurnOf
            }
            val side = turn.side
            if (!client.config.manualActivity && mic.isRunning) {
                turn.original.clear()
                ui = ui.copy(phase = Phase.Listening(side), partialOriginal = "", notice = side to side.retry)
            } else {
                active = null
                mic.stop()
                ui = ui.copy(phase = Phase.Waiting(side), partialOriginal = "", notice = side to side.retry)
            }
            return@onTurnOf
        }
        turn.completed = true
        val audio = turn.audio.toByteArray()
        updateRecord(turn.recordId) {
            it.copy(
                original = turn.original.toString().trim().ifEmpty { it.original },
                translation = turn.translation.toString().trim(),
                status = if (audio.isEmpty()) RecordStatus.NO_AUDIO else RecordStatus.DONE,
                audio = audio.takeIf { a -> a.isNotEmpty() },
            )
        }
        if (turn.playerGen == 0) {
            onPlaybackDone(turn, played = false)
        } else {
            player.finish(turn.playerGen) { played -> main.post { if (active === turn) onPlaybackDone(turn, played) } }
        }
    }

    private fun onPlaybackDone(turn: Turn, played: Boolean) {
        active = null
        abandonFocus()
        val next = turn.side.other()
        if (played) {
            beginTurn(next)
        } else {
            // Translation text is shown; offer replay or let the other person reply by tapping their button.
            ui = ui.copy(phase = Phase.Waiting(next), notice = next to next.playFailed)
        }
    }

    override fun onClosed(client: GeminiLiveClient, error: String?) {
        main.post {
            if (clients[client.config.source] === client) clients.remove(client.config.source)
            if (clients.values.none { it.state == GeminiLiveClient.State.CONNECTING }) ui = ui.copy(connecting = false)
            if (error != null) ui = ui.copy(errorDetail = error)
            val turn = active ?: return@post
            if (turn.client !== client || turn.completed) return@post
            main.removeCallbacks(translatingTimer)
            active = null
            mic.stop()
            player.stop()
            abandonFocus()
            val original = turn.original.toString().trim().ifEmpty { ui.partialOriginal }
            val recordId = if (original.isNotEmpty()) {
                upsertRecord(Record(turn.recordId, turn.side, original, status = RecordStatus.FAILED))
                turn.recordId
            } else {
                null
            }
            ui = ui.copy(
                phase = Phase.NetworkError(turn.side, recordId),
                partialOriginal = "",
                notice = turn.side to turn.side.networkError,
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun upsertRecord(record: Record) {
        val list = ui.records
        val i = list.indexOfFirst { it.id == record.id }
        ui = ui.copy(records = if (i < 0) list + record else list.toMutableList().also { it[i] = record })
    }

    private fun updateRecord(id: Long, change: (Record) -> Record) {
        ui = ui.copy(records = ui.records.map { if (it.id == id) change(it) else it })
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener({ change ->
                // An incoming call or another app took the speaker.
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }, main)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    override fun onCleared() {
        cancelInFlight()
        closeClients()
        player.release()
        http.dispatcher.executorService.shutdown()
    }

    private companion object {
        const val MIN_PRESS_MS = 300L
        const val TRANSLATING_HINT_MS = 700L
    }
}
