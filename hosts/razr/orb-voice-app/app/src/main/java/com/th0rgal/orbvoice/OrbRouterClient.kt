package com.th0rgal.orbvoice

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class OrbRouterConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val fastModel: String = DEFAULT_FAST_MODEL,
    val deepAudioModel: String = DEFAULT_DEEP_MODEL,
    val livePolishEnabled: Boolean = true,
    val autoTranslateTarget: String = "",
    val translateLangA: String = "English",
    val translateLangB: String = "French"
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://orbrouter.thomas.md/v1"
        const val DEFAULT_FAST_MODEL = "builtin/fast"
        const val DEFAULT_DEEP_MODEL = "builtin/smart"
        val SUPPORTED_LANGUAGES = listOf("English", "French", "Portuguese", "Spanish", "German", "Italian")

        fun load(context: Context): OrbRouterConfig {
            val prefs = context.getSharedPreferences("orb_voice_prefs", Context.MODE_PRIVATE)
            return OrbRouterConfig(
                baseUrl = prefs.getString("orb_router_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
                apiKey = prefs.getString("orb_router_key", "") ?: "",
                fastModel = prefs.getString("orb_router_fast_model", DEFAULT_FAST_MODEL) ?: DEFAULT_FAST_MODEL,
                deepAudioModel = prefs.getString("orb_router_deep_model", DEFAULT_DEEP_MODEL) ?: DEFAULT_DEEP_MODEL,
                livePolishEnabled = prefs.getBoolean("orb_router_live_polish", true),
                autoTranslateTarget = prefs.getString("orb_router_auto_translate", "") ?: "",
                translateLangA = prefs.getString("orb_translate_lang_a", "English") ?: "English",
                translateLangB = prefs.getString("orb_translate_lang_b", "French") ?: "French"
            )
        }

        fun save(context: Context, cfg: OrbRouterConfig) {
            context.getSharedPreferences("orb_voice_prefs", Context.MODE_PRIVATE).edit()
                .putString("orb_router_url", cfg.baseUrl.trim().trimEnd('/'))
                .putString("orb_router_key", cfg.apiKey.trim())
                .putString("orb_router_fast_model", cfg.fastModel.trim().ifEmpty { DEFAULT_FAST_MODEL })
                .putString("orb_router_deep_model", cfg.deepAudioModel.trim().ifEmpty { DEFAULT_DEEP_MODEL })
                .putBoolean("orb_router_live_polish", cfg.livePolishEnabled)
                .putString("orb_router_auto_translate", cfg.autoTranslateTarget.trim())
                .putString("orb_translate_lang_a", cfg.translateLangA.trim().ifEmpty { "English" })
                .putString("orb_translate_lang_b", cfg.translateLangB.trim().ifEmpty { "French" })
                .apply()
        }
    }
}

data class LivePolishResult(
    val cleaned: String,
    val translated: String = ""
)

data class PolishedNoteResult(
    val title: String,
    val summary: String,
    val segments: List<TranscriptSegment>
)

/**
 * OpenAI-compatible client for Orb Router (`https://orbrouter.thomas.md/v1`):
 *  - A) Rolling live autocorrect (`builtin/fast`) for committed turns while recording.
 *  - B) Post-recording full-note polish + auto-title & summary (`builtin/fast`).
 *  - C) On-demand Deep Audio Re-transcription (`builtin/smart` / quorum) sending the saved
 *       `.m4a`/`.mp3`/`.wav` directly to a multimodal model for studio-grade accuracy & diarization.
 */
object OrbRouterClient {

    /** Android's org.json.JSONObject.optString returns literal "null" when a key has JSON null! */
    private fun safeOptString(obj: JSONObject, key: String, fallback: String = ""): String {
        if (!obj.has(key) || obj.isNull(key)) return fallback
        val v = obj.optString(key, fallback).trim()
        return if (v.equals("null", ignoreCase = true)) fallback else v
    }

