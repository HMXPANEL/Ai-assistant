package com.voicecontrol.app.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.voicecontrol.app.data.GeminiClient
import com.voicecontrol.app.data.Mode
import com.voicecontrol.app.security.SecureKeyStore
import com.voicecontrol.app.service.AutoAgentService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentLlmEngine(private val context: Context) {

    private val ttsManager = AgentTtsManager(context)
    private val geminiClient: GeminiClient?
        get() {
            val key = SecureKeyStore.getGeminiApiKey(context)
            return if (key.isNullOrBlank()) null else GeminiClient(key, Mode.AGENT)
        }

    companion object {
        private const val TAG = "AgentLlmEngine"
        private const val MAX_ITERATIONS = 30

        private const val MAX_HISTORY_MESSAGES = 10

        private const val SYSTEM_PROMPT = """You are Krinry, AI phone assistant. Full device control via AccessibilityService. Respond ONLY in valid JSON, no markdown.

ACTIONS (JSON format: {"action":"X","speech":"Hindi or empty","reason":"why","status":"in_progress|done"} + action-specific fields):
- open_app: +app_name | click: +node_id | type: +node_id,text | tap_xy: +x,y | long_press: +x,y
- scroll_down/scroll_up | swipe: +text(left|right|up|down) | back/home/recent
- open_url: +url | screenshot | copy | paste: +node_id | select_all | open_notifications
- wait | done: status="done"

UI nodes: i=id,t=text,d=desc,T=type(B=Button,E=EditText,IB=ImageButton,TV=TextView,IV=ImageView),x=centerX,y=centerY,c=clickable,e=editable,s=scrollable. Use node_id(i) for click/type. Fallback: tap_xy with x,y coords.

RULES:
1. Speech: Hindi only. First step=short confirm, middle=empty, done=completion msg, error=Hindi explain
2. Apps: ALWAYS open_app first, never scroll home. Use exact name: "WhatsApp","YouTube","Chrome"
3. NEVER say done early. After type→MUST click Send button→verify→done. Complete full task inside app
4. Node missing? scroll→tap_xy→search by text. Give up only after trying all
5. Verify before done: check screen confirms action worked
6. Multiple matches? Ask user via speech. One match? Proceed"""
    }

    var onStatusUpdate: ((String) -> Unit)? = null
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private var currentJob: Job? = null
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    private var pinnedGoal = ""

    fun startTask(voiceCommand: String, scope: CoroutineScope) {
        conversationHistory.clear()
        currentJob?.cancel()
        ttsManager.stop()
        currentJob = scope.launch {
            runAgentLoop(voiceCommand)
        }
    }

    fun cancelTask() {
        currentJob?.cancel()
        currentJob = null
        ttsManager.stop()
        onStatusUpdate?.invoke("⏹ Ruk gaya")
    }

    private suspend fun runAgentLoop(command: String) {
        _isRunning.value = true
        try {
        val client = geminiClient
        if (client == null) {
            onStatusUpdate?.invoke("❌ Gemini API key set nahi hai")
            ttsManager.speak("Pehle settings mein API key daalo.")
            return
        }

        val service = AutoAgentService.instance
        if (service == null) {
            onStatusUpdate?.invoke("❌ Accessibility Service on nahi hai")
            ttsManager.speak("Accessibility Service chalu karo pehle.")
            return
        }

        onStatusUpdate?.invoke("🧠 Samajh raha hoon: \"$command\"")
        Log.d(TAG, "Starting task: $command")
        pinnedGoal = command

        val currentTime = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val currentDate = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US).format(Date())
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val installedApps = pm.queryIntentActivities(mainIntent, 0)
            .map { it.loadLabel(pm).toString() }
            .sorted()
            .take(15)
            .joinToString(", ")

        for (iteration in 1..MAX_ITERATIONS) {
            if (!isActive) return

            Log.d(TAG, "=== Step $iteration ===")

            val rootNode = service.getRootNode()
            if (rootNode == null) {
                onStatusUpdate?.invoke("❌ Screen nahi padh paya")
                delay(800)
                continue
            }

            val uiNodes = UiTreeExtractor.extractTree(rootNode)
            val uiJson = UiTreeExtractor.toJson(uiNodes)
            Log.d(TAG, "UI nodes: ${uiNodes.size}")

            val userMessage = if (iteration == 1) {
                "GOAL:$pinnedGoal\nTIME:$currentTime\nDATE:$currentDate\nAPPS:$installedApps\nSCREEN:$uiJson"
            } else {
                "GOAL:$pinnedGoal\nSTEP:$iteration\nSCREEN:$uiJson"
            }

            onStatusUpdate?.invoke("🤔 Step $iteration...")
            val llmResponse = try {
                val fullPrompt = buildString {
                    appendLine(SYSTEM_PROMPT)
                    appendLine()
                    for ((role, text) in conversationHistory) {
                        appendLine("$role: $text")
                    }
                    appendLine("user: $userMessage")
                }
                client.generateResponse(fullPrompt, emptyList())
            } catch (e: Exception) {
                Log.e(TAG, "LLM call failed: ${e.message}")
                onStatusUpdate?.invoke("❌ ${e.message?.take(50) ?: "Server error"}")
                ttsManager.speak("Server se jawab nahi aaya.")
                return
            }

            if (llmResponse.isBlank()) {
                onStatusUpdate?.invoke("❌ Empty response from server")
                ttsManager.speak("Server ne koi jawab nahi diya.")
                return
            }

            Log.d(TAG, "LLM response: $llmResponse")

            conversationHistory.add("user" to userMessage)
            conversationHistory.add("assistant" to llmResponse)

            while (conversationHistory.size > MAX_HISTORY_MESSAGES) {
                conversationHistory.removeAt(0)
            }

            val action = ActionExecutor.parseResponse(llmResponse)
            if (action == null) {
                onStatusUpdate?.invoke("❌ Response samajh nahi aaya")
                delay(1000)
                continue
            }

            val reasonText = action.reason ?: action.action
            onStatusUpdate?.invoke("⚡ ${getHindiAction(action.action)}: $reasonText")

            action.speech?.takeIf { it.isNotBlank() }?.let { speechText ->
                ttsManager.speak(speechText)
            }

            if (action.status == "done" || action.action == "done") {
                onStatusUpdate?.invoke("✅ Ho gaya: ${action.reason ?: "Task complete"}")
                delay(2500)
                return
            }

            val result = ActionExecutor.execute(action, uiNodes)
            Log.d(TAG, "Result: $result")
            onStatusUpdate?.invoke(result)

            if (result.startsWith("❌")) {
                Log.w(TAG, "Action failed: $result")
                conversationHistory.add("user" to "SYSTEM: Previous action failed. Error: $result. Try a different approach.")
                while (conversationHistory.size > MAX_HISTORY_MESSAGES) {
                    conversationHistory.removeAt(0)
                }
            }

            waitForStableScreen(service)
        }

        onStatusUpdate?.invoke("⚠️ Bahut steps ho gaye ($MAX_ITERATIONS)")
        ttsManager.speak("Kaam time pe complete nahi ho paya. Chhota command try karo.")
        } finally {
            _isRunning.value = false
        }
    }

    private fun getHindiAction(action: String): String {
        return when (action) {
            "click" -> "Click kar raha hoon"
            "type" -> "Type kar raha hoon"
            "scroll_down" -> "Neeche scroll kar raha hoon"
            "scroll_up" -> "Upar scroll kar raha hoon"
            "back" -> "Back ja raha hoon"
            "home" -> "Home ja raha hoon"
            "recent" -> "Recent apps dekh raha hoon"
            "open_app" -> "App khol raha hoon"
            "open_url" -> "URL khol raha hoon"
            "tap_xy" -> "Tap kar raha hoon"
            "long_press" -> "Long press kar raha hoon"
            "swipe" -> "Swipe kar raha hoon"
            "screenshot" -> "Screenshot le raha hoon"
            "copy" -> "Copy kar raha hoon"
            "paste" -> "Paste kar raha hoon"
            "select_all" -> "Sab select kar raha hoon"
            "open_notifications" -> "Notifications dekh raha hoon"
            "wait" -> "Ruk raha hoon"
            "done" -> "Ho gaya"
            else -> action
        }
    }

    private suspend fun waitForStableScreen(service: AutoAgentService): List<UiNode> {
        var previousHash: Int? = null
        var stableNodes: List<UiNode> = emptyList()
        withTimeoutOrNull(3000L) {
            while (true) {
                val rootNode = service.getRootNode()
                stableNodes = if (rootNode != null) UiTreeExtractor.extractTree(rootNode) else emptyList()
                val hash = stableNodes.joinToString { it.text + it.id }.hashCode()
                if (previousHash != null && hash == previousHash) return@withTimeoutOrNull
                previousHash = hash
                delay(250)
            }
        }
        return stableNodes
    }

    private val isActive: Boolean
        get() = currentJob?.isActive == true
}
