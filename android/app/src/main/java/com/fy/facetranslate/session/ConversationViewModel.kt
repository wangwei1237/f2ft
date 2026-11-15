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
import com.fy.facetranslate.data.UpdateChecker
import com.fy.facetranslate.data.UpdateResult
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
    private val updateChecker = UpdateChecker(http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build())
    private val subtitles = mutableMapOf<Language, GeminiLiveClient>()
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

        /** Live subtitle session for this turn's side, or null when subtitles are off or unavailable. */
        var subtitle: GeminiLiveClient? = null
        var subtitleSent = false
        /** Subtitle text finalized so far in this turn, plus the hypothesis for the utterance in progress. */
        val subtitleFinal = StringBuilder()
        var subtitleInterim = ""

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
        stopPreview()
        store.save(new)
        settings = store.load()
        closeClients()
        ui = ui.copy(screen = Screen.HOME, needsApiKey = settings.apiKey.isBlank(), errorDetail = null)
    }

    fun closeSettings() {
        stopPreview()
        ui = ui.copy(screen = Screen.HOME)
    }

    /**
     * Opens throwaway sessions for the translation model and, if enabled, the subtitle model.
     * Reports one (label, error or null) pair per model.
     */
    fun testConnection(candidate: AppSettings, onResult: (List<Pair<String, String?>>) -> Unit) {
        val base = LiveConfig(
            apiKey = candidate.apiKey.trim(), model = candidate.model.trim(), voice = candidate.voice.trim(),
            source = Language.ZH, target = Language.EN, manualActivity = false, silenceMs = candidate.silenceMs,
        )
        val probes = mutableListOf("翻译播报" to base)
        if (candidate.subtitlesEnabled) {
            probes += "实时字幕" to base.copy(model = candidate.transcribeModel.trim(), transcribeOnly = true)
        }
        val results = arrayOfNulls<Pair<String, String?>>(probes.size)
        var remaining = probes.size
        probes.forEachIndexed { i, (label, config) ->
            probe(config) { error ->
                results[i] = label to error
                if (--remaining == 0) onResult(results.filterNotNull())
            }
        }
    }

    /** Connects with [config] and reports null once setup completes, or the error text. Result on main thread. */
    private fun probe(config: LiveConfig, onResult: (String?) -> Unit) {
        var done = false
        val finish = { error: String? -> main.post { if (!done) { done = true; onResult(error) } } }
        val client = GeminiLiveClient(http, config, object : GeminiLiveClient.Listener {
            override fun onReady(client: GeminiLiveClient) { finish(null); client.close() }
            override fun onInputTranscript(client: GeminiLiveClient, text: String) = Unit
            override fun onOutputTranscript(client: GeminiLiveClient, text: String) = Unit
            override fun onAudio(client: GeminiLiveClient, pcm: ByteArray) = Unit
            override fun onTurnComplete(client: GeminiLiveClient) = Unit
            override fun onClosed(client: GeminiLiveClient, error: String?) { finish(error ?: "connection closed") }
        })
        client.connect()
        main.postDelayed({ if (!done) { client.close(); finish("timeout") } }, 10_000)
    }

    // ---------------------------------------------------------------- update check

    /** Asks GitHub for the latest release; [onResult] runs on the main thread. */
    fun checkForUpdate(currentVersion: String, onResult: (UpdateResult) -> Unit) {
        updateChecker.check(currentVersion) { result -> main.post { onResult(result) } }
    }

    // ---------------------------------------------------------------- voice preview

    private var previewClient: GeminiLiveClient? = null

    /**
     * Speaks a fixed sample in [candidate]'s voice by translating a Chinese sentence into English.
     * [onState] receives true when playback starts and false (with an error or null) when it ends.
     */
    fun previewVoice(candidate: AppSettings, onState: (playing: Boolean, error: String?) -> Unit) {
        stopPreview()
        val config = LiveConfig(
            apiKey = candidate.apiKey.trim(), model = candidate.model.trim(), voice = candidate.voice.trim(),
            source = Language.ZH, target = Language.EN, manualActivity = false, silenceMs = candidate.silenceMs,
        )
        var gen = 0
        var ended = false
        fun end(client: GeminiLiveClient, error: String?) {
            if (previewClient !== client || ended) return
            ended = true
            previewClient = null
            client.close()
            abandonFocus()
            onState(false, error)
        }
        val client = GeminiLiveClient(http, config, object : GeminiLiveClient.Listener {
            override fun onReady(client: GeminiLiveClient) {
                main.post { if (previewClient === client) client.sendText(PREVIEW_SAMPLE) }
            }
            override fun onInputTranscript(client: GeminiLiveClient, text: String) = Unit
            override fun onOutputTranscript(client: GeminiLiveClient, text: String) = Unit
            override fun onAudio(client: GeminiLiveClient, pcm: ByteArray) {
                main.post {
                    if (previewClient !== client) return@post
                    if (gen == 0) {
                        requestFocus()
                        gen = player.begin()
                    }
                    player.write(gen, pcm)
                }
            }
            override fun onTurnComplete(client: GeminiLiveClient) {
                main.post {
                    if (previewClient !== client) return@post
                    if (gen == 0) end(client, "no audio") else player.finish(gen) { main.post { end(client, null) } }
                }
            }
            override fun onClosed(client: GeminiLiveClient, error: String?) {
                main.post { end(client, error ?: "connection closed") }
            }
        })
        previewClient = client
        onState(true, null)
        client.connect()
        main.postDelayed({ if (previewClient === client && gen == 0) end(client, "timeout") }, 15_000)
    }

    fun stopPreview() {
        val client = previewClient ?: return
        previewClient = null
        client.close()
        player.stop()
        abandonFocus()
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
        ensureSubtitle(first)
        ensureSubtitle(first.other())
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
        val turn = Turn(Kind.SPEECH, side, client, nextRecordId++).apply {
            pressAt = SystemClock.elapsedRealtime()
            subtitle = ensureSubtitle(side)
        }
        active = turn
        ui = ui.copy(phase = Phase.Recording(side), partialOriginal = "", partialIsInterim = false, notice = null)
        if (!mic.start()) micFailed(side)
    }

    fun releaseToTalk(side: Language) {
        val turn = active ?: return
        if (turn.side != side || ui.phase !is Phase.Recording) return
        mic.stop()
        val started = synchronized(turn.lock) {
            turn.released = true
            if (turn.activityStarted) {
                turn.client?.sendActivityEnd()
                turn.subtitle?.sendActivityEnd()
            }
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
        active = Turn(Kind.SPEECH, side, client, nextRecordId++).apply { subtitle = ensureSubtitle(side) }
        ui = ui.copy(phase = Phase.Listening(side), partialIsInterim = false)
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
        // A subtitle session that heard part of this turn could leak its late hypothesis into the next one.
        turn.subtitle?.takeIf { turn.subtitleSent && subtitles[turn.side] === it }?.let {
            subtitles.remove(turn.side)
            it.close()
        }
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
        subtitles.values.forEach { it.close() }
        subtitles.clear()
    }

    /** Returns the live subtitle session for [source], opening it if needed; null when subtitles are off. */
    private fun ensureSubtitle(source: Language): GeminiLiveClient? {
        if (!settings.subtitlesEnabled) return null
        subtitles[source]?.takeIf { it.state != GeminiLiveClient.State.CLOSED }?.let { return it }
        val config = LiveConfig(
            apiKey = settings.apiKey,
            model = settings.transcribeModel,
            voice = settings.voice,
            source = source,
            target = source.other(),
            manualActivity = ui.holdMode,
            silenceMs = settings.silenceMs,
            transcribeOnly = true,
        )
        val client = GeminiLiveClient(http, config, subtitleListener)
        subtitles[source] = client
        client.connect()
        return client
    }

    /**
     * Subtitle results only refresh the on-screen original of the active turn, and only until the
     * translation model delivers its own transcript or output starts. Errors are never shown:
     * the conversation simply falls back to end-of-sentence transcripts.
     */
    private val subtitleListener = object : GeminiLiveClient.Listener {
        private fun onTurnOf(client: GeminiLiveClient, block: (Turn) -> Unit) {
            main.post {
                val turn = active ?: return@post
                if (turn.subtitle === client && subtitles[turn.side] === client && turn.subtitleSent &&
                    !turn.outputStarted && turn.original.isEmpty()
                ) {
                    block(turn)
                }
            }
        }

        private fun show(turn: Turn) {
            val text = (turn.subtitleFinal.toString() + turn.subtitleInterim).trim()
            if (text.isNotEmpty()) ui = ui.copy(partialOriginal = text, partialIsInterim = true, notice = null)
        }

        override fun onReady(client: GeminiLiveClient) = Unit

        override fun onInterimTranscript(client: GeminiLiveClient, text: String) = onTurnOf(client) { turn ->
            turn.subtitleInterim = text
            show(turn)
        }

        override fun onInputTranscript(client: GeminiLiveClient, text: String) = onTurnOf(client) { turn ->
            turn.subtitleFinal.append(text)
            turn.subtitleInterim = ""
            show(turn)
        }

        override fun onOutputTranscript(client: GeminiLiveClient, text: String) = Unit
        override fun onAudio(client: GeminiLiveClient, pcm: ByteArray) = Unit
        override fun onTurnComplete(client: GeminiLiveClient) = Unit

        override fun onClosed(client: GeminiLiveClient, error: String?) {
            // Reconnected lazily when the next turn on this side begins.
            main.post { if (subtitles[client.config.source] === client) subtitles.remove(client.config.source) }
        }
    }

    /** Microphone thread. */
    private fun onMicChunk(pcm: ByteArray) {
        val turn = active ?: return
        val client = turn.client ?: return
        if (turn.outputStarted) return
        // The subtitle session gets exactly the same audio as the translation session.
        val subtitle = turn.subtitle
        if (!client.config.manualActivity) {
            turn.sentInput = true
            client.sendAudio(pcm)
            if (subtitle != null) {
                turn.subtitleSent = true
                subtitle.sendAudio(pcm)
            }
            return
        }
        synchronized(turn.lock) {
            if (turn.released) return
            if (turn.activityStarted) {
                client.sendAudio(pcm)
                subtitle?.sendAudio(pcm)
                return
            }
            turn.preroll.add(pcm)
            if (SystemClock.elapsedRealtime() - turn.pressAt >= MIN_PRESS_MS) {
                turn.activityStarted = true
                turn.sentInput = true
                client.sendActivityStart()
                turn.preroll.forEach(client::sendAudio)
                if (subtitle != null) {
                    turn.subtitleSent = true
                    subtitle.sendActivityStart()
                    turn.preroll.forEach(subtitle::sendAudio)
                }
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
            // The translation model's transcript is authoritative and replaces the live subtitle.
            ui = ui.copy(partialOriginal = original, partialIsInterim = false, notice = null)
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
        if (turn.subtitleSent && turn.subtitle?.config?.manualActivity == false) turn.subtitle?.sendAudioStreamEnd()
        main.removeCallbacks(translatingTimer)
        upsertRecord(Record(turn.recordId, turn.side, turn.original.toString().trim()))
        ui = ui.copy(phase = Phase.Playing(turn.side, replay = false), partialOriginal = "", partialIsInterim = false, notice = null)
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
                turn.subtitleFinal.clear()
                turn.subtitleInterim = ""
                ui = ui.copy(phase = Phase.Listening(side), partialOriginal = "", partialIsInterim = false, notice = side to side.retry)
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
        const val PREVIEW_SAMPLE = "你好，我会帮你把对话翻译成英文。"
    }
}
