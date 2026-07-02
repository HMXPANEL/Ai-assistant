package com.voicecontrol.app.skills

import com.voicecontrol.app.agent.ActionExecutor
import com.voicecontrol.app.agent.UiTreeExtractor
import com.voicecontrol.app.service.AutoAgentService
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Executes an ExecutionPlan without calling the AI after every step.
 * AI is only invoked (via Planner.replan) when a step cannot be locally
 * resolved or verified after all recovery attempts — the "unexpected
 * situation" escape hatch from the original design.
 */
class PlanExecutor(
    private val service: AutoAgentService,
    private val planner: Planner
) {

    var onStatusUpdate: ((String) -> Unit)? = null

    suspend fun execute(initialPlan: ExecutionPlan): ExecutionResult {
        var plan = initialPlan
        var stepIndex = 0
        var escalations = 0
        val maxEscalations = 2

        while (stepIndex < plan.steps.size) {
            val step = plan.steps[stepIndex]
            onStatusUpdate?.invoke("⚡ Step ${stepIndex + 1}/${plan.steps.size}: ${step.action}")

            if (step.action == "done") {
                return ExecutionResult.Success
            }

            val liveNodes = readLiveTree()
            val resolvedNode = step.target?.let { resolveTarget(it, liveNodes) }

            if (step.target != null && resolvedNode == null) {
                if (step.optional) {
                    stepIndex++
                    continue
                }
                val recovered = attemptLocalRecovery(step, liveNodes)
                if (recovered != null) {
                    val stabilized = performAndVerify(step, recovered, liveNodes)
                    if (stabilized) { stepIndex++; continue }
                }
                // local recovery exhausted -> escalate
                val patched = escalate(plan, stepIndex, "target not found: ${describeTarget(step.target)}", ++escalations, maxEscalations)
                    ?: return ExecutionResult.Failed("Element not found and AI could not recover", stepIndex)
                plan = patched
                stepIndex = 0 // patched plan is relative to current screen; restart index bookkeeping at 0 of the NEW plan
                continue
            }

            val verified = performAndVerify(step, resolvedNode, liveNodes)
            if (!verified) {
                val retried = retryStep(step, liveNodes)
                if (retried) { stepIndex++; continue }

                val patched = escalate(plan, stepIndex, "verification failed for step ${step.action}", ++escalations, maxEscalations)
                    ?: return ExecutionResult.Failed("Verification failed, AI could not recover", stepIndex)
                plan = patched
                stepIndex = 0
                continue
            }

            stepIndex++
        }

        return ExecutionResult.Success
    }

    // ---- step execution ----

    private fun performAndVerify(
        step: PlanStep,
        resolvedNode: UiTreeExtractor.UiNode?,
        nodesBefore: List<UiTreeExtractor.UiNode>
    ): Boolean {
        val action = ActionExecutor.AgentAction(
            action = step.action,
            nodeId = resolvedNode?.id,
            text = step.inputText,
            appName = if (step.action == "open_app") step.inputText else null,
            url = null,
            speech = null,
            status = "in_progress",
            x = step.target?.fallbackXY?.first,
            y = step.target?.fallbackXY?.second,
            reason = step.target?.byText
        )
        val result = ActionExecutor.execute(action, nodesBefore)
        if (result.startsWith("❌")) return false

        return verify(step.verification, nodesBefore)
    }

    private suspend fun retryStep(step: PlanStep, nodesBefore: List<UiTreeExtractor.UiNode>): Boolean {
        repeat(2) { attempt ->
            delay(600L * (attempt + 1))
            val fresh = readLiveTree()
            val resolved = step.target?.let { resolveTarget(it, fresh) }
            if (performAndVerify(step, resolved, fresh)) return true
        }
        return false
    }

    private suspend fun attemptLocalRecovery(step: PlanStep, nodes: List<UiTreeExtractor.UiNode>): UiTreeExtractor.UiNode? {
        // try scrolling down once, then re-resolve — covers "target is below the fold"
        ActionExecutor.execute(
            ActionExecutor.AgentAction("scroll_down", null, null, null, null, null, "in_progress", null, null, null),
            nodes
        )
        delay(400)
        val fresh = readLiveTree()
        return step.target?.let { resolveTarget(it, fresh) }
    }

    private suspend fun escalate(
        plan: ExecutionPlan,
        stepIndex: Int,
        reason: String,
        escalationCount: Int,
        maxEscalations: Int
    ): ExecutionPlan? {
        if (escalationCount > maxEscalations) return null
        onStatusUpdate?.invoke("🧠 Stuck — AI se poochh raha hoon...")
        val liveNodes = readLiveTree()
        val screenJson = UiTreeExtractor.toJson(liveNodes)
        val patchedSteps = planner.replan(plan.goal, stepIndex, reason, screenJson) ?: return null
        return plan.copy(steps = patchedSteps)
    }

    // ---- target resolution ----

    private fun resolveTarget(target: TargetDescriptor, nodes: List<UiTreeExtractor.UiNode>): UiTreeExtractor.UiNode? {
        target.byText?.let { text ->
            nodes.find { it.text.equals(text, ignoreCase = true) }?.let { return it }
            nodes.find { it.text.contains(text, ignoreCase = true) }?.let { return it }
        }
        target.byDesc?.let { desc ->
            nodes.find { it.contentDescription.equals(desc, ignoreCase = true) }?.let { return it }
            nodes.find { it.contentDescription.contains(desc, ignoreCase = true) }?.let { return it }
        }
        target.byType?.let { type ->
            nodes.filter { it.className.contains(type, ignoreCase = true) && it.clickable }
                .minByOrNull { it.bounds.top }
                ?.let { return it }
        }
        return null // caller falls back to fallbackXY via tap_xy in AgentAction if present
    }

    private fun describeTarget(target: TargetDescriptor?): String =
        target?.byText ?: target?.byDesc ?: target?.byType ?: "unknown"

    // ---- verification ----

    private suspend fun verify(rule: VerificationRule, nodesBefore: List<UiTreeExtractor.UiNode>): Boolean {
        val nodesAfter = waitForStableScreen()
        return when (rule.type) {
            VerifyType.TEXT_APPEARS -> rule.value != null && nodesAfter.any { it.text.contains(rule.value, true) || it.contentDescription.contains(rule.value, true) }
            VerifyType.TEXT_DISAPPEARS -> rule.value != null && nodesAfter.none { it.text.contains(rule.value, true) }
            VerifyType.SCREEN_CHANGED -> hash(nodesBefore) != hash(nodesAfter)
            VerifyType.NODE_GONE -> rule.value != null && nodesAfter.none { it.text.contains(rule.value, true) || it.contentDescription.contains(rule.value, true) }
            VerifyType.APP_FOREGROUND -> true // TODO: needs current package name from AutoAgentService — wire up if you add that accessor
        }
    }

    private fun hash(nodes: List<UiTreeExtractor.UiNode>): Int = nodes.joinToString { it.text + it.contentDescription }.hashCode()

    // ---- screen reading ----

    private fun readLiveTree(): List<UiTreeExtractor.UiNode> {
        val root = service.getRootNode() ?: return emptyList()
        return UiTreeExtractor.extractTree(root)
    }

    private suspend fun waitForStableScreen(): List<UiTreeExtractor.UiNode> {
        var previousHash: Int? = null
        var stable: List<UiTreeExtractor.UiNode> = emptyList()
        withTimeoutOrNull(3000L) {
            while (true) {
                stable = readLiveTree()
                val h = hash(stable)
                if (previousHash != null && h == previousHash) return@withTimeoutOrNull
                previousHash = h
                delay(250)
            }
        }
        return stable
    }
}