    /**
     * A2) Fast bidirectional live interpreter OR single-target auto-translate in one round-trip.
     * - When [bidirectionalLangA] & [bidirectionalLangB] are provided: detects which language was
     *   spoken and translates to the OTHER language, returning both cleaned original + translation.
     * - Otherwise when [cfg.autoTranslateTarget] is non-blank: cleans and translates to that target.
     */
    fun polishAndTranslateLive(
        cfg: OrbRouterConfig,
        rawText: String,
        recentContext: List<TranscriptSegment>,
        knownSpeakers: Collection<String>,
        bidirectionalLangA: String? = null,
        bidirectionalLangB: String? = null
    ): LivePolishResult? {
        if (!cfg.isConfigured || rawText.length < 2) return null
        val isBidir = !bidirectionalLangA.isNullOrBlank() && !bidirectionalLangB.isNullOrBlank()
        val targetLang = cfg.autoTranslateTarget.trim()
        if (!isBidir && targetLang.isEmpty()) {
            val cleaned = polishLiveSegment(cfg, rawText, recentContext, knownSpeakers) ?: return null
            return LivePolishResult(cleaned = cleaned, translated = "")
        }

        val ctxLines = recentContext.takeLast(3).joinToString("\n") {
            "${it.speakerName}: ${it.text}"
        }
        val namesHint = knownSpeakers.filter { !it.startsWith("Speaker ") }.joinToString(", ")
        val systemPrompt = buildString {
            append("You are an ultra-fast live speech autocorrector and interpreter. ")
            if (isBidir) {
                append("Speakers are conversing in $bidirectionalLangA and $bidirectionalLangB. ")
                append("1) Fix ASR typos/punctuation in the spoken utterance in its original language (\"cleaned\"). ")
                append("2) If spoken in $bidirectionalLangA, translate naturally into $bidirectionalLangB; if spoken in $bidirectionalLangB, translate naturally into $bidirectionalLangA (\"translated\"). ")
                append("If spoken in another language, translate into $bidirectionalLangA. ")
            } else {
                append("1) Fix ASR typos/punctuation in the spoken utterance in its original language (\"cleaned\"). ")
                append("2) Translate it naturally into $targetLang (\"translated\"). If it is already in $targetLang, set \"translated\" to \"\". ")
            }
            if (namesHint.isNotBlank()) append("Known speaker names: $namesHint. ")
            append("Return ONLY valid JSON: {\"cleaned\":\"...\",\"translated\":\"...\"}")
        }
        val userPrompt = buildString {
            if (ctxLines.isNotBlank()) {
                append("Recent context:\n")
                append(ctxLines)
                append("\n\n")
            }
            append("Latest spoken line:\n")
            append(rawText)
        }
        return try {
            val raw = chatCompletion(
                cfg = cfg,
                model = cfg.fastModel,
                systemPrompt = systemPrompt,
                userContent = userPrompt,
                maxTokens = 320,
                timeoutMs = 8000
            ) ?: return null
            val json = extractJsonObject(raw) ?: return null
            val cleaned = safeOptString(json, "cleaned", rawText).ifEmpty { rawText }
            val translated = safeOptString(json, "translated", "")
            LivePolishResult(cleaned = cleaned, translated = translated)
        } catch (e: Exception) {
            Log.d("OrbVoice", "Live translate skipped: ${e.message}")
            null
        }
    }

