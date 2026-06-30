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

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
