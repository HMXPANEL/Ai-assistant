package com.voicecontrol.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicecontrol.app.data.ApiKeyManager
import com.voicecontrol.app.data.GeminiClient
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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val apiKeyManager = ApiKeyManager(getApplication())
    private val geminiClient = GeminiClient()

    private var speechRecognizer: SpeechRecognizer? = null

    init {
        addBotMessage("Hello! I can open apps for you. Try saying 'open WhatsApp' or type 'show apps'.")
    }

    fun onInputChange(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        addUserMessage(text)
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
                    getGeminiResponse(command)
                }
            }
            addBotMessage(response)
        }
    }

    private suspend fun getGeminiResponse(prompt: String): String {
        val apiKey = apiKeyManager.getApiKey()
        if (apiKey == null) {
            return "Please set your Gemini API key in Settings to use AI responses."
        }
        _isLoading.value = true
        return try {
            geminiClient.generateContent(prompt, apiKey)
        } catch (e: Exception) {
            "Sorry, I couldn't reach the AI: ${e.message}"
        } finally {
            _isLoading.value = false
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

    fun clearApiKey() {
        apiKeyManager.clearApiKey()
    }

    private fun addUserMessage(text: String) {
        _messages.value = _messages.value + Message(text = text, isUser = true)
    }

    private fun addBotMessage(text: String) {
        _messages.value = _messages.value + Message(text = text, isUser = false)
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
    }
}