    /**
     * A) Fast low-latency autocorrect for a single newly committed live utterance.
     * Returns null on timeout/offline so the raw SODA text stays intact.
     */
    fun polishLiveSegment(
        cfg: OrbRouterConfig,
        rawText: String,
        recentContext: List<TranscriptSegment>,
        knownSpeakers: Collection<String>
    ): String? {
        if (!cfg.isConfigured || !cfg.livePolishEnabled || rawText.length < 4) return null
        val ctxLines = recentContext.takeLast(3).joinToString("\n") {
            "${it.speakerName}: ${it.text}"
        }
        val namesHint = knownSpeakers.filter { !it.startsWith("Speaker ") }.joinToString(", ")
        val systemPrompt = buildString {
            append("You are an ultra-fast speech-to-text autocorrector. ")
            append("Fix ASR phonetic errors, technical/aviation/software jargon, bilingual English/French words, and punctuation. ")
            append("Preserve the speaker's exact meaning and language. Do NOT summarize, do NOT add commentary, output ONLY the corrected text.")
            if (namesHint.isNotBlank()) append(" Known speaker names: $namesHint.")
        }
        val userPrompt = buildString {
            if (ctxLines.isNotBlank()) {
                append("Recent context:\n")
                append(ctxLines)
                append("\n\n")
            }
            append("Correct this latest spoken line:\n")
            append(rawText)
        }
        return try {
            val reply = chatCompletion(
                cfg = cfg,
                model = cfg.fastModel,
                systemPrompt = systemPrompt,
                userContent = userPrompt,
                maxTokens = 220,
                timeoutMs = 8000
            )?.trim()
            if (reply.isNullOrBlank() || reply.equals("null", ignoreCase = true) || reply.length > rawText.length * 3 + 40) null else reply
        } catch (e: Exception) {
            Log.d("OrbVoice", "Live LLM polish skipped: ${e.message}")
            null
        }
    }

    /**
     * B) Post-recording full-note cleanup: fixes global consistency across all turns and generates
     * a concise title + summary using `builtin/fast`.
     */
    fun polishFullNote(
        cfg: OrbRouterConfig,
        note: VoiceNote
    ): PolishedNoteResult? {
        if (!cfg.isConfigured || note.segments.isEmpty()) return null
        val indexedLines = note.segments.mapIndexed { idx, s ->
            "[$idx] ${s.speakerName} (${VoiceNoteStore.formatTimestamp(s.startMs)}): ${s.text}"
        }.joinToString("\n")

        val systemPrompt = """
            You clean up multi-speaker audio transcripts.
            Return ONLY valid JSON with this exact schema:
            {"title":"3-7 word concise title","summary":"1-2 sentence summary","texts":["corrected text for index 0","..."]}
            Rules:
            - Fix ASR mistakes, technical/aviation/software terms, French/English code-switching, and punctuation.
            - Keep the exact same number of elements in "texts" as input lines (${note.segments.size}).
            - Do not invent content that wasn't spoken.
        """.trimIndent()

        return try {
            val raw = chatCompletion(
                cfg = cfg,
                model = cfg.fastModel,
                systemPrompt = systemPrompt,
                userContent = indexedLines,
                maxTokens = 1600,
                timeoutMs = 15000
            ) ?: return null
            val json = extractJsonObject(raw) ?: return null
            val title = safeOptString(json, "title", note.title).ifEmpty { note.title }
            val summary = safeOptString(json, "summary", "")
            val arr = json.optJSONArray("texts")
            val updatedSegs = note.segments.mapIndexed { idx, seg ->
                val corrected = if (arr != null && idx < arr.length() && !arr.isNull(idx)) {
                    val s = arr.optString(idx, seg.text).trim()
                    if (s.equals("null", ignoreCase = true)) seg.text else s
                } else seg.text
                seg.copy(text = corrected.ifEmpty { seg.text })
            }
            PolishedNoteResult(title = title, summary = summary, segments = updatedSegs)
        } catch (e: Exception) {
            Log.w("OrbVoice", "Full-note polish failed: ${e.message}")
            null
        }
    }

    /**
     * C) On-demand Deep Audio Re-transcription via Orb Router (`builtin/smart` / configured quorum):
     * sends the recorded audio file directly to a multimodal model for studio-grade transcription
     * and speaker separation.
     */
    fun deepAudioRetranscribe(
        cfg: OrbRouterConfig,
        note: VoiceNote
    ): Result<PolishedNoteResult> {
        if (!cfg.isConfigured) {
            return Result.failure(IllegalStateException("Configure Orb Router API key in Settings first"))
        }
        val audioFile = File(note.audioPath)
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return Result.failure(IllegalStateException("Audio file missing: ${audioFile.name}"))
        }
        if (audioFile.length() > 18L * 1024L * 1024L) {
            return Result.failure(IllegalStateException("Audio file too large (${audioFile.length() / (1024 * 1024)} MB > 18 MB limit)"))
        }

