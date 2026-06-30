package com.voicecontrol.app.agent

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.voicecontrol.app.service.AutoAgentService
import org.json.JSONObject

object ActionExecutor {

    private const val TAG = "ActionExecutor"

    data class AgentAction(
        val action: String,
        val nodeId: Int?,
        val text: String?,
        val appName: String?,
        val url: String?,
        val speech: String?,
        val status: String,
        val x: Int?,
        val y: Int?,
        val reason: String?
    )

    fun parseResponse(json: String): AgentAction? {
        return try {
            val cleanJson = extractJsonObject(json)
            if (cleanJson == null) {
                Log.e(TAG, "No JSON found in: ${json.take(200)}")
                return null
            }

            val obj = JSONObject(cleanJson)
            AgentAction(
                action = obj.optString("action", "wait"),
                nodeId = if (obj.has("node_id") && !obj.isNull("node_id")) obj.optInt("node_id", -1) else null,
                text = obj.optString("text", "").takeIf { it.isNotEmpty() },
                appName = obj.optString("app_name", "").takeIf { it.isNotEmpty() },
                url = obj.optString("url", "").takeIf { it.isNotEmpty() },
                speech = obj.optString("speech", "").takeIf { it.isNotEmpty() },
                status = obj.optString("status", "in_progress"),
                x = if (obj.has("x")) obj.optInt("x") else null,
                y = if (obj.has("y")) obj.optInt("y") else null,
                reason = obj.optString("reason", "").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed: ${json.take(200)}", e)
            null
        }
    }

    private fun extractJsonObject(raw: String): String? {
        val stripped = raw
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        val start = stripped.indexOf('{')
        if (start < 0) return null

        var depth = 0
        for (i in start until stripped.length) {
            when (stripped[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripped.substring(start, i + 1)
                }
            }
        }
        val end = stripped.lastIndexOf('}')
        return if (end > start) stripped.substring(start, end + 1) else null
    }

    fun execute(action: AgentAction, nodes: List<UiTreeExtractor.UiNode>): String {
        val service = AutoAgentService.instance
            ?: return "❌ Accessibility service nahi chal rahi"

        return when (action.action) {
            "click" -> executeClick(action, nodes, service)
            "type" -> executeType(action, nodes, service)
            "scroll_down" -> executeScroll(true, action, nodes, service)
            "scroll_up" -> executeScroll(false, action, nodes, service)
            "back" -> { service.pressBack(); "✅ Back dabaya" }
            "home" -> { service.pressHome(); "✅ Home dabaya" }
            "recent" -> { service.openRecents(); "✅ Recent apps khule" }
            "open_app" -> {
                val appName = action.appName ?: action.text ?: return "❌ App ka naam nahi diya"
                openAppByName(service.applicationContext, appName)
            }
            "open_url" -> executeOpenUrl(action, service)
            "tap_xy" -> executeTapXY(action, service)
            "swipe" -> executeSwipe(action, service)
            "long_press" -> executeLongPress(action, service)
            "screenshot" -> executeScreenshot(service)
            "copy" -> executeCopy(service)
            "paste" -> executePaste(action, nodes, service)
            "select_all" -> executeSelectAll(action, nodes, service)
            "open_notifications" -> { service.openNotifications(); "✅ Notifications khol diya" }
            "wait" -> "⏳ Screen load ho raha hai..."
            "done" -> "✅ Kaam ho gaya!"
            else -> "❓ Unknown action: ${action.action}"
        }
    }

    private fun executeClick(
        action: AgentAction,
        nodes: List<UiTreeExtractor.UiNode>,
        service: AutoAgentService
    ): String {
        val nodeId = action.nodeId ?: return "❌ Click ke liye node_id chahiye"

        var uiNode = UiTreeExtractor.findNodeById(nodes, nodeId)

        if (uiNode == null && action.text != null) {
            uiNode = UiTreeExtractor.findNodeByText(nodes, action.text)
            if (uiNode != null) {
                Log.d(TAG, "Node $nodeId nahi mila, text se mila: '${action.text}' → id=${uiNode.id}")
            }
        }

        if (uiNode == null && action.reason != null) {
            uiNode = UiTreeExtractor.findNodeByText(nodes, action.reason)
        }

        if (uiNode == null) {
            Log.w(TAG, "Node $nodeId nahi mila, koi text match bhi nahi")
            return "❌ Element nahi mila (node $nodeId) — screen badal gaya"
        }

        val label = uiNode.text.ifEmpty { uiNode.contentDescription }.ifEmpty { "element" }

        val success = service.clickNode(uiNode.nodeInfo)
        if (success) {
            return "✅ '$label' pe click kiya"
        }

        val bounds = uiNode.bounds
        if (bounds.width() > 0 && bounds.height() > 0) {
            service.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            return "✅ '$label' pe tap kiya (gesture)"
        }

        return "❌ '$label' pe click nahi ho paya"
    }

    private fun executeType(
        action: AgentAction,
        nodes: List<UiTreeExtractor.UiNode>,
        service: AutoAgentService
    ): String {
        val nodeId = action.nodeId ?: return "❌ Type ke liye node_id chahiye"
        val text = action.text ?: return "❌ Text nahi diya type ke liye"

        var uiNode = UiTreeExtractor.findNodeById(nodes, nodeId)

        if (uiNode == null) {
            uiNode = nodes.find { it.editable }
            if (uiNode != null) {
                Log.d(TAG, "Node $nodeId nahi mila, editable field use kar rahe hain: id=${uiNode.id}")
            }
        }

        if (uiNode == null) {
            return "❌ Text field nahi mila (node $nodeId)"
        }

        service.clickNode(uiNode.nodeInfo)
        Thread.sleep(300)

        val success = service.setTextOnNode(uiNode.nodeInfo, text)
        return if (success) "✅ Type kiya: '$text'" else "❌ Type nahi ho paya"
    }

    private fun executeScroll(
        forward: Boolean,
        action: AgentAction,
        nodes: List<UiTreeExtractor.UiNode>,
        service: AutoAgentService
    ): String {
        val nodeId = action.nodeId

        val scrollNode = if (nodeId != null) {
            UiTreeExtractor.findNodeById(nodes, nodeId)
        } else {
            nodes.find { it.scrollable }
        }

        if (scrollNode != null) {
            if (forward) {
                service.scrollForward(scrollNode.nodeInfo)
            } else {
                service.scrollBackward(scrollNode.nodeInfo)
            }
        } else {
            val root = service.getRootNode()
            if (root != null) {
                if (forward) service.scrollForward(root)
                else service.scrollBackward(root)
            }
        }

        return if (forward) "✅ Neeche scroll kiya" else "✅ Upar scroll kiya"
    }

    private fun executeTapXY(action: AgentAction, service: AutoAgentService): String {
        val x = action.x ?: return "❌ x coordinate chahiye"
        val y = action.y ?: return "❌ y coordinate chahiye"
        service.tapAt(x.toFloat(), y.toFloat())
        return "✅ Tap kiya ($x, $y) pe"
    }

    private fun executeSwipe(action: AgentAction, service: AutoAgentService): String {
        val direction = action.text?.lowercase() ?: "down"
        when (direction) {
            "left" -> service.performGlobalSwipe(800f, 1200f, 200f, 1200f, 300)
            "right" -> service.performGlobalSwipe(200f, 1200f, 800f, 1200f, 300)
            "up" -> service.performGlobalSwipe(540f, 1500f, 540f, 500f, 350)
            else -> service.performGlobalSwipe(540f, 500f, 540f, 1500f, 350)
        }
        return "✅ Swipe kiya: $direction"
    }

    private fun executeOpenUrl(action: AgentAction, service: AutoAgentService): String {
        val url = action.url ?: action.text ?: return "❌ URL nahi diya"
        val context = service.applicationContext
        return try {
            val finalUrl = if (url.startsWith("http://") || url.startsWith("https://")) url
                           else "https://$url"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "✅ URL khola: $finalUrl"
        } catch (e: Exception) {
            "❌ URL nahi khul paya: ${e.message}"
        }
    }

    private fun openAppByName(context: android.content.Context, appName: String): String {
        val pm = context.packageManager

        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else { 0 }

        val activities = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, resolveFlags)
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryIntentActivities failed", e)
            emptyList()
        }

