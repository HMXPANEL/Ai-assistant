package com.voicecontrol.app.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.voicecontrol.app.service.AutoAgentService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentLlmEngine(private val context: Context) {

    var llmCall: suspend (String) -> String = { "No LLM configured." }

    private val ttsManager = AgentTtsManager(context)

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
2. App info is checked automatically. If APP_FOUND is given, use that exact app_name with open_app. If APP_NOT_FOUND, tell user it's not installed.
3. NEVER say done early. After type→MUST click Send button→verify→done. Complete full task inside app
4. Node missing? scroll→tap_xy→search by text. Give up only after trying all
5. Verify before done: check screen confirms action worked
6. Multiple matches? Ask user via speech. One match? Proceed

TOGGLE WIFI/BLUETOOTH/DATA/AIRPLANE:
Command examples: "wifi on karo", "bluetooth band kar do", "wifi chalu kar", "mobile data on", "airplane mode off karo", "turn on wifi", "bluetooth enable karo"
1. Use open_app with app_name "Settings" to open Android settings
2. Search screen for "WiFi" or "Network" or "Connections" section. Tap it
3. Find the toggle switch (usually a Switch/Button with text "WiFi" or "Bluetooth"). Click it via node_id or text
4. Verify by checking toggle state text changes (on/off)
5. Press back to return to home
6. If Settings app can't be opened directly, open the Quick Settings panel by swiping down from top"""
    }

    var onStatusUpdate: ((String) -> Unit)? = null
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private var currentJob: Job? = null
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    private var pinnedGoal = ""
    private var taskGeneration = 0

    fun startTask(voiceCommand: String, scope: CoroutineScope) {
        conversationHistory.clear()
        currentJob?.cancel()
        ttsManager.stop()
        _isRunning.value = true
        val myGeneration = ++taskGeneration
        currentJob = scope.launch {
            runAgentLoop(voiceCommand, myGeneration)
        }
    }

    fun cancelTask() {
        currentJob?.cancel()
        currentJob = null
        ttsManager.stop()
        onStatusUpdate?.invoke("⏹ Ruk gaya")
    }

    private suspend fun runAgentLoop(command: String, myGeneration: Int) {
        try {
        val call = llmCall

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

        // ponytail: check requested app locally instead of sending full app list to Gemini (saves tokens)
        val appStatus = findRequestedAppStatus(command)

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
                "GOAL:$pinnedGoal\nTIME:$currentTime\nDATE:$currentDate\n$appStatus\nSCREEN:$uiJson"
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
                call(fullPrompt)
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

            if (action.action == "done") {
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
            if (myGeneration == taskGeneration) {
                _isRunning.value = false
            }
        }
    }

    private val actionLabels = mapOf(
        "click" to "Click kar raha hoon",
        "type" to "Type kar raha hoon",
        "scroll_down" to "Neeche scroll kar raha hoon",
        "scroll_up" to "Upar scroll kar raha hoon",
        "back" to "Back ja raha hoon",
        "home" to "Home ja raha hoon",
        "recent" to "Recent apps dekh raha hoon",
        "open_app" to "App khol raha hoon",
        "open_url" to "URL khol raha hoon",
        "tap_xy" to "Tap kar raha hoon",
        "long_press" to "Long press kar raha hoon",
        "swipe" to "Swipe kar raha hoon",
        "screenshot" to "Screenshot le raha hoon",
        "copy" to "Copy kar raha hoon",
        "paste" to "Paste kar raha hoon",
        "select_all" to "Sab select kar raha hoon",
        "open_notifications" to "Notifications dekh raha hoon",
        "wait" to "Ruk raha hoon",
        "done" to "Ho gaya",
    )
    private fun getHindiAction(action: String): String = actionLabels[action] ?: action

    private suspend fun waitForStableScreen(service: AutoAgentService): List<UiTreeExtractor.UiNode> {
        var previousHash: Int? = null
        var stableNodes: List<UiTreeExtractor.UiNode> = emptyList()
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

    private fun findRequestedAppStatus(command: String): String {
        val lower = command.lowercase()
        // compound command (has "and") → let Gemini handle it
        if (lower.contains(" and ")) return ""
        val prefixes = listOf("open ", "launch ", "start ")
        val prefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return ""
        val appName = lower.removePrefix(prefix).trim()
        if (appName.isBlank()) return ""

        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val activities = pm.queryIntentActivities(intent, 0)

        val match = activities.firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            label == appName || label.contains(appName) || appName.contains(label) ||
            info.activityInfo.packageName.lowercase().contains(appName)
        }

        return if (match != null) {
            val foundName = match.loadLabel(pm).toString()
            "APP_FOUND:\"$foundName\" — Installed on device. Use open_app with app_name:\"$foundName\"."
        } else {
            "APP_NOT_FOUND:\"$appName\""
        }
    }

    private val isActive: Boolean
        get() = currentJob?.isActive == true
}
