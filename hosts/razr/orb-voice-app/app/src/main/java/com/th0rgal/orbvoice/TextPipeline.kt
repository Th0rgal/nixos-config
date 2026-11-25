package com.th0rgal.orbvoice

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/**
 * Shared text post-processing pipeline mirroring Murmure's `TextPipeline.swift` / `voiced/pipeline.py`:
 *  - Loads optional `vocabulary.txt` (one term per line, `#` comments) and `corrections.json`
 *    (`{"wrong": "right"}`) from `Documents/OrbVoice/` (seeded with Thomas's core dev vocabulary).
 *  - Applies exact case-insensitive corrections and bounded Levenshtein fuzzy snapping.
 */
object TextPipeline {
    private val defaultVocabulary = listOf(
        "Omarchnix", "SideSystem", "Hyprland", "Quickshell", "Murmure", "Scribe",
        "NixOS", "Wayland", "Ghostty", "Tailscale", "Bitwarden", "OpenCode",
        "Parakeet", "Qwen3", "Hexagon", "Snapdragon", "Adreno", "Blackwell"
    )

    private var lastLoadMs = 0L
    private var cachedVocab: List<String> = defaultVocabulary
    private var cachedCorrections: Map<String, String> = emptyMap()

    private fun refreshIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastLoadMs < 10_000L) return
        lastLoadMs = now
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "OrbVoice"
        )
        val vocabFile = File(dir, "vocabulary.txt")
        if (vocabFile.canRead()) {
            val parsed = vocabFile.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
            if (parsed.isNotEmpty()) {
                cachedVocab = (parsed + defaultVocabulary).distinctBy { it.lowercase() }
            }
        }
        val corrFile = File(dir, "corrections.json")
        if (corrFile.canRead()) {
            runCatching {
                val obj = JSONObject(corrFile.readText())
                val map = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    if (k.isNotBlank() && v.isNotBlank()) map[k] = v
                }
                cachedCorrections = map
            }
        }
    }

    fun process(@Suppress("UNUSED_PARAMETER") context: Context?, raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        refreshIfNeeded()

        var out = trimmed
        // 1. Exact phrase/word corrections from corrections.json
        for ((wrong, right) in cachedCorrections) {
            out = out.replace(Regex("(?i)\\b${Regex.escape(wrong)}\\b"), Regex.escapeReplacement(right))
        }

        // 2. Bounded Levenshtein fuzzy snapping against vocabulary.txt (single-word terms >= 5 chars)
        val singleTerms = cachedVocab.filter { !it.contains(' ') && it.length >= 5 }
        if (singleTerms.isNotEmpty()) {
            val tokens = out.split(' ').toMutableList()
            for (i in tokens.indices) {
                val tok = tokens[i]
                val lead = tok.takeWhile { it in "\"'([{" }
                val trail = tok.takeLastWhile { it in ".,!?;:\"'()[]{}" }
                val core = tok.removePrefix(lead).removeSuffix(trail)
                if (core.length < 5) continue
                val coreLower = core.lowercase()
                var bestTerm: String? = null
                var bestDist = Int.MAX_VALUE
                for (term in singleTerms) {
                    val maxDist = if (term.length >= 8) 2 else 1
                    val d = levenshtein(coreLower, term.lowercase())
                    if (d in 1..maxDist && d < bestDist) {
                        bestDist = d
                        bestTerm = term
                    } else if (d == 0 && core != term) {
                        bestDist = 0
                        bestTerm = term
                        break
                    }
                }
                if (bestTerm != null) {
                    tokens[i] = "$lead$bestTerm$trail"
                }
            }
            out = tokens.joinToString(" ")
        }
        return out
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            val ca = a[i]
            for (j in b.indices) {
                val cost = if (ca == b[j]) 0 else 1
                curr[j + 1] = min(min(prev[j + 1] + 1, curr[j] + 1), prev[j] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }
}
