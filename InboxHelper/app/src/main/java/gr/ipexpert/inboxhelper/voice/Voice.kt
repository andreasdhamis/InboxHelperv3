package gr.ipexpert.inboxhelper.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.mutableStateMapOf
import gr.ipexpert.inboxhelper.data.Evidence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Drafts per conversation, shared by the reply composer and the voice assistant. */
object Drafts {
    val text = mutableStateMapOf<String, String>()
    val evidence = mutableStateMapOf<String, List<Evidence>>()
    val missing = mutableStateMapOf<String, String>()

    fun set(convId: String, t: String, ev: List<Evidence> = evidence[convId] ?: emptyList(), miss: String = missing[convId] ?: "") {
        text[convId] = t; evidence[convId] = ev; missing[convId] = miss
    }
}

data class Segment(val title: String, val text: String)

data class PlayerState(val active: Boolean = false, val playing: Boolean = false, val index: Int = 0, val total: Int = 0,
                       val title: String = "", val rate: Float = 1f)

/**
 * Read-aloud player on top of Android TextToSpeech: a queue of segments with play/pause/stop/next/previous
 * and speed (0.75–2×). Greek text is spoken with a Greek voice, other text with English.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var queue: List<Segment> = emptyList()
    private var pendingPlay = false
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready && pendingPlay) { pendingPlay = false; speakCurrent() }
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId?.contains("-part") == true) return   // intermediate chunk of a long segment
                    val s = _state.value
                    if (!s.playing) return
                    if (s.index + 1 < queue.size) { _state.value = s.copy(index = s.index + 1, title = queue[s.index + 1].title); speakCurrent() }
                    else _state.value = s.copy(playing = false)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { _state.value = _state.value.copy(playing = false) }
            })
        }
    }

    private fun isGreek(s: String) = s.count { it in 'Α'..'ω' || it in 'ά'..'ώ' } > s.length / 10

    private fun speakCurrent() {
        val t = tts ?: return
        if (!ready) { pendingPlay = true; return }
        val seg = queue.getOrNull(_state.value.index) ?: return
        t.language = if (isGreek(seg.text)) Locale.forLanguageTag("el-GR") else Locale.US
        t.setSpeechRate(_state.value.rate)
        // TTS has an input length limit (~4000 chars); long texts are split into chunks queued in order.
        val chunks = seg.text.chunked(3500)
        chunks.forEachIndexed { i, c ->
            val id = if (i == chunks.lastIndex) "seg-${_state.value.index}" else "seg-${_state.value.index}-part$i"
            t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
        }
    }

    fun play(segments: List<Segment>, startAt: Int = 0) {
        if (segments.isEmpty()) return
        queue = segments
        _state.value = _state.value.copy(active = true, playing = true, index = startAt.coerceIn(0, segments.lastIndex),
            total = segments.size, title = segments[startAt.coerceIn(0, segments.lastIndex)].title)
        speakCurrent()
    }

    fun say(text: String) = play(listOf(Segment("Assistant", text)))

    fun pause() { tts?.stop(); _state.value = _state.value.copy(playing = false) }
    fun resume() { if (queue.isNotEmpty()) { _state.value = _state.value.copy(playing = true); speakCurrent() } }
    fun stop() { tts?.stop(); queue = emptyList(); _state.value = PlayerState(rate = _state.value.rate) }
    fun next() { val s = _state.value; if (s.index + 1 < queue.size) { _state.value = s.copy(index = s.index + 1, playing = true, title = queue[s.index + 1].title); speakCurrent() } }
    fun previous() { val s = _state.value; val i = (s.index - 1).coerceAtLeast(0); _state.value = s.copy(index = i, playing = true, title = queue.getOrNull(i)?.title ?: ""); speakCurrent() }
    fun setRate(r: Float) {
        _state.value = _state.value.copy(rate = r)
        if (_state.value.playing) speakCurrent()
    }
}
