package com.th0rgal.orbvoice

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class VoiceprintProfile(
    val name: String,
    val embedding: FloatArray,
    val sampleWeight: Float = 20f,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Persistent cross-session & cross-device 256-D speaker voiceprint registry (`voiceprints.json`).
 *
 * Whenever you label a speaker (e.g. "Thomas", "Nini", "Benjamin"), their learned WeSpeaker
 * ResNet293-LM 256-D centroid (~1 KB) is stored locally and optionally synced with `dgx-spark`
 * so future recordings recognize them by name automatically from the very first sentence.
 */
object VoiceprintRegistry {

    private fun registryFile(context: Context): File =
        File(VoiceNoteStore.notesDir(context), "voiceprints.json")

    fun loadAll(context: Context): List<VoiceprintProfile> {
        val f = registryFile(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<VoiceprintProfile>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name", "").trim()
                val vecArr = o.optJSONArray("embedding") ?: continue
                if (name.isEmpty() || vecArr.length() < 16) continue
                val vec = FloatArray(vecArr.length()) { vecArr.getDouble(it).toFloat() }
                out.add(
                    VoiceprintProfile(
                        name = name,
                        embedding = vec,
                        sampleWeight = o.optDouble("sample_weight", 20.0).toFloat(),
                        updatedAtMs = o.optLong("updated_at_ms", System.currentTimeMillis())
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w("OrbVoice", "VoiceprintRegistry load failed: ${e.message}")
            emptyList()
        }
    }

    fun upsert(context: Context, name: String, embedding: FloatArray, sparkVoiceprintUrl: String? = null) {
        val clean = name.trim()
        if (clean.isEmpty() || clean.startsWith("Speaker ") || embedding.size < 16) return
        val existing = loadAll(context).toMutableList()
        val idx = existing.indexOfFirst { it.name.equals(clean, ignoreCase = true) }
        if (idx >= 0) {
            val prev = existing[idx]
            if (prev.embedding.size == embedding.size) {
                val merged = FloatArray(embedding.size) { k -> prev.embedding[k] * 0.65f + embedding[k] * 0.35f }
                var norm = 0f
                for (x in merged) norm += x * x
                val d = kotlin.math.sqrt(norm).coerceAtLeast(1e-6f)
                for (k in merged.indices) merged[k] /= d
                existing[idx] = prev.copy(
                    name = clean,
                    embedding = merged,
                    sampleWeight = (prev.sampleWeight + 15f).coerceAtMost(300f),
                    updatedAtMs = System.currentTimeMillis()
                )
            } else {
                existing[idx] = VoiceprintProfile(clean, embedding.copyOf())
            }
        } else {
            existing.add(VoiceprintProfile(clean, embedding.copyOf()))
        }
        saveAll(context, existing)

        if (!sparkVoiceprintUrl.isNullOrBlank()) {
            pushToSparkBestEffort(sparkVoiceprintUrl, existing)
        }
    }

    fun rename(context: Context, oldName: String, newName: String) {
        val cleanNew = newName.trim()
        if (cleanNew.isEmpty()) return
        val existing = loadAll(context).toMutableList()
        val idx = existing.indexOfFirst { it.name.equals(oldName, ignoreCase = true) }
        if (idx >= 0) {
            existing[idx] = existing[idx].copy(name = cleanNew, updatedAtMs = System.currentTimeMillis())
            saveAll(context, existing)
        }
    }

    fun delete(context: Context, name: String) {
        val updated = loadAll(context).filterNot { it.name.equals(name, ignoreCase = true) }
        saveAll(context, updated)
    }

    private fun saveAll(context: Context, profiles: List<VoiceprintProfile>) {
        try {
            val arr = JSONArray()
            for (p in profiles) {
                val vec = JSONArray()
                for (v in p.embedding) vec.put(v.toDouble())
                arr.put(
                    JSONObject().apply {
                        put("name", p.name)
                        put("sample_weight", p.sampleWeight.toDouble())
                        put("updated_at_ms", p.updatedAtMs)
                        put("embedding", vec)
                    }
                )
            }
            registryFile(context).writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.w("OrbVoice", "VoiceprintRegistry save failed: ${e.message}")
        }
    }

    private fun pushToSparkBestEffort(url: String, profiles: List<VoiceprintProfile>) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 1200
                readTimeout = 2000
                setRequestProperty("Content-Type", "application/json")
            }
            val arr = JSONArray()
            for (p in profiles) {
                val vec = JSONArray()
                for (v in p.embedding) vec.put(v.toDouble())
                arr.put(JSONObject().put("name", p.name).put("embedding", vec))
            }
            conn.outputStream.use { it.write(JSONObject().put("voiceprints", arr).toString().toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {
            // Offline / away from home mesh — local voiceprints.json still persists
        }
    }
}
