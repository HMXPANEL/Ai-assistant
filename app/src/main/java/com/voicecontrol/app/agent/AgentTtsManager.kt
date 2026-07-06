package com.voicecontrol.app.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class AgentTtsManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context) { status ->
            isReady = (status == TextToSpeech.SUCCESS)
            tts?.language = Locale.forLanguageTag("hi")
        }
    }

    fun speak(text: String) {
        if (isReady && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    fun stop() {
        tts?.stop()
    }

    suspend fun speakAndAwait(text: String) {
        if (!isReady || text.isBlank()) return
        val utteranceId = "utt_${System.currentTimeMillis()}"
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(Unit) {}
                }
                override fun onError(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(Unit) {}
                }
            })
            val params = android.os.Bundle()
            tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
