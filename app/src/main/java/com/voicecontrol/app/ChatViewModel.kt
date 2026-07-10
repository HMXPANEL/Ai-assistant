package com.voicecontrol.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicecontrol.app.agent.AgentLlmEngine
import com.voicecontrol.app.data.ConversationMemory
import com.voicecontrol.app.security.SecureKeyStore
import com.voicecontrol.app.data.GroqClient
import com.voicecontrol.app.data.GeminiClient
import com.voicecontrol.app.data.WeatherClient
import com.voicecontrol.app.device.AlarmHelper
import com.voicecontrol.app.device.CalendarHelper
import com.voicecontrol.app.device.ContactsHelper
import com.voicecontrol.app.device.DeviceController
import com.voicecontrol.app.device.MusicController
import com.voicecontrol.app.device.NotificationService
import com.voicecontrol.app.device.SmsManager
import com.voicecontrol.app.model.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject


enum class AiProvider { GEMINI, GROQ }

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isTtsEnabled = MutableStateFlow(true)
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    private val _isGeminiEnabled = MutableStateFlow(true)
    val isGeminiEnabled: StateFlow<Boolean> = _isGeminiEnabled.asStateFlow()

    private val _isWakeWordEnabled = MutableStateFlow(false)
    val isWakeWordEnabled: StateFlow<Boolean> = _isWakeWordEnabled.asStateFlow()

    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _geminiApiKey = MutableStateFlow("")
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _groqApiKey = MutableStateFlow("")
    val groqApiKey: StateFlow<String> = _groqApiKey.asStateFlow()

    private val _weatherApiKey = MutableStateFlow("")
    val weatherApiKey: StateFlow<String> = _weatherApiKey.asStateFlow()

    private val _aiProvider = MutableStateFlow(AiProvider.GEMINI)
    val aiProvider: StateFlow<AiProvider> = _aiProvider.asStateFlow()

    private var geminiClient = GeminiClient("")
    private var groqClient = GroqClient("")
    private var weatherClient = WeatherClient("")

    private val _requestSmsPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestSmsPermission: SharedFlow<Unit> = _requestSmsPermission.asSharedFlow()

    private val _requestWakePermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestWakePermission: SharedFlow<Unit> = _requestWakePermission.asSharedFlow()

    private val conversationMemory = ConversationMemory(getApplication())
    private val agentLlmEngine = AgentLlmEngine(getApplication()).also { engine ->
        engine.llmCall = { prompt -> currentLlmCall(prompt) }
        engine.onStatusUpdate = { status -> addBotMessage(status) }
    }
    val isAgentRunning: StateFlow<Boolean> = agentLlmEngine.isRunning

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    init {
        val ctx = getApplication<Application>()
        val savedGeminiKey = SecureKeyStore.getGeminiApiKey(ctx) ?: ""
        _geminiApiKey.value = savedGeminiKey
        if (savedGeminiKey.isNotBlank()) geminiClient = GeminiClient(savedGeminiKey)

        val savedGroqKey = SecureKeyStore.getGroqApiKey(ctx) ?: ""
        _groqApiKey.value = savedGroqKey
        if (savedGroqKey.isNotBlank()) groqClient = GroqClient(savedGroqKey)

        val savedWeatherKey = SecureKeyStore.getWeatherApiKey(ctx) ?: ""
        _weatherApiKey.value = savedWeatherKey
        if (savedWeatherKey.isNotBlank()) weatherClient = WeatherClient(savedWeatherKey)

        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _isWakeWordEnabled.value = prefs.getBoolean("wake_word_enabled", false)
        _isDarkMode.value = prefs.getBoolean("dark_mode", false)
        if (_isWakeWordEnabled.value && hasRecordAudioPermission()) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, com.voicecontrol.app.wake.WakeListenerService::class.java))
        } else if (_isWakeWordEnabled.value) {
            _requestWakePermission.tryEmit(Unit)
        }

        addBotMessage("Hello! I'm your AI assistant. Try 'open YouTube', 'send message to [name] saying [text]', 'set alarm at 7am', 'read messages', or 'flashlight on'.")

        viewModelScope.launch {
            com.voicecontrol.app.wake.WakeEventBus.wakeDetected.collect {
                if (!_isWakeWordEnabled.value) return@collect
                handleWakeDetected()
            }
        }
    }

    fun saveGeminiApiKey(key: String) {
        SecureKeyStore.saveGeminiApiKey(getApplication(), key)
        _geminiApiKey.value = key
        geminiClient = GeminiClient(key)
        updateAgentLlmCall()
    }

    fun saveGroqApiKey(key: String) {
        SecureKeyStore.saveGroqApiKey(getApplication(), key)
        _groqApiKey.value = key
        groqClient = GroqClient(key)
        updateAgentLlmCall()
    }

    fun saveWeatherApiKey(key: String) {
        SecureKeyStore.saveWeatherApiKey(getApplication(), key)
        _weatherApiKey.value = key
        weatherClient = WeatherClient(key)
    }

    fun setAiProvider(provider: AiProvider) {
        _aiProvider.value = provider
        updateAgentLlmCall()
    }

    private fun updateAgentLlmCall() {
        agentLlmEngine.llmCall = { prompt -> currentLlmCall(prompt) }
    }

    private suspend fun currentLlmCall(prompt: String): String {
        if (_geminiApiKey.value.isBlank() && _groqApiKey.value.isBlank())
            return "No API key set. Enter it in Settings."
        repeat(3) { attempt ->
            val result = when (_aiProvider.value) {
                AiProvider.GEMINI -> geminiClient.generateResponse(prompt, emptyList())
                AiProvider.GROQ -> groqClient.generateResponse(prompt, emptyList())
            }
            if (!result.startsWith("Error") || attempt == 2) return result
            delay(1000)
        }
        return "Server se response nahi aaya. Dobara try karein."
    }

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
            // Fast offline exact matches — no LLM needed
            when {
                lower in listOf("help", "what can you do") -> {
                    addBotMessage("I can do many things! Try 'flashlight on', 'set alarm at 7am', 'send message to Mom saying hi', 'read notifications', 'wifi on karo', 'open YouTube'.")
                    return@launch
                }
                lower in listOf("show apps", "list apps", "show installed apps") -> {
                    val apps = getInstalledApps()
                    addBotMessage(if (apps.isEmpty()) "No apps found." else "Installed apps:\n" + apps.joinToString("\n") { "• ${it.first}" })
                    return@launch
                }
                lower in listOf("flashlight on", "turn on flashlight", "torch on") -> {
                    addBotMessage(DeviceController.toggleFlashlight(getApplication(), true))
                    return@launch
                }
                lower in listOf("flashlight off", "turn off flashlight", "torch off") -> {
                    addBotMessage(DeviceController.toggleFlashlight(getApplication(), false))
                    return@launch
                }
                lower in listOf("mute", "mute phone", "silence phone") -> {
                    addBotMessage(DeviceController.mutePhone(getApplication()))
                    return@launch
                }
                lower in listOf("unmute", "unmute phone") -> {
                    addBotMessage(DeviceController.unmutePhone(getApplication()))
                    return@launch
                }
                lower.startsWith("set volume") || lower.startsWith("volume ") || lower == "volume" -> {
                    val n = extractInt(lower, Regex("set volume|volume|set brightness|brightness|to"))?.coerceIn(0, 100)
                    addBotMessage(if (n != null) DeviceController.setVolume(getApplication(), n) else "Volume number samajh nahi aaya.")
                    return@launch
                }
                lower.startsWith("set brightness") || lower.startsWith("brightness ") || lower == "brightness" -> {
                    val n = extractInt(lower, Regex("set volume|volume|set brightness|brightness|to"))?.coerceIn(0, 100)
                    addBotMessage(if (n != null) DeviceController.setBrightness(getApplication(), n) else "Brightness number samajh nahi aaya.")
                    return@launch
                }
                lower.startsWith("send message to ") || lower.startsWith("send sms to ") || lower.startsWith("text ") -> {
                    if (!checkSmsPermission()) return@launch
                    val parts = when {
                        lower.startsWith("text ") -> lower.removePrefix("text ").trim().split(" ", limit = 2).let { if (it.size < 2) { addBotMessage("Usage: text [name] [message]."); return@launch } else it }
                        else -> lower.removePrefix("send message to ").removePrefix("send sms to ").split(" saying ", limit = 2).let { if (it.size < 2) { addBotMessage("Usage: send message to [name] saying [message]."); return@launch } else it }
                    }
                    addBotMessage(SmsManager.sendSms(getApplication(), parts[0].trim(), parts[1].trim()))
                    return@launch
                }
                lower in listOf("read messages", "show messages", "read sms") -> {
                    if (!checkSmsPermission()) return@launch
                    addBotMessage(SmsManager.readRecentSms(getApplication()))
                    return@launch
                }
                lower in listOf("read notifications", "show notifications", "any notifications", "what's new", "whats new") -> {
                    val ctx = getApplication<Application>()
                    if (!NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName))
                        addBotMessage("Please grant Notification Access: go to Settings > Notifications > Notification Access > enable AI Assistant.")
                    else addBotMessage(NotificationService.getSummary())
                    return@launch
                }
                lower.startsWith("set alarm") || lower.startsWith("wake me") || lower.startsWith("alarm at") -> {
                    try {
                        val parsed = AlarmHelper.parseTimeFromText(lower)
                        addBotMessage(if (parsed == null) "Time samajh nahi aaya. Try 'set alarm at 7am'." else AlarmHelper.setAlarm(getApplication(), parsed.first, parsed.second))
                    } catch (e: Exception) { addBotMessage("Alarm error: ${e.message}") }
                    return@launch
                }
                lower.startsWith("set timer") || lower.startsWith("timer for") -> {
                    val duration = parseDuration(lower)
                    addBotMessage(if (duration != null) AlarmHelper.setTimer(getApplication(), duration) else "Duration samajh nahi aaya. Try 'set timer for 5 minutes'.")
                    return@launch
                }
            }

            if (!_isGeminiEnabled.value || (_geminiApiKey.value.isBlank() && _groqApiKey.value.isBlank())) {
                addBotMessage("AI is disabled or no API key. Enable in Settings.")
                return@launch
            }

            val result = llmRoute(command)
            if (result != null) addBotMessage(result)
        }
    }

