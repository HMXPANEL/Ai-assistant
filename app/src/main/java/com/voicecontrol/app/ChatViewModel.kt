package com.voicecontrol.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicecontrol.app.agent.AgentLlmEngine
import com.voicecontrol.app.data.ConversationMemory
import com.voicecontrol.app.security.SecureKeyStore
import com.voicecontrol.app.data.GeminiClient
import com.voicecontrol.app.data.LocalAiClient
import com.voicecontrol.app.device.AlarmHelper
import com.voicecontrol.app.device.CalendarHelper
import com.voicecontrol.app.device.ContactsHelper
import com.voicecontrol.app.device.DeviceController
import com.voicecontrol.app.device.NotificationPermissionHelper
import com.voicecontrol.app.device.NotificationService
import com.voicecontrol.app.device.SmsManager
import com.voicecontrol.app.model.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar


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

    private val _isGeminiEnabled = MutableStateFlow(true)
    val isGeminiEnabled: StateFlow<Boolean> = _isGeminiEnabled.asStateFlow()

    private val _geminiApiKey = MutableStateFlow("")
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    private var geminiClient = GeminiClient("")

    private val _requestSmsPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestSmsPermission: SharedFlow<Unit> = _requestSmsPermission.asSharedFlow()

    private val conversationMemory = ConversationMemory(getApplication())
    internal val localAiClient = LocalAiClient(getApplication()) // kept for future on-device use
    private val agentLlmEngine = AgentLlmEngine(getApplication()).also { engine ->
        engine.onStatusUpdate = { status -> addBotMessage(status) }
    }
    private val _modelCopyProgress = MutableStateFlow(-1)
    val modelCopyProgress: StateFlow<Int> = _modelCopyProgress.asStateFlow()
    private val _modelCopyStatus = MutableStateFlow("")
    val modelCopyStatus: StateFlow<String> = _modelCopyStatus.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    init {
        val savedKey = SecureKeyStore.getGeminiApiKey(getApplication()) ?: ""
        _geminiApiKey.value = savedKey
        if (savedKey.isNotBlank()) geminiClient = GeminiClient(savedKey)

        tts = TextToSpeech(getApplication()) { status ->
            isTtsReady = (status == TextToSpeech.SUCCESS)
        }
        addBotMessage("Hello! I'm your AI assistant. Try 'open YouTube', 'send message to [name] saying [text]', 'set alarm at 7am', 'read messages', or 'flashlight on'.")
    }

    fun saveGeminiApiKey(key: String) {
        SecureKeyStore.saveGeminiApiKey(getApplication(), key)
        _geminiApiKey.value = key
        geminiClient = GeminiClient(key)
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
            // Compound/multi-step commands (contains "and") → route directly to AgentLlmEngine
            if (lower.contains(" and ")) {
                agentLlmEngine.startTask(command, viewModelScope)
                return@launch
            }

            val response = when {
                lower == "show apps" || lower == "list apps" || lower == "show installed apps" -> {
                    val apps = AppLauncher.getInstalledApps(getApplication())
                    if (apps.isEmpty()) "No apps found."
                    else "Installed apps:\n" + apps.joinToString("\n") { "• ${it.name}" }
                }
                lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ") -> {
                    agentLlmEngine.startTask(command, viewModelScope)
                    return@launch
                }
                lower == "help" || lower == "what can you do" -> "I can:\n• Open apps — say 'open YouTube'\n• List apps — say 'show apps'\n• Send SMS — say 'send message to [name] saying [text]'\n• Read messages — say 'read messages'\n• Set alarms — say 'set alarm at 7am'\n• Set timers — say 'set timer for 5 minutes'\n• Find contacts — say 'find contact [name]'\n• Calendar — say 'today's events' or 'add event'\n• Control device — say 'flashlight on', 'mute', 'set volume'\n• Read notifications — say 'read notifications'"

                lower.startsWith("send message to ") || lower.startsWith("send sms to ") -> {
                    if (!hasSmsPermission()) {
                        _requestSmsPermission.tryEmit(Unit)
                        "Requesting SMS permission... If the dialog doesn't appear, go to Settings > Apps > AI Assistant > Permissions > SMS"
                    } else {
                        val parts = lower.removePrefix("send message to ").removePrefix("send sms to ").split(" saying ", limit = 2)
                        if (parts.size < 2) "Usage: 'send message to [name] saying [message]'."
                        else SmsManager.sendSms(getApplication(), parts[0].trim(), parts[1].trim())
                    }
                }
                lower.startsWith("text ") -> {
                    if (!hasSmsPermission()) {
                        _requestSmsPermission.tryEmit(Unit)
                        "Requesting SMS permission... If the dialog doesn't appear, go to Settings > Apps > AI Assistant > Permissions > SMS"
                    } else {
                        val parts = lower.removePrefix("text ").trim().split(" ", limit = 2)
                        if (parts.size < 2) "Usage: 'text [name] [message]'."
                        else SmsManager.sendSms(getApplication(), parts[0].trim(), parts[1].trim())
                    }
                }
                lower == "read messages" || lower == "show messages" || lower == "read sms" -> {
                    if (!hasSmsPermission()) {
                        _requestSmsPermission.tryEmit(Unit)
                        "Requesting SMS permission... If the dialog doesn't appear, go to Settings > Apps > AI Assistant > Permissions > SMS"
                    } else {
                        SmsManager.readRecentSms(getApplication())
                    }
                }

                lower == "read notifications" || lower == "show notifications" || lower == "any notifications" || lower == "what's new" || lower == "whats new" -> {
                    if (!NotificationPermissionHelper.isNotificationAccessGranted(getApplication()))
                        "Please grant Notification Access: go to Settings > Notifications > Notification Access > enable AI Assistant."
                    else NotificationService.getSummary()
                }

                lower.startsWith("find contact ") || lower.startsWith("search contact ") || (lower.startsWith("what is ") && lower.contains("number")) ->
                    ContactsHelper.findContact(getApplication(), extractContactName(lower))
                lower == "show contacts" || lower == "list contacts" -> ContactsHelper.listRecentContacts(getApplication())

                lower == "what's on my calendar" || lower == "show calendar" || lower == "today's events" ->
                    CalendarHelper.getTodayEvents(getApplication())
                lower.startsWith("show events") || lower.startsWith("upcoming events") ->
                    CalendarHelper.getUpcomingEvents(getApplication())
                lower.startsWith("add event") || lower.startsWith("schedule ") || lower.startsWith("create meeting") -> {
                    val text = lower.removePrefix("add event").removePrefix("schedule").removePrefix("create meeting").trim()
                    val (title, hour, min) = parseEventTime(text)
                    CalendarHelper.addEvent(getApplication(), title, hour, min)
                }

                lower.startsWith("set alarm") || lower.startsWith("wake me") || lower.startsWith("alarm at") -> {
                    try {
                        val parsed = AlarmHelper.parseTimeFromText(lower)
                        if (parsed == null) {
                            "I didn't understand that time. Try: 'set alarm at 7am' or 'alarm at 14:30'"
                        } else {
                            AlarmHelper.setAlarm(getApplication(), parsed.first, parsed.second)
                        }
                    } catch (e: Exception) {
                        "Alarm error: ${e.message}"
                    }
                }
                lower.startsWith("set timer") || lower.startsWith("timer for") -> {
                    parseDuration(lower)?.let { AlarmHelper.setTimer(getApplication(), it) }
                        ?: "Couldn't parse the duration. Try 'set timer for 5 minutes'."
                }

                lower == "flashlight on" || lower == "turn on flashlight" || lower == "torch on" ->
                    DeviceController.toggleFlashlight(getApplication(), true)
                lower == "flashlight off" || lower == "turn off flashlight" || lower == "torch off" ->
                    DeviceController.toggleFlashlight(getApplication(), false)
                lower.startsWith("set volume") || lower.startsWith("volume ") || lower == "volume" -> {
                    val n = extractNumber(lower)
                    if (n != null && n in 0..100) DeviceController.setVolume(getApplication(), n)
                    else "Couldn't parse volume. Try 'set volume to 50'."
                }
                lower == "mute" || lower == "silence phone" || lower == "mute phone" ->
                    DeviceController.mutePhone(getApplication())
                lower == "unmute" || lower == "unmute phone" ->
                    DeviceController.unmutePhone(getApplication())
                lower.startsWith("set brightness") || lower.startsWith("brightness ") || lower == "brightness" -> {
                    val n = extractNumber(lower)
                    if (n != null && n in 0..100) DeviceController.setBrightness(getApplication(), n)
                    else "Couldn't parse brightness. Try 'set brightness to 50'."
                }
                lower == "turn on wifi" || lower == "enable wifi" || lower == "wifi on" ->
                    DeviceController.toggleWifi(getApplication(), true)
                lower == "turn off wifi" || lower == "wifi off" ->
                    DeviceController.toggleWifi(getApplication(), false)
                lower == "turn on bluetooth" || lower == "bluetooth on" ->
                    DeviceController.toggleBluetooth(getApplication(), true)
                lower == "turn off bluetooth" || lower == "bluetooth off" ->
                    DeviceController.toggleBluetooth(getApplication(), false)

                lower.contains("mobile data") || lower.contains("turn off data") ||
                lower.contains("turn on data") || lower.contains("mobile network") -> {
                    val enable = lower.contains("on") || lower.contains("enable")
                    DeviceController.toggleMobileData(getApplication(), enable)
                }

                lower.contains("airplane mode") || lower.contains("flight mode") -> {
                    DeviceController.openAirplaneMode(getApplication())
                }

                lower.contains("turn off internet") || lower.contains("disable internet") ||
                lower.contains("turn on internet") -> {
                    val enable = lower.contains("on") || lower.contains("enable")
                    DeviceController.toggleMobileData(getApplication(), enable)
                }

                lower.contains("trun off") || lower.contains("trun on") -> {
                    val fixedLower = lower.replace("trun", "turn")
                    processCommand(fixedLower)
                    return@launch
                }

                else -> {
                    val devicePrefixes = listOf("open", "send", "set", "turn", "call", "play", "book", "search", "delete", "close")
                    if (devicePrefixes.any { lower.startsWith(it) } || lower.contains(" and ")) {
                        agentLlmEngine.startTask(command, viewModelScope)
                        return@launch
                    }
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
        return if (_isGeminiEnabled.value) {
            val history = conversationMemory.getHistory()
            geminiClient.generateResponse(prompt, history)
        } else {
            "AI is disabled. Enable Gemini in Settings."
        }
    // ponytail: localAI kept for future rule-based on-device tasks
    // private suspend fun getLocalAiResponse(prompt: String): String {
    //     return if (!_isLocalAiEnabled.value) {
    //         "On-device AI is disabled. Enable it in Settings."
    //     } else if (!localAiClient.isModelAvailable()) {
    //         "Model not ready. Go to Settings → tap 'Copy Model to App Storage'."
    //     } else {
    //         val history = conversationMemory.getHistory()
    //         localAiClient.generateResponse(prompt, history)
    //     }
    // }
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

    fun toggleGemini() {
        _isGeminiEnabled.value = !_isGeminiEnabled.value
    }

    fun copyModelFromUri(uri: Uri) {
        _modelCopyStatus.value = "Starting copy..."
        _modelCopyProgress.value = 0

        viewModelScope.launch {
            try {
                val result = localAiClient.copyModelFromUri(uri) { progress ->
                    _modelCopyProgress.value = progress
                    _modelCopyStatus.value = "Copying... $progress%"
                }
                _modelCopyStatus.value = result
            } catch (e: Exception) {
                _modelCopyStatus.value = "Error: ${e.message}"
            } finally {
                _modelCopyProgress.value = -1
            }
        }
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

    fun addSystemMessage(text: String) {
        addBotMessage(text)
    }

    private fun hasSmsPermission(): Boolean {
        val ctx = getApplication<Application>()
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun addUserMessage(text: String) {
        _messages.value = _messages.value + Message(text = text, isUser = true)
    }

    private fun addBotMessage(text: String) {
        _messages.value = _messages.value + Message(text = text, isUser = false)
        speak(text)
        conversationMemory.saveMessage("assistant", text)
    }

    private fun extractContactName(lower: String): String = when {
        lower.startsWith("find contact ") -> lower.removePrefix("find contact ").trim()
        lower.startsWith("search contact ") -> lower.removePrefix("search contact ").trim()
        lower.startsWith("what is ") -> lower.removePrefix("what is ").trim().replace(Regex("'?s? (phone )?number.*"), "").trim()
        else -> lower
    }

    private fun parseDuration(input: String): Int? {
        val cleaned = input.lowercase().replace(Regex("set timer|timer for|timer|for"), "").trim()
        Regex("""(\d+)\s*(min|mins|minute|minutes|m)""").find(cleaned)?.let { return it.groupValues[1].toInt() * 60 }
        Regex("""(\d+)\s*(sec|secs|second|seconds|s)""").find(cleaned)?.let { return it.groupValues[1].toInt() }
        Regex("""(\d+)\s*(hour|hours|h)""").find(cleaned)?.let { return it.groupValues[1].toInt() * 3600 }
        Regex("""(\d+)""").find(cleaned)?.let { return it.groupValues[1].toInt() * 60 }
        return null
    }

    private fun extractNumber(input: String): Int? {
        val cleaned = input.lowercase().replace(Regex("set volume|volume|set brightness|brightness|to"), "").trim()
        Regex("""(\d+)""").find(cleaned)?.let { return it.groupValues[1].toInt().coerceIn(0, 100) }
        return null
    }

    private fun parseEventTime(text: String): Triple<String, Int, Int> {
        val lower = text.lowercase()
        val atIdx = lower.indexOf(" at ")
        if (atIdx >= 0) {
            val title = text.substring(0, atIdx).trim()
            val time = AlarmHelper.parseTimeFromText(text.substring(atIdx + 4).trim())
            if (time != null) return Triple(title, time.first, time.second)
        }
        val cal = Calendar.getInstance()
        return Triple(text, cal.get(Calendar.HOUR_OF_DAY) + 1, 0)
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        localAiClient.unload()
    }
}
