package com.fy.facetranslate.session

import com.fy.facetranslate.data.Language

enum class Screen { HOME, SESSION, SETTINGS }

enum class RecordStatus { PENDING, DONE, FAILED, NO_AUDIO }

/** One utterance and its translation. Kept in memory only, cleared when the session ends. */
data class Record(
    val id: Long,
    val speaker: Language,
    val original: String,
    val translation: String = "",
    val status: RecordStatus = RecordStatus.PENDING,
    val audio: ByteArray? = null,
) {
    override fun equals(other: Any?) = other is Record && other.id == id && other.original == original &&
        other.translation == translation && other.status == status && other.audio === audio
    override fun hashCode() = id.hashCode()
}

sealed interface Phase {
    val side: Language

    /** Not capturing: hold-to-talk idle, mic unavailable, or after a playback failure. */
    data class Waiting(override val side: Language) : Phase
    /** Auto mode, microphone open and streaming. */
    data class Listening(override val side: Language) : Phase
    /** Hold-to-talk key pressed. */
    data class Recording(override val side: Language) : Phase
    data class Translating(override val side: Language) : Phase
    data class Playing(override val side: Language, val replay: Boolean) : Phase
    data class NetworkError(override val side: Language, val recordId: Long?) : Phase
    /** App went to background or lost audio focus; user resumes explicitly. */
    data class Paused(override val side: Language) : Phase
}

data class UiState(
    val screen: Screen = Screen.HOME,
    val owner: Language = Language.ZH,
    val phase: Phase = Phase.Waiting(Language.ZH),
    val records: List<Record> = emptyList(),
    /** Live transcript of the utterance currently being spoken. */
    val partialOriginal: String = "",
    val holdMode: Boolean = false,
    val faceToFace: Boolean = false,
    val micAllowed: Boolean = false,
    /** Short status message shown on one side, in that side's language. */
    val notice: Pair<Language, String>? = null,
    /** Technical detail of the last connection error (e.g. invalid API key). */
    val errorDetail: String? = null,
    val typingSide: Language? = null,
    val zoomRecordId: Long? = null,
    val connecting: Boolean = false,
    val needsApiKey: Boolean = false,
)