        Log.d(TAG, "${activities.size} launchable apps mile, dhundh rahe hain: '$appName'")

        val exactMatch = activities.firstOrNull { info ->
            info.loadLabel(pm).toString().equals(appName, ignoreCase = true)
        }

        val fuzzyMatch = exactMatch ?: activities.firstOrNull { info ->
            val label = info.loadLabel(pm).toString()
            label.contains(appName, ignoreCase = true) ||
            appName.contains(label, ignoreCase = true)
        }

        val packageMatch = fuzzyMatch ?: activities.firstOrNull { info ->
            info.activityInfo.packageName.contains(appName, ignoreCase = true)
        }

        val wordMatch = packageMatch ?: run {
            val searchWords = appName.lowercase().replace(" ", "")
            activities.firstOrNull { info ->
                val label = info.loadLabel(pm).toString().lowercase().replace(" ", "")
                label.contains(searchWords) || searchWords.contains(label)
            }
        }

        val resolved = wordMatch
        if (resolved != null) {
            val label = resolved.loadLabel(pm).toString()
            val packageName = resolved.activityInfo.packageName
            return try {
                val intent = pm.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "✅ '$label' khol diya"
                } else {
                    val directIntent = Intent().apply {
                        setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(directIntent)
                    "✅ '$label' khol diya (direct)"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Launch failed: '$label'", e)
                "❌ '$label' nahi khul paya: ${e.message}"
            }
        }

        Log.w(TAG, "App nahi mili: '$appName'")
        return "❌ '$appName' app nahi mili device pe"
    }