        val format = when {
            audioFile.name.endsWith(".mp3", true) -> "mp3"
            audioFile.name.endsWith(".wav", true) -> "wav"
            else -> "m4a"
        }
        val audioB64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        val existingNames = note.segments.map { it.speakerName }.distinct()
            .filter { !it.startsWith("Speaker ") }
            .joinToString(", ")

        val prompt = buildString {
            append("Transcribe this audio recording with maximum accuracy and multi-speaker diarization.\n")
            if (existingNames.isNotBlank()) {
                append("Known speaker names in this conversation: $existingNames.\n")
            }
            append(
                """
                Return ONLY valid JSON with this exact schema:
                {
                  "title": "3-7 word concise title",
                  "summary": "2-3 sentence summary of key points",
                  "segments": [
                    {"speaker_index": 0, "speaker_name": "Speaker 1", "start_ms": 0, "end_ms": 3500, "text": "..."}
                  ]
                }
                Rules:
                - Preserve original language(s) including English/French switches.
                - Separate distinct voices accurately using consistent speaker_index (0, 1, 2...).
                - Fix domain terms, acronyms, and punctuation cleanly.
                """.trimIndent()
            )
        }

        return try {
            val contentParts = JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", prompt))
                put(
                    JSONObject()
                        .put("type", "input_audio")
                        .put(
                            "input_audio",
                            JSONObject()
                                .put("data", audioB64)
                                .put("format", format)
                        )
                )
            }
            val raw = chatCompletionRaw(
                cfg = cfg,
                model = cfg.deepAudioModel,
                messages = JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content", contentParts))
                },
                maxTokens = 4096,
                timeoutMs = 90000
            ) ?: return Result.failure(IllegalStateException("Empty response from ${cfg.deepAudioModel}"))

            val json = extractJsonObject(raw)
                ?: return Result.failure(IllegalStateException("Model did not return valid JSON"))

            val title = json.optString("title", note.title).trim().ifEmpty { note.title }
            val summary = json.optString("summary", "").trim()
            val arr = json.optJSONArray("segments")
            val parsed = mutableListOf<TranscriptSegment>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val txt = o.optString("text", "").trim()
                    if (txt.isEmpty()) continue
                    val spkIdx = o.optInt("speaker_index", 0).coerceAtLeast(0)
                    val spkName = o.optString("speaker_name", "Speaker ${spkIdx + 1}").trim()
                        .ifEmpty { "Speaker ${spkIdx + 1}" }
                    parsed.add(
                        TranscriptSegment(
                            speakerIndex = spkIdx,
                            speakerName = spkName,
                            startMs = o.optLong("start_ms", i * 3000L),
                            endMs = o.optLong("end_ms", (i + 1) * 3000L),
                            text = txt
                        )
                    )
                }
            }
            if (parsed.isEmpty()) {
                return Result.failure(IllegalStateException("No transcript segments returned"))
            }
            Result.success(PolishedNoteResult(title = title, summary = summary, segments = parsed))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun chatCompletion(
        cfg: OrbRouterConfig,
        model: String,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int,
        timeoutMs: Int
    ): String? {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", userContent))
        }
        return chatCompletionRaw(cfg, model, messages, maxTokens, timeoutMs)
    }

    private fun chatCompletionRaw(
        cfg: OrbRouterConfig,
        model: String,
        messages: JSONArray,
        maxTokens: Int,
        timeoutMs: Int
    ): String? {
        val endpoint = "${cfg.baseUrl.trimEnd('/')}/chat/completions"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5000
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "orb-voice-android/2.0")
        }
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            put("max_tokens", maxTokens)
            put("messages", messages)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val respText = stream?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: ${respText.take(180)}")
        }
        val root = JSONObject(respText)
        val choices = root.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val msg = choices.getJSONObject(0).optJSONObject("message") ?: return null
        return safeOptString(msg, "content", "").takeIf { it.isNotBlank() }
    }

    private fun extractJsonObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(trimmed.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }
}
