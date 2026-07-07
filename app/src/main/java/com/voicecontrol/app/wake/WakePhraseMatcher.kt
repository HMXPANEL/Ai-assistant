package com.voicecontrol.app.wake

import kotlin.math.min

/**
 * Fuzzy match for the wake phrase "Hey Max". Recognizer runs in hi-IN locale
 * (chosen to handle Hinglish/code-switched speech better than en-US), which
 * means English words can come back transcribed in Devanagari script — so
 * candidates include both Latin and Devanagari spellings, not just Latin
 * mishearings.
 */
object WakePhraseMatcher {

    private val CANDIDATES = listOf(
        "hey max", "hey mac", "hi max", "hemax", "e max", "hey mags", "a max",
        "hey max", "hay max", "he max", "eh max", "h max", "yeah max",
        "हे मैक्स", "हाय मैक्स", "हेय मैक्स", "हे मैक", "हेमैक्स", "अ मैक्स",
        "हे मिक्स", "है मैक्स", "है मैक", "हे मख्स", "हय मैक्स", "ए मैक्स"
    )

    private const val MAX_DISTANCE = 2

    fun containsWakePhrase(transcript: String): Boolean {
        val normalized = normalize(transcript)
        if (normalized.isEmpty()) return false

        if (CANDIDATES.any { levenshtein(normalized, normalize(it)) <= MAX_DISTANCE }) return true

        val words = normalized.split(" ").filter { it.isNotBlank() }
        for (windowSize in 1..3) {
            if (windowSize > words.size) break
            for (start in 0..(words.size - windowSize)) {
                val window = words.subList(start, start + windowSize).joinToString(" ")
                if (CANDIDATES.any { levenshtein(window, normalize(it)) <= MAX_DISTANCE }) return true
            }
        }
        return false
    }

    private fun normalize(s: String): String =
        s.lowercase().trim().replace(Regex("[^a-z0-9\u0900-\u097F ]"), "")

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }
}
