package com.voicecontrol.app.agent

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object UiTreeExtractor {

    private const val TAG = "UiTreeExtractor"
    private const val MAX_DEPTH = 15
    private const val MAX_NODES = 150

    private val SKIP_TYPES = setOf(
        "View", "FrameLayout", "LinearLayout", "RelativeLayout",
        "ConstraintLayout", "CardView", "CoordinatorLayout"
    )

    data class UiNode(
        val id: Int,
        val text: String,
        val contentDescription: String,
        val className: String,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checked: Boolean?,
        val focused: Boolean,
        val bounds: Rect,
        val nodeInfo: AccessibilityNodeInfo
    )

    fun extractTree(root: AccessibilityNodeInfo?): List<UiNode> {
        if (root == null) return emptyList()

        val nodes = mutableListOf<UiNode>()
        var idCounter = 1

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || nodes.size >= MAX_NODES) return

            if (!node.isVisibleToUser) return

            val text = node.text?.toString()?.trim() ?: ""
            val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
            val className = node.className?.toString()?.substringAfterLast('.') ?: ""

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val hasSize = bounds.width() > 0 && bounds.height() > 0

            val isActionable = node.isClickable || node.isEditable || node.isScrollable ||
                    node.isCheckable || node.isFocusable || node.isLongClickable
            val hasContent = text.isNotEmpty() || contentDesc.isNotEmpty()
            val isImportant = className in listOf(
                "Button", "EditText", "ImageButton", "TextView", "ImageView",
                "CheckBox", "RadioButton", "Switch", "ToggleButton",
                "Spinner", "SeekBar", "SearchView", "TabView", "RecyclerView",
                "ListView", "ScrollView", "ViewPager"
            )

            if (hasSize && (hasContent || isActionable || isImportant)) {
                val isDecorativeContainer = className in SKIP_TYPES && !isActionable && !hasContent
                if (!isDecorativeContainer) {
                nodes.add(
                    UiNode(
                        id = idCounter++,
                        text = text.take(80),
                        contentDescription = contentDesc.take(80),
                        className = className,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        checked = if (node.isCheckable) node.isChecked else null,
                        focused = node.isFocused,
                        bounds = bounds,
                        nodeInfo = node
                    )
                )
                }
            }

            for (i in 0 until node.childCount) {
                try {
                    val child = node.getChild(i)
                    if (child != null) {
                        traverse(child, depth + 1)
                    }
                } catch (_: Exception) {
                }
            }
        }

        try {
            traverse(root, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting UI tree", e)
        }

        Log.d(TAG, "Extracted ${nodes.size} UI nodes")
        return nodes
    }

    fun toJson(nodes: List<UiNode>): String {
        val sb = StringBuilder("[")
        var first = true
        for (node in nodes) {
            if (!first) sb.append(",")
            first = false

            sb.append("{\"i\":").append(node.id)

            val text = node.text.take(50)
            if (text.isNotEmpty()) sb.append(",\"t\":\"").append(escapeJson(text)).append("\"")

            val desc = node.contentDescription.take(50)
            if (desc.isNotEmpty()) sb.append(",\"d\":\"").append(escapeJson(desc)).append("\"")

            val shortType = when (node.className) {
                "Button" -> "B"
                "EditText" -> "E"
                "ImageButton" -> "IB"
                "TextView" -> "TV"
                "ImageView" -> "IV"
                "CheckBox" -> "CB"
                "Switch" -> "SW"
                "RecyclerView" -> "RV"
                else -> node.className.take(10)
            }
            sb.append(",\"T\":\"").append(shortType).append("\"")
            sb.append(",\"x\":").append(node.bounds.centerX())
            sb.append(",\"y\":").append(node.bounds.centerY())

            if (node.clickable) sb.append(",\"c\":1")
            if (node.editable) sb.append(",\"e\":1")
            if (node.scrollable) sb.append(",\"s\":1")
            if (node.checked != null) sb.append(",\"ck\":").append(if (node.checked) 1 else 0)

            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "")
    }

    fun findNodeById(nodes: List<UiNode>, id: Int): UiNode? {
        return nodes.find { it.id == id }
    }

    fun findNodeByText(nodes: List<UiNode>, text: String): UiNode? {
        val exact = nodes.find {
            it.text.equals(text, ignoreCase = true) ||
            it.contentDescription.equals(text, ignoreCase = true)
        }
        if (exact != null) return exact

        return nodes.find {
            it.text.contains(text, ignoreCase = true) ||
            it.contentDescription.contains(text, ignoreCase = true)
        }
    }
}