private suspend fun llmRoute(command: String): String? {
        val prompt = buildString {
            appendLine("ROUTER — Classify this command into one intent. Return ONLY JSON, no other text.")
            appendLine("Intents: flash_on, flash_off, mute, unmute, volume_set, brightness_set,")
            appendLine("wifi_on, wifi_off, bt_on, bt_off, data_on, data_off, airplane_on, airplane_off,")
            appendLine("open_app, show_apps, sms_send, sms_read, notif_read,")
            appendLine("alarm_set, timer_set, contact_find, contact_list, cal_read, cal_add,")
            appendLine("weather, music_play, music_pause, music_next, music_prev, music_current,")
            appendLine("compound (multi-step, needs UI automation), call, chat, help")
            appendLine()
            appendLine("{\"intent\":\"\",\"params\":{},\"needs_agent\":false,\"speech\":\"\"}")
            append("Command: $command")
        }

        _isThinking.value = true
        val response = currentLlmCall(prompt)
        _isThinking.value = false
        val json = try {
            val s = response.indexOf('{')
            val e = response.lastIndexOf('}')
            if (s >= 0 && e > s) JSONObject(response.substring(s, e + 1)) else null
        } catch (_: Exception) { null }

        if (json == null) return response

        val intent = json.optString("intent", "chat")
        val params = json.optJSONObject("params") ?: JSONObject()
        val needsAgent = json.optBoolean("needs_agent", false)
        val speech = json.optString("speech", "")

        return when (intent) {
            "flash_on" -> DeviceController.toggleFlashlight(getApplication(), true)
            "flash_off" -> DeviceController.toggleFlashlight(getApplication(), false)
            "mute" -> DeviceController.mutePhone(getApplication())
            "unmute" -> DeviceController.unmutePhone(getApplication())
            "volume_set" -> DeviceController.setVolume(getApplication(), params.optInt("level", 50).coerceIn(0, 100))
            "brightness_set" -> DeviceController.setBrightness(getApplication(), params.optInt("level", 50).coerceIn(0, 100))
            "weather" -> {
                if (_weatherApiKey.value.isBlank()) "Weather API key nahi hai. Settings mein add karein."
                else weatherClient.getWeather(params.optString("city", ""))
            }
            "music_play" -> MusicController.playPause(getApplication())
            "music_pause" -> MusicController.playPause(getApplication())
            "music_next" -> MusicController.next(getApplication())
            "music_prev" -> MusicController.previous(getApplication())
            "music_current" -> MusicController.getCurrentTrack(getApplication())
            "sms_send" -> {
                if (!checkSmsPermission()) return "SMS permission nahi hai."
                SmsManager.sendSms(getApplication(), params.optString("contact", ""), params.optString("message", ""))
            }
            "sms_read" -> {
                if (!checkSmsPermission()) return "SMS permission nahi hai."
                SmsManager.readRecentSms(getApplication())
            }
            "notif_read" -> {
                val ctx = getApplication<Application>()
                if (!NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName))
                    "Please grant Notification Access."
                else NotificationService.getSummary()
            }
            "alarm_set" -> {
                val time = AlarmHelper.parseTimeFromText(params.optString("time", command))
                if (time != null) AlarmHelper.setAlarm(getApplication(), time.first, time.second)
                else "Time samajh nahi aaya."
            }
            "timer_set" -> {
                val mins = params.optInt("minutes", -1)
                if (mins > 0) AlarmHelper.setTimer(getApplication(), mins * 60)
                else "Duration samajh nahi aaya."
            }
            "contact_find" -> ContactsHelper.findContact(getApplication(), params.optString("name", command))
            "contact_list" -> ContactsHelper.listRecentContacts(getApplication())
            "cal_read" -> CalendarHelper.getTodayEvents(getApplication())
            "cal_add" -> {
                val t = AlarmHelper.parseTimeFromText(params.optString("time", ""))
                CalendarHelper.addEvent(getApplication(), params.optString("title", "Event"), t?.first ?: 12, t?.second ?: 0)
            }
            "show_apps" -> {
                val apps = getInstalledApps()
                if (apps.isEmpty()) "No apps found."
                else "Installed apps:\n" + apps.joinToString("\n") { "• ${it.first}" }
            }
            "help" -> "I can do many things! Try 'flashlight on', 'set alarm at 7am', 'send message to Mom saying hi', 'read notifications', 'wifi on karo', 'open YouTube'."
            "call" -> {
                val name = params.optString("name", "")
                val number = params.optString("number", "")
                if (number.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    getApplication<Application>().startActivity(intent)
                    "$name ko call kar raha hoon"
                } else if (name.isNotBlank()) {
                    ContactsHelper.findContact(getApplication(), name)
                } else "Call karna hai but kise bulana hai?"
            }
            "wifi_on", "wifi_off", "bt_on", "bt_off", "data_on", "data_off", "airplane_on", "airplane_off",
            "open_app", "compound" -> {
                agentLlmEngine.startTask(command, viewModelScope)
                null
            }
            else -> if (needsAgent) {
                agentLlmEngine.startTask(command, viewModelScope)
                null
            } else speech.ifEmpty { getAiResponse(command) }
        }
    }

    fun cancelAgent() {
        agentLlmEngine.cancelTask()
    }

    private suspend fun getAiResponse(prompt: String): String {
        if (!_isGeminiEnabled.value) return "AI is disabled. Enable in Settings."
        val history = conversationMemory.getHistory()
        if (_geminiApiKey.value.isBlank() && _groqApiKey.value.isBlank()) return "No API key set. Enter it in Settings."
        return when (_aiProvider.value) {
            AiProvider.GEMINI -> geminiClient.generateResponse(prompt, history)
            AiProvider.GROQ -> groqClient.generateResponse(prompt, history)
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
                speechRecognizer?.destroy()
                speechRecognizer = null
                addBotMessage("Didn't catch that. Please try again.")
                com.voicecontrol.app.wake.WakeListenerService.resumeAfterCommand()
            }
            override fun onResults(results: Bundle?) {
                _isListening.value = false
                speechRecognizer?.destroy()
                speechRecognizer = null
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull()
                if (spokenText != null) {
                    addUserMessage(spokenText)
                    conversationMemory.saveMessage("user", spokenText)
                    viewModelScope.launch {
                        processCommand(spokenText)
                        if (agentLlmEngine.isRunning.value) {
                            agentLlmEngine.isRunning.first { running -> !running }
                        }
                        com.voicecontrol.app.wake.WakeListenerService.resumeAfterCommand()
                    }
                } else {
                    addBotMessage("Couldn't understand. Please try again.")
                    com.voicecontrol.app.wake.WakeListenerService.resumeAfterCommand()
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
        speechRecognizer?.destroy()
        speechRecognizer = null
        _isListening.value = false
    }

    private suspend fun handleWakeDetected() {
        val ttsManager = (getApplication<Application>() as com.voicecontrol.app.VoiceControlApp).sharedTtsManager
        ttsManager.speakAndAwait("Yes sir, kaise madad karu?")
        delay(400)
        startListening()
    }

    fun toggleTts() {
        _isTtsEnabled.value = !_isTtsEnabled.value
    }

    fun toggleGemini() {
        _isGeminiEnabled.value = !_isGeminiEnabled.value
    }

    fun toggleWakeWord() {
        val newState = !_isWakeWordEnabled.value
        _isWakeWordEnabled.value = newState
        val ctx = getApplication<Application>()
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("wake_word_enabled", newState)
            .apply()
        if (newState) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
                addBotMessage("❌ Battery optimization allow karo wake word ke liye: Settings > Apps > AI Assistant > Battery > Unrestricted")
            }
            if (hasRecordAudioPermission()) {
                ContextCompat.startForegroundService(ctx, Intent(ctx, com.voicecontrol.app.wake.WakeListenerService::class.java))
            } else {
                _requestWakePermission.tryEmit(Unit)
            }
        } else {
            stopWakeService()
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun startWakeServiceFromPermission() {
        val context = getApplication<Application>()
        val intent = Intent(context, com.voicecontrol.app.wake.WakeListenerService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun toggleDarkMode() {
        val newState = !_isDarkMode.value
        _isDarkMode.value = newState
        getApplication<Application>()
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("dark_mode", newState)
            .apply()
    }

    private fun stopWakeService() {
        val context = getApplication<Application>()
        context.stopService(Intent(context, com.voicecontrol.app.wake.WakeListenerService::class.java))
    }

    fun clearHistory() {
        conversationMemory.clearHistory()
        _messages.value = emptyList()
    }

    fun addSystemMessage(text: String) {
        addBotMessage(text)
    }

    private fun checkSmsPermission(): Boolean {
        val ctx = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) return true
        _requestSmsPermission.tryEmit(Unit)
        return false
    }

    private fun addUserMessage(text: String) {
        _messages.value = _messages.value + Message(text = text, isUser = true)
    }

    private fun addBotMessage(text: String) {
        _messages.value = _messages.value + Message(text = text, isUser = false)
        if (_isTtsEnabled.value) {
            getApplication<VoiceControlApp>().sharedTtsManager.speak(text)
        }
        conversationMemory.saveMessage("assistant", text)
    }

    private fun extractInt(input: String, strip: Regex): Int? =
        Regex("""(\d+)""").find(input.lowercase().replace(strip, "").trim())?.groupValues?.get(1)?.toInt()

    private fun parseDuration(input: String): Int? {
        val n = extractInt(input, Regex("set timer|timer for|timer|for")) ?: return null
        val cleaned = input.lowercase().replace(Regex("set timer|timer for|timer|for"), "").trim()
        return when {
            Regex("""(\d+)\s*(min|mins|minute|minutes|m)""").find(cleaned) != null -> n * 60
            Regex("""(\d+)\s*(sec|secs|second|seconds|s)""").find(cleaned) != null -> n
            Regex("""(\d+)\s*(hour|hours|h)""").find(cleaned) != null -> n * 3600
            else -> n * 60
        }
    }

    private fun getInstalledApps(): List<Pair<String, String>> {
        val pm = getApplication<Application>().packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        return pm.queryIntentActivities(intent, 0).map { ri ->
            ri.loadLabel(pm).toString() to ri.activityInfo.packageName
        }.sortedBy { it.first.lowercase() }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
    }
}
