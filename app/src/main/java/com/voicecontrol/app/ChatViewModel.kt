package com.voicecontrol.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicecontrol.app.data.ConversationMemory
import com.voicecontrol.app.data.LocalAiClient
import com.voicecontrol.app.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isTtsEnabled = MutableStateFlow(true)
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    private val _isLocalAiEnabled = MutableStateFlow(false)
    val isLocalAiEnabled: StateFlow<Boolean> = _isLocalAiEnabled.asStateFlow()

    private val conversationMemory = ConversationMemory(getApplication())
    private val localAiClient = LocalAiClient(getApplication())

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    init {
        tts = TextToSpeech(getApplication()) { status ->
            isTtsReady = (status == TextToSpeech.SUCCESS)
        }
        addBotMessage("Hello! I'm your AI assistant. I can open apps, answer questions, and control your device. Try saying 'open WhatsApp', 'set an alarm', or just ask me anything.")
    }

    fun isModelAvailable(): Boolean = localAiClient.isModelAvailable()

    fun onInputChange(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        addUserMessage(text)
        conversationMemory.saveMessage("user", text)
        _inputText.value = ""
        processCommand(text)
    }

    fun processCommand(command: String) {
        val lower = command.lowercase().trim()
        viewModelScope.launch {
            val response = when {
                lower == "show apps" || lower == "list apps" || lower == "show installed apps" -> {
                    val apps = AppLauncher.getInstalledApps(getApplication())
                    if (apps.isEmpty()) {
                        "No apps found."
                    } else {
                        "Installed apps:\n" + apps.joinToString("\n") { "• ${it.name}" }
                    }
                }
                lower.startsWith("open ") -> {
                    val appName = lower.removePrefix("open ").trim()
                    AppLauncher.findAndLaunchApp(getApplication(), appName)
                }
                lower.startsWith("launch ") -> {
                    val appName = lower.removePrefix("launch ").trim()
                    AppLauncher.findAndLaunchApp(getApplication(), appName)
                }
                lower.startsWith("start ") -> {
                    val appName = lower.removePrefix("start ").trim()
                    AppLauncher.findAndLaunchApp(getApplication(), appName)
                }
                lower == "help" || lower == "what can you do" -> {
                    "I can:\n• Open apps — say 'open YouTube'\n• List apps — say 'show apps'\n• Launch apps by name"
                }
                else -> {
                    getLocalAiResponse(command)
                }
            }
            addBotMessage(response)
        }
    }

    fun unloadModel() {
        localAiClient.unload()
        addBotMessage("Model unloaded. Tap to reload on next question.")
    }

    private suspend fun getLocalAiResponse(prompt: String): String {
        return if (!_isLocalAiEnabled.value) {
            "On-device AI is disabled. Enable it in Settings."
        } else if (!localAiClient.isModelAvailable()) {
            "Model file not found at /storage/emulated/0/Download/gemma-2-2b-it-lQ4_XS.gguf — make sure the file is in your Downloads folder."
        } else {
            val history = conversationMemory.getHistory()
            localAiClient.generateResponse(prompt, history)
        }
    }

    fun startListening() {
        val context = getApplication<Application>()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            addBotMessage("Speech recognition is not available on this device.")
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                _isListening.value = false
            }
            override fun onError(error: Int) {
                _isListening.value = false
                addBotMessage("Didn't catch that. Please try again.")
            }
            override fun onResults(results: Bundle?) {
                _isListening.value = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull()
                if (spokenText != null) {
                    addUserMessage(spokenText)
                    conversationMemory.saveMessage("user", spokenText)
                    processCommand(spokenText)
                } else {
                    addBotMessage("Couldn't understand. Please try again.")
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        _isListening.value = false
    }

    fun toggleTts() {
        _isTtsEnabled.value = !_isTtsEnabled.value
    }

    fun toggleLocalAi() {
        _isLocalAiEnabled.value = !_isLocalAiEnabled.value
    }

    fun clearHistory() {
        conversationMemory.clearHistory()
        _messages.value = emptyList()
    }

    private fun speak(text: String) {
        if (isTtsReady && _isTtsEnabled.value) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun addUserMessage(text: String) {
        _messages.value = _messages.value + Message(text = text, isUser = true)
    }

    private fun addBotMessage(text: String) {
        _messages.value = _messages.value + Message(text = text, isUser = false)
        speak(text)
        conversationMemory.saveMessage("assistant", text)
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        localAiClient.unload()
    }
}
