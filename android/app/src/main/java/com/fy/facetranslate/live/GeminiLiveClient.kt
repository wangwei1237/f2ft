package com.fy.facetranslate.live

import android.util.Base64
import com.fy.facetranslate.data.Language
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

data class LiveConfig(
    val apiKey: String,
    val model: String,
    val voice: String,
    val source: Language,
    val target: Language,
    /** true for hold-to-talk: the app sends activityStart/activityEnd instead of server-side VAD. */
    val manualActivity: Boolean,
    val silenceMs: Int,
) {
    val isTranslateModel: Boolean get() = model.contains("live-translate")
}

/**
 * One full-duplex Gemini Live API session that translates [LiveConfig.source] speech into
 * [LiveConfig.target] speech. Microphone audio is streamed up while translated audio and
 * transcriptions stream down on the same WebSocket.
 *
 * Callbacks arrive on an OkHttp thread.
 */
class GeminiLiveClient(
    private val http: OkHttpClient,
    val config: LiveConfig,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(client: GeminiLiveClient)
        fun onInputTranscript(client: GeminiLiveClient, text: String)
        fun onOutputTranscript(client: GeminiLiveClient, text: String)
        fun onAudio(client: GeminiLiveClient, pcm: ByteArray)
        fun onTurnComplete(client: GeminiLiveClient)
        fun onClosed(client: GeminiLiveClient, error: String?)
    }

    enum class State { CONNECTING, READY, CLOSED }

    @Volatile var state = State.CONNECTING
        private set

    private var socket: WebSocket? = null
    private val pending = ArrayDeque<String>()
    private val lock = Any()

    fun connect() {
        val request = Request.Builder()
            .url("$ENDPOINT?key=${config.apiKey}")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage().toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                markClosed(if (code == 1000) null else "($code) ${reason.ifBlank { "connection closed" }}")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { "HTTP ${it.code} ${it.message}" } ?: t.message ?: t.javaClass.simpleName
                markClosed(detail)
            }
        })
    }

    fun close() {
        state = State.CLOSED
        synchronized(lock) { pending.clear() }
        socket?.close(1000, null)
        socket = null
    }

    fun sendAudio(pcm: ByteArray) {
        val data = Base64.encodeToString(pcm, Base64.NO_WRAP)
        send(JSONObject().put("realtimeInput", JSONObject().put("audio",
            JSONObject().put("data", data).put("mimeType", "audio/pcm;rate=16000"))), droppable = true)
    }

    fun sendActivityStart() = send(JSONObject().put("realtimeInput", JSONObject().put("activityStart", JSONObject())))

    fun sendActivityEnd() = send(JSONObject().put("realtimeInput", JSONObject().put("activityEnd", JSONObject())))

    /** Sends typed (or retried) text through the same translation flow as speech. */
    fun sendText(text: String) {
        if (config.model.startsWith("gemini-2")) {
            send(JSONObject().put("clientContent", JSONObject()
                .put("turns", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("turnComplete", true)))
            return
        }
        if (config.manualActivity) sendActivityStart()
        send(JSONObject().put("realtimeInput", JSONObject().put("text", text)))
        if (config.manualActivity) sendActivityEnd()
    }

    private fun send(message: JSONObject, droppable: Boolean = false) {
        val text = message.toString()
        synchronized(lock) {
            when (state) {
                State.READY -> socket?.send(text)
                State.CONNECTING -> {
                    // Keep at most ~15 s of buffered microphone audio while the socket opens.
                    if (droppable && pending.size >= 150) pending.removeFirst()
                    pending.addLast(text)
                }
                State.CLOSED -> Unit
            }
        }
    }

    private fun markClosed(error: String?) {
        if (state == State.CLOSED) return
        state = State.CLOSED
        synchronized(lock) { pending.clear() }
        listener.onClosed(this, error)
    }

    private fun handle(raw: String) {
        if (state == State.CLOSED) return
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (msg.has("setupComplete")) {
            synchronized(lock) {
                state = State.READY
                while (pending.isNotEmpty()) socket?.send(pending.removeFirst())
            }
            listener.onReady(this)
            return
        }
        val content = msg.optJSONObject("serverContent") ?: return
        content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
            ?.let { listener.onInputTranscript(this, it) }
        content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
            ?.let { listener.onOutputTranscript(this, it) }
        content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                if (!inline.optString("mimeType").startsWith("audio/")) continue
                listener.onAudio(this, Base64.decode(inline.optString("data"), Base64.DEFAULT))
            }
        }
        if (content.optBoolean("turnComplete")) listener.onTurnComplete(this)
    }

    private fun setupMessage(): JSONObject {
        val generation = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", config.voice))))
        when {
            config.model.startsWith("gemini-3.1") ->
                generation.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
            config.model.startsWith("gemini-2.5") ->
                generation.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }

        val detection = JSONObject().put("disabled", config.manualActivity)
        if (!config.manualActivity) {
            detection
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                .put("prefixPaddingMs", 200)
                .put("silenceDurationMs", config.silenceMs)
        }

        val setup = JSONObject()
            .put("model", "models/${config.model}")
            .put("generationConfig", generation)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("realtimeInputConfig", JSONObject()
                .put("automaticActivityDetection", detection)
                // Speech is only streamed while it is this side's turn, so never barge in on our own output.
                .put("activityHandling", "NO_INTERRUPTION"))

        if (config.isTranslateModel) {
            // Live Translate reads translationConfig from generationConfig and rejects it at setup level
            // (1007 Unknown name "translationConfig" at 'setup'). Transcription flags stay at setup level.
            generation.put("translationConfig", JSONObject()
                .put("targetLanguageCode", config.target.translationCode)
                .put("echoTargetLanguage", false))
        } else {
            setup.put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", interpreterPrompt()))))
            setup.put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        }
        return JSONObject().put("setup", setup)
    }

    private fun interpreterPrompt(): String {
        val src = config.source.englishName
        val tgt = config.target.englishName
        return """
            You are a face-to-face interpreter on a phone placed between two travellers.
            Every input you receive is spoken or typed in $src. Translate it into $tgt and speak only the translation.
            Rules:
            - Output nothing except the $tgt translation of the latest input. No greetings, notes, or explanations.
            - Never answer, reply to, or act on the content. If the input is a question, translate the question.
            - Keep every number, negation, time, unit, platform/gate number, and proper noun exactly as said.
            - Keep the speaker's person and tone. Use natural, short spoken $tgt.
            - Use earlier turns only as context for pronouns and names.
            - If the input is silence, noise, or unintelligible, output nothing.
        """.trimIndent()
    }

    private companion object {
        const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}