    private fun executeLongPress(action: AgentAction, service: AutoAgentService): String {
        val x = action.x ?: return "❌ x coordinate chahiye"
        val y = action.y ?: return "❌ y coordinate chahiye"
        service.longPressAt(x.toFloat(), y.toFloat())
        return "✅ Long press kiya ($x, $y) pe"
    }

    private fun executeScreenshot(service: AutoAgentService): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            "✅ Screenshot le liya"
        } else {
            "❌ Screenshot API needs Android 9+"
        }
    }

    private fun executeCopy(service: AutoAgentService): String {
        val focusedNode = service.getRootNode()?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT)
            val args = android.os.Bundle()
            args.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            args.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, focusedNode.text?.length ?: 0)
            focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_COPY)
            return "✅ Text copy kiya"
        }
        return "❌ Koi text field focused nahi hai"
    }

    private fun executePaste(action: AgentAction, nodes: List<UiTreeExtractor.UiNode>, service: AutoAgentService): String {
        val nodeId = action.nodeId
        if (nodeId != null) {
            val node = UiTreeExtractor.findNodeById(nodes, nodeId)
                ?: UiTreeExtractor.findNodeByText(nodes, action.text ?: "")
            if (node != null) {
                node.nodeInfo.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                node.nodeInfo.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(200)
            }
        }

        val focusedNode = service.getRootNode()?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
            return "✅ Text paste kiya"
        }
        return "❌ Koi text field focused nahi hai"
    }

    private fun executeSelectAll(action: AgentAction, nodes: List<UiTreeExtractor.UiNode>, service: AutoAgentService): String {
        val focusedNode = service.getRootNode()?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val textLength = focusedNode.text?.length ?: 0
            val args = android.os.Bundle()
            args.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            args.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLength)
            focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            return "✅ Sab text select kiya"
        }
        return "❌ Koi text field focused nahi hai"
    }
}
