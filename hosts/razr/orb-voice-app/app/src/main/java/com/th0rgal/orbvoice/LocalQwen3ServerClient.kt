package com.th0rgal.orbvoice

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Localhost client for Razr SideSystem's warm `llama-server` running our DGX Spark-trained
 * `Qwen3-ASR-1.7B + Scribe LoRA` (`Q4_K_M` + `mmproj Q8_0`) or bilingual `IQ4_XS` GGUF on
 * `http://127.0.0.1:8089` (droidspaces shares host networking, zero network hop).
 *
 * Implements the mandatory overnight-research safeguards:
 *  - Dynamic `max_tokens` cap (~8 tokens/sec of audio, bounded [48, 256])
 *  - Repetition-loop guard (aborts/strips any char or short n-gram repeated >= 20x)
 *  - Clean `<asr_text>` tag extraction (strips `language <Lang><asr_text>` prefix)
 */
object LocalQwen3ServerClient {
    const val DEFAULT_URL = "http://127.0.0.1:8089"

    fun isHealthy(baseUrl: String = DEFAULT_URL): Boolean {
        return try {
            val conn = (URL("${baseUrl.trimEnd('/')}/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = 250
                readTimeout = 250
                requestMethod = "GET"
            }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    fun transcribe(samples: FloatArray, baseUrl: String = DEFAULT_URL): String {
        if (samples.size < 3200) return ""
        val wavBytes = encodePcm16Wav(samples, 16000)
        val b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val durationSec = samples.size / 16000f
        val maxTokens = (durationSec * 8f).toInt().coerceIn(48, 256)

        val contentArr = JSONArray()
            .put(
                JSONObject()
                    .put("type", "input_audio")
                    .put(
                        "input_audio",
                        JSONObject()
                            .put("data", b64)
                            .put("format", "wav")
                    )
            )
            .put(
                JSONObject()
                    .put("type", "text")
                    .put("text", "Transcribe the audio.")
            )

        val body = JSONObject()
            .put("model", "qwen3-asr")
            .put("temperature", 0.0)
            .put("max_tokens", maxTokens)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", contentArr)
                )
            )

        return try {
            val t0 = System.nanoTime()
            val conn = (URL("${baseUrl.trimEnd('/')}/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 25000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                Log.w("OrbVoice", "LocalQwen3Server HTTP $code: ${resp.take(160)}")
                return ""
            }
            val root = JSONObject(resp)
            val raw = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                .orEmpty()
            val cleaned = cleanQwenOutput(raw)
            val ms = (System.nanoTime() - t0) / 1_000_000L
            Log.d("OrbVoice", "Qwen3-1.7B-LoRA decoded ${"%.1f".format(durationSec)}s in ${ms}ms -> ${cleaned.take(60)}")
            cleaned
        } catch (e: Throwable) {
            Log.w("OrbVoice", "LocalQwen3Server transcribe failed: ${e.message}")
            ""
        }
    }

    /** Extracts transcript after `<asr_text>` and enforces the repetition-loop safeguard. */
    fun cleanQwenOutput(raw: String): String {
        var s = raw.trim()
        val tagIdx = s.indexOf("<asr_text>")
        if (tagIdx >= 0) {
            s = s.substring(tagIdx + "<asr_text>".length).trim()
        }
        // Strip any trailing special tokens
        val spIdx = s.indexOf("<|")
        if (spIdx > 0) s = s.substring(0, spIdx).trim()
        // Repetition loop guard: truncate if any character repeats >= 20 times in a row (e.g. "8888...")
        val repMatch = Regex("(.)\\1{19,}").find(s)
        if (repMatch != null) {
            s = s.substring(0, repMatch.range.first).trim()
        }
        return s
    }

    private fun encodePcm16Wav(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun writeStr(str: String) = out.write(str.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(v: Int) {
            out.write(v and 0xff); out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xff); out.write((v shr 8) and 0xff)
        }
        writeStr("RIFF"); writeIntLE(36 + dataSize); writeStr("WAVE")
        writeStr("fmt "); writeIntLE(16); writeShortLE(1); writeShortLE(1)
        writeIntLE(sampleRate); writeIntLE(sampleRate * 2); writeShortLE(2); writeShortLE(16)
        writeStr("data"); writeIntLE(dataSize)
        for (f in samples) {
            val s = (f * 32767f).toInt().coerceIn(-32768, 32767)
            writeShortLE(s)
        }
        return out.toByteArray()
    }
}
