package com.voicecontrol.app.skills

/**
 * A reusable, learned workflow. Once saved, the assistant can execute this
 * without asking the AI to re-plan from scratch.
 *
 * IMPORTANT: PlanStep targets inside `steps` are semantic (text/desc/type),
 * NEVER raw accessibility node_ids. Node ids are only valid for the single
 * UI-tree read they came from and are meaningless by the time a later step
 * executes. See ExecutionPlan.kt / TargetDescriptor.
 */
data class Skill(
    val id: String,                      // stable slug, e.g. "send_whatsapp_message"
    val name: String,                    // human label, e.g. "Send WhatsApp Message"
    val description: String,
    val requiredApps: List<String>,
    val parameters: List<SkillParameter>,
    val steps: List<PlanStep>,
    val successConditions: List<VerificationRule>,
    val retryStrategy: RetryStrategy = RetryStrategy.DEFAULT,
    val version: Int = 1,
    val lastUsedAt: Long? = null,
    val usageCount: Int = 0,
    val successCount: Int = 0
) {
    val successRate: Float
        get() = if (usageCount == 0) 0f else successCount.toFloat() / usageCount

    /** Returns a copy with usage stats updated after one execution attempt. */
    fun recordUsage(succeeded: Boolean): Skill = copy(
        lastUsedAt = System.currentTimeMillis(),
        usageCount = usageCount + 1,
        successCount = successCount + if (succeeded) 1 else 0
    )
}

data class SkillParameter(
    val name: String,          // referenced in steps as "{name}"
    val type: ParamType,
    val required: Boolean = true
)

enum class ParamType { CONTACT_NAME, FREE_TEXT, TIME, NUMBER, APP_NAME }

data class RetryStrategy(
    val maxRetriesPerStep: Int,
    val backoffMs: Long,
    val maxEscalationsToAi: Int   // how many times this skill is allowed to call the AI mid-execution before it's declared failed
) {
    companion object {
        val DEFAULT = RetryStrategy(maxRetriesPerStep = 2, backoffMs = 600L, maxEscalationsToAi = 2)
    }
}
