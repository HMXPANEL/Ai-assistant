package com.voicecontrol.app.skills

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local storage + lookup for learned Skills.
 *
 * Deliberately plain JSON-file storage (same pattern as ConversationMemory.kt),
 * not Room. At the scale of a personal assistant's skill library (tens, maybe
 * low hundreds of skills) a relational DB buys nothing but adds a KSP/build
 * dependency you can't easily debug without local Android SDK. Revisit if the
 * library grows past ~200 skills or needs fuzzy/full-text search.
 */
class SkillLibrary(context: Context) {

    private val file = File(context.filesDir, "skills_library.json")

    @Synchronized
    fun getAll(): List<Skill> = loadArray().let { arr ->
        (0 until arr.length()).map { toSkill(arr.getJSONObject(it)) }
    }

    @Synchronized
    fun getById(id: String): Skill? = getAll().find { it.id == id }

    /**
     * Cheap, local, zero-AI-call matching: keyword overlap between the command
     * and each skill's name/description. Good enough for a first pass — most
     * commands ("send whatsapp to mom", "good morning") map obviously.
     *
     * NOTE: this is intentionally naive. If match quality turns out too loose
     * (wrong skill picked) the next step up is one cheap classification-only
     * AI call ("given this command and this list of skill names, which one
     * fits, or none?") rather than jumping straight to embeddings — that's a
     * decision to make once you have real false-match data, not before.
     */
    fun findBestMatch(command: String, minScore: Double = 0.35): Skill? {
        val commandWords = tokenize(command)
        if (commandWords.isEmpty()) return null

        return getAll()
            .map { skill -> skill to overlapScore(commandWords, tokenize("${skill.name} ${skill.description}")) }
            .filter { (_, score) -> score >= minScore }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    @Synchronized
    fun save(skill: Skill) {
        val arr = loadArray()
        var replaced = false
        val updated = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("id") == skill.id) {
                updated.put(toJson(skill))
                replaced = true
            } else {
                updated.put(obj)
            }
        }
        if (!replaced) updated.put(toJson(skill))
        file.writeText(updated.toString())
    }

    @Synchronized
    fun recordUsage(skillId: String, succeeded: Boolean) {
        val skill = getById(skillId) ?: return
        save(skill.recordUsage(succeeded))
    }

    fun delete(skillId: String) {
        val arr = loadArray()
        val updated = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("id") != skillId) updated.put(obj)
        }
        file.writeText(updated.toString())
    }

    // ---- matching helpers ----

    private fun tokenize(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()

    private fun overlapScore(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersect = a.intersect(b).size
        return intersect.toDouble() / a.size
    }

    // ---- serialization ----

    private fun loadArray(): JSONArray = try {
        if (file.exists()) JSONArray(file.readText()) else JSONArray()
    } catch (_: Exception) {
        JSONArray()
    }

    private fun toJson(skill: Skill): JSONObject = JSONObject().apply {
        put("id", skill.id)
        put("name", skill.name)
        put("description", skill.description)
        put("requiredApps", JSONArray(skill.requiredApps))
        put("parameters", JSONArray(skill.parameters.map { p ->
            JSONObject().apply {
                put("name", p.name); put("type", p.type.name); put("required", p.required)
            }
        }))
        put("steps", JSONArray(skill.steps.map { stepToJson(it) }))
        put("successConditions", JSONArray(skill.successConditions.map { ruleToJson(it) }))
        put("retryStrategy", JSONObject().apply {
            put("maxRetriesPerStep", skill.retryStrategy.maxRetriesPerStep)
            put("backoffMs", skill.retryStrategy.backoffMs)
            put("maxEscalationsToAi", skill.retryStrategy.maxEscalationsToAi)
        })
        put("version", skill.version)
        put("lastUsedAt", skill.lastUsedAt ?: JSONObject.NULL)
        put("usageCount", skill.usageCount)
        put("successCount", skill.successCount)
    }

    private fun toSkill(obj: JSONObject): Skill {
        val paramsArr = obj.getJSONArray("parameters")
        val params = (0 until paramsArr.length()).map { i ->
            val p = paramsArr.getJSONObject(i)
            SkillParameter(p.getString("name"), ParamType.valueOf(p.getString("type")), p.optBoolean("required", true))
        }
        val stepsArr = obj.getJSONArray("steps")
        val steps = (0 until stepsArr.length()).map { stepFromJson(stepsArr.getJSONObject(it)) }
        val condArr = obj.getJSONArray("successConditions")
        val conditions = (0 until condArr.length()).map { ruleFromJson(condArr.getJSONObject(it)) }
        val retry = obj.optJSONObject("retryStrategy")?.let {
            RetryStrategy(
                it.optInt("maxRetriesPerStep", 2),
                it.optLong("backoffMs", 600L),
                it.optInt("maxEscalationsToAi", 2)
            )
        } ?: RetryStrategy.DEFAULT

        return Skill(
            id = obj.getString("id"),
            name = obj.getString("name"),
            description = obj.getString("description"),
            requiredApps = obj.getJSONArray("requiredApps").let { a -> (0 until a.length()).map { a.getString(it) } },
            parameters = params,
            steps = steps,
            successConditions = conditions,
            retryStrategy = retry,
            version = obj.optInt("version", 1),
            lastUsedAt = if (obj.isNull("lastUsedAt")) null else obj.optLong("lastUsedAt"),
            usageCount = obj.optInt("usageCount", 0),
            successCount = obj.optInt("successCount", 0)
        )
    }

    private fun stepToJson(step: PlanStep): JSONObject = JSONObject().apply {
        put("index", step.index)
        put("action", step.action)
        put("target", step.target?.let { t ->
            JSONObject().apply {
                put("byText", t.byText ?: JSONObject.NULL)
                put("byDesc", t.byDesc ?: JSONObject.NULL)
                put("byType", t.byType ?: JSONObject.NULL)
                put("fallbackXY", t.fallbackXY?.let { JSONArray(listOf(it.first, it.second)) } ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)
        put("inputText", step.inputText ?: JSONObject.NULL)
        put("verification", ruleToJson(step.verification))
        put("optional", step.optional)
    }

    private fun stepFromJson(obj: JSONObject): PlanStep {
        val targetObj = obj.optJSONObject("target")
        val target = targetObj?.let {
            val xy = it.optJSONArray("fallbackXY")
            TargetDescriptor(
                byText = it.optString("byText", null).takeUnless { s -> s.isNullOrEmpty() },
                byDesc = it.optString("byDesc", null).takeUnless { s -> s.isNullOrEmpty() },
                byType = it.optString("byType", null).takeUnless { s -> s.isNullOrEmpty() },
                fallbackXY = xy?.let { arr -> Pair(arr.getInt(0), arr.getInt(1)) }
            )
        }
        return PlanStep(
            index = obj.getInt("index"),
            action = obj.getString("action"),
            target = target,
            inputText = obj.optString("inputText", null).takeUnless { it.isNullOrEmpty() },
            verification = ruleFromJson(obj.getJSONObject("verification")),
            optional = obj.optBoolean("optional", false)
        )
    }

    private fun ruleToJson(rule: VerificationRule): JSONObject = JSONObject().apply {
        put("type", rule.type.name)
        put("value", rule.value ?: JSONObject.NULL)
    }

    private fun ruleFromJson(obj: JSONObject): VerificationRule =
        VerificationRule(VerifyType.valueOf(obj.getString("type")), obj.optString("value", null).takeUnless { it.isNullOrEmpty() })
}
