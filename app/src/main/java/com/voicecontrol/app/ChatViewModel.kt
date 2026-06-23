package com.voicecontrol.app

import android.content.Context
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicecontrol.app.model.Message
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.*

class ChatViewModel(private val context: Context) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    private val _speechError = MutableStateFlow<String?>(null)
    val speechError = _speechError.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private val appLauncher = AppLauncher(context)

    init {
        initializeSpeechRecognizer()
    }

    private fun initializeSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle) {
                    _isListening.value = true
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray) {}

                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                        else -> "Speech recognition error"
                    }
                    _speechError.value = errorMessage
                }

                override fun onResults(results: Bundle) {
                    _isListening.value = false
                    val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let { text ->
                        addUserMessage(text)
                        processCommand(text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle) {}

                override fun onEvent(eventType: Int, params: Bundle) {}
            })
        }
    }

    fun startVoiceInput() {
        _speechError.value = null
        speechRecognizer?.let { recognizer ->
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.listening))
            }
            recognizer.startListening(intent)
        }
    }

    fun stopVoiceInput() {
        speechRecognizer?.stopListening()
        _isListening.value = false
    }

    fun sendMessage(text: String) {
        val trimmedText = text.trim()
        if (trimmedText.isNotEmpty()) {
            addUserMessage(trimmedText)
            processCommand(trimmedText)
        }
    }

    private fun addUserMessage(text: String) {
        val message = Message(text = text, isUser = true)
        _messages.value = _messages.value + message
    }

    private fun addBotMessage(text: String) {
        val message = Message(text = text, isUser = false)
        _messages.value = _messages.value + message
    }

    private fun processCommand(text: String) {
        val lowerText = text.lowercase(Locale.getDefault()).trim()

        when {
            lowerText == "show apps" || lowerText == "list apps" -> {
                val apps = appLauncher.getAllLaunchableApps()
                if (apps.isNotEmpty()) {
                    val appsList = apps.joinToString("\n") { "- ${it.label} (${it.packageName})" }
                    addBotMessage("Installed Apps:\n$appsList")
                } else {
                    addBotMessage(context.getString(R.string.no_apps_found))
                }
            }
            lowerText.startsWith("open ") -> {
                val appName = lowerText.substringAfter("open ").trim()
                if (appName.isNotEmpty()) {
                    val success = appLauncher.launchAppByName(appName)
                    if (!success) {
                        addBotMessage(context.getString(R.string.app_not_found))
                    }
                }
            }
            else -> {
                addBotMessage("I didn't understand. Try 'open WhatsApp', 'open YouTube', or 'show apps'")
            }
        }
    }

    override fun onCleared() {
        speechRecognizer?.destroy()
        super.onCleared()
    }
}