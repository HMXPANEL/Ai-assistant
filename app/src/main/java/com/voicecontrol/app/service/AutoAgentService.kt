package com.voicecontrol.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoAgentService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoAgentService"

        @Volatile
        var instance: AutoAgentService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    private var screenWidth = 1080
    private var screenHeight = 2400

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        try {
            val dm = resources.displayMetrics
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
        } catch (_: Exception) {}

        Log.d(TAG, "✅ AutoAgentService connected (${screenWidth}x${screenHeight})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        Log.w(TAG, "AutoAgentService interrupted")
    }

    override fun onDestroy() {
        instance = null
        Log.d(TAG, "AutoAgentService destroyed")
        super.onDestroy()
    }

    fun getRootNode(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get root node", e)
            null
        }
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isClickable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) {
                    Log.d(TAG, "Click: direct ACTION_CLICK succeeded")
                    return true
                }
            }

            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isClickable) {
                    val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (result) {
                        Log.d(TAG, "Click: parent click succeeded (depth=$depth)")
                        return true
                    }
                }
                parent = parent.parent
                depth++
            }

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                val cx = bounds.centerX().toFloat()
                val cy = bounds.centerY().toFloat()
                Log.d(TAG, "Click: gesture tap at ($cx, $cy)")
                tapAt(cx, cy)
                return true
            }

            Log.w(TAG, "Click failed: no clickable node or bounds")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Click failed", e)
            false
        }
    }

    fun clickAtBounds(bounds: Rect): Boolean {
        return try {
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            tapAt(cx, cy)
            true
        } catch (e: Exception) {
            Log.e(TAG, "clickAtBounds failed", e)
            false
        }
    }

    fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(150)

            val clearArgs = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            Thread.sleep(100)

            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.e(TAG, "Set text failed", e)
            false
        }
    }

    fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isScrollable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                if (result) return true
            }

            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isScrollable) {
                    val result = parent.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    if (result) return true
                }
                parent = parent.parent
                depth++
            }

            val cx = screenWidth / 2f
            performGlobalSwipe(cx, screenHeight * 0.7f, cx, screenHeight * 0.3f, 400)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Scroll forward failed", e)
            false
        }
    }

    fun scrollBackward(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isScrollable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                if (result) return true
            }

            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isScrollable) {
                    val result = parent.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    if (result) return true
                }
                parent = parent.parent
                depth++
            }

            val cx = screenWidth / 2f
            performGlobalSwipe(cx, screenHeight * 0.3f, cx, screenHeight * 0.7f, 400)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Scroll backward failed", e)
            false
        }
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun performGlobalSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 350
    ) {
        val path = Path().apply {
            moveTo(startX.coerceIn(0f, screenWidth.toFloat()), startY.coerceIn(0f, screenHeight.toFloat()))
            lineTo(endX.coerceIn(0f, screenWidth.toFloat()), endY.coerceIn(0f, screenHeight.toFloat()))
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe gesture completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe gesture cancelled")
            }
        }, null)
    }

    fun tapAt(x: Float, y: Float) {
        val safeX = x.coerceIn(1f, screenWidth.toFloat() - 1)
        val safeY = y.coerceIn(1f, screenHeight.toFloat() - 1)
        val path = Path().apply { moveTo(safeX, safeY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap completed at ($safeX, $safeY)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap cancelled at ($safeX, $safeY)")
            }
        }, null)
    }

    fun longPressAt(x: Float, y: Float) {
        val safeX = x.coerceIn(1f, screenWidth.toFloat() - 1)
        val safeY = y.coerceIn(1f, screenHeight.toFloat() - 1)
        val path = Path().apply { moveTo(safeX, safeY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 800))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
