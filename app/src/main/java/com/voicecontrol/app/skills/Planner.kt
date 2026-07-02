package com.voicecontrol.app.skills

import com.voicecontrol.app.data.GeminiClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everywhere this used to be "call the AI every step," it is now "call the AI
 * once to get a plan, occasionally again only to patch a stuck plan."
 *
 * Three entry points map to the three moments the AI is actually needed:
 *  - generatePlan(): no matching skill exists, need a full plan from scratch
 *  - bindSkillParams(): a skill matched, just need to extract parameter
 *    values from the command (e.g. contact name) — much cheaper prompt than full planning
 *  - replan(): PlanExecutor got stuck and exhausted local recovery
 */
class Planner(private val client: GeminiClient) {

    companion object {
        private const val PLAN_SYSTEM_PROMPT = """You are a planning module for an Android automation agent.
Given a goal, produce a COMPLETE step-by-step execution plan as JSON. Do not solve it interactively — you will not see the screen again until the whole plan is built.

Output ONLY valid JSON, no markdown, in this shape:
{
  "steps": [
    {
      "action": "open_app|click|type|scroll_down|scroll_up|swipe|back|tap_xy|wait|done",
      "target": {"byText": "...", "byDesc": "...", "byType": "B|E|IB|TV|IV"} or null,
      "inputText": "text to type, may include {param_name}",
      "verification": {"type": "TEXT_APPEARS|TEXT_DISAPPEARS|SCREEN_CHANGED|APP_FOREGROUND|NODE_GONE", "value": "..."},
      "optional": false
    }
  ]
}

RULES:
1. NEVER reference node ids or coordinates you cannot know in advance — describe targets by visible text, content description, or widget type only.
2. Assume standard app layouts (send button near text field, contact search at top, etc). Be conservative: prefer identifying elements by exact expected button/label text.
3. Every step needs a verification rule — how would you confirm THIS step worked, not just the whole task.
4. Mark a step optional:true only for things that may or may not appear (permission dialogs, popups).
5. Last step should be action "done" with a verification that confirms the overall goal."""

        private const val REPLAN_SYSTEM_PROMPT = """You are a planning module for an Android automation agent.
The executor got stuck running a pre-built plan. Given the goal, the step it was on, the failure reason, and the CURRENT live screen, output a JSON patch: replacement steps for the remaining plan, in the same schema as before (steps array, semantic targets, verification per step). You do NOT need to repeat completed steps."""
    }

    suspend fun generatePlan(goal: String): ExecutionPlan? {
        val prompt = "$PLAN_SYSTEM_PROMPT\n\nGOAL:$goal"
        val response = client.generateResponse(prompt, emptyList())
        val steps = parseSteps(response) ?: return null
        return ExecutionPlan(goal = goal, skillId = null, steps = steps)
    }

    /**
     * Cheap path: skill already defines the steps, we just need to pull parameter
     * values (contact name, message text, etc) out of the natural-language command.
     * Much smaller prompt/response than generatePlan — should be near-instant.
     */
    suspend fun bindSkillParams(skill: Skill, command: String): ExecutionPlan? {
        if (skill.parameters.isEmpty()) {
            return ExecutionPlan(goal = command, skillId = skill.id, steps = skill.steps)
        }
        val paramNames = skill.parameters.joinToString(", ") { it.name }
        val prompt = """Extract parameter values from the command below. Output ONLY JSON: {"param_name": "value", ...}
PARAMETERS NEEDED: $paramNames
COMMAND: $command"""
        val response = client.generateResponse(prompt, emptyList())
        val params = parseParams(response) ?: return null
        val boundSteps = skill.steps.map { step ->
            step.copy(inputText = step.inputText?.let { text -> interpolate(text, params) })
        }
        return ExecutionPlan(goal = command, skillId = skill.id, steps = boundSteps, boundParams = params)
    }

    suspend fun replan(
        goal: String,
        failedAtStep: Int,
        failureReason: String,
        currentScreenJson: String
    ): List<PlanStep>? {
        val prompt = """$REPLAN_SYSTEM_PROMPT

GOAL:$goal
STUCK_AT_STEP:$failedAtStep
REASON:$failureReason
CURRENT_SCREEN:$currentScreenJson"""
        val response = client.generateResponse(prompt, emptyList())
        return parseSteps(response)
    }

    private fun interpolate(template: String, params: Map<String, String>): String {
        var result = template
        params.forEach { (k, v) -> result = result.replace("{$k}", v) }
        return result
    }

    private fun parseParams(raw: String): Map<String, String>? = try {
        val json = extractJsonObject(raw) ?: return null
        val obj = JSONObject(json)
        obj.keys().asSequence().associateWith { obj.getString(it) }
    } catch (_: Exception) {
        null
    }

    private fun parseSteps(raw: String): List<PlanStep>? = try {
        val json = extractJsonObject(raw) ?: return null
        val obj = JSONObject(json)
        val arr = obj.getJSONArray("steps")
        (0 until arr.length()).map { i -> stepFromJson(i, arr.getJSONObject(i)) }
    } catch (e: Exception) {
        null
    }

    private fun stepFromJson(index: Int, obj: JSONObject): PlanStep {
        val targetObj = obj.optJSONObject("target")
        val target = targetObj?.let {
            TargetDescriptor(
                byText = it.optString("byText", null).takeUnless { s -> s.isNullOrEmpty() },
                byDesc = it.optString("byDesc", null).takeUnless { s -> s.isNullOrEmpty() },
                byType = it.optString("byType", null).takeUnless { s -> s.isNullOrEmpty() }
            )
        }
        val verObj = obj.getJSONObject("verification")
        return PlanStep(
            index = index,
            action = obj.getString("action"),
            target = target,
            inputText = obj.optString("inputText", null).takeUnless { it.isNullOrEmpty() },
            verification = VerificationRule(
                VerifyType.valueOf(verObj.getString("type")),
                verObj.optString("value", null).takeUnless { s -> s.isNullOrEmpty() }
            ),
            optional = obj.optBoolean("optional", false)
        )
    }

    private fun extractJsonObject(raw: String): String? {
        val stripped = raw.replace(Regex("```json\\s*"), "").replace(Regex("```\\s*"), "").trim()
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        return if (start != -1 && end != -1 && end > start) stripped.substring(start, end + 1) else null
    }
}
