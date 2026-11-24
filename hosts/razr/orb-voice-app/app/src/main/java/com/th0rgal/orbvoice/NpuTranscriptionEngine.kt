package com.th0rgal.orbvoice

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class NpuHardwareStatus(
    val cdspDevicePresent: Boolean,
    val qnnHtpV79StubPresent: Boolean,
    val qnnHtpLibPresent: Boolean
) {
    val isReady: Boolean get() = cdspDevicePresent && (qnnHtpV79StubPresent || qnnHtpLibPresent)
    val badgeLabel: String get() = when {
        qnnHtpV79StubPresent -> "NPU V79"
        isReady -> "NPU QNN"
        else -> "CPU"
    }
}

/**
 * Multi-tier transcription engine tailored for Motorola Razr 60 Ultra + DGX Spark:
 *
 * 1. Hexagon V79 NPU hardware detection (`/dev/fastrpc-cdsp` + `libQnnHtpV79Stub.so`)
 * 2. DGX Spark `cohere-transcribe` NVFP4 (`http://10.88.50.30:8002/v1/audio/transcriptions`)
 *    per separated speaker turn when reachable over home/paloma mesh
 * 3. Offline live-caption fallback aligned to diarized speaker turns
 * 4. One-tap Raw-First sync to `spark-memory` (`http://10.88.50.30:8890/api/voice/ingest`)
 */
object NpuTranscriptionEngine {
    const val DEFAULT_SPARK_ASR_URL = "http://10.88.50.30:8002/v1/audio/transcriptions"
    const val DEFAULT_SPARK_MEMORY_URL = "http://10.88.50.30:8890/api/voice/ingest"

    fun probeHardware(): NpuHardwareStatus {
        val cdsp = File("/dev/fastrpc-cdsp").exists()
        val v79 = File("/vendor/lib64/libQnnHtpV79Stub.so").exists()
        val htp = File("/vendor/lib64/libQnnHtp.so").exists()
        return NpuHardwareStatus(cdsp, v79, htp)
    }

    fun probeSparkReachable(sparkAsrUrl: String = DEFAULT_SPARK_ASR_URL): Boolean =
        isEndpointReachable(sparkAsrUrl)

    fun transcribeSingleTurn(
        pcm: ShortArray,
        startMs: Long,
        sparkAsrUrl: String = DEFAULT_SPARK_ASR_URL
    ): String? {
        if (pcm.isEmpty()) return null
        val wavBytes = sliceToWavBytes(pcm, 0, pcm.size)
        return callOpenAiCompatibleAsr(sparkAsrUrl, wavBytes, "live_${startMs}.wav")?.trim()
    }

    fun transcribeTurns(
        context: Context,
        pcm: ShortArray,
        turns: List<DiarizedTurn>,
        diarizerEngine: String,
        sparkAsrUrl: String = DEFAULT_SPARK_ASR_URL
    ): Pair<List<TranscriptSegment>, String> {
        if (turns.isEmpty()) return emptyList<TranscriptSegment>() to diarizerEngine

        val sparkReachable = isEndpointReachable(sparkAsrUrl)
        val results = ArrayList<TranscriptSegment>(turns.size)
        var usedSpark = false

        for (turn in turns) {
            val wavBytes = sliceToWavBytes(pcm, turn.startSample, turn.endSample)
            val text = if (sparkReachable) {
                val t = callOpenAiCompatibleAsr(sparkAsrUrl, wavBytes, "turn_${turn.startMs}.wav")
                if (!t.isNullOrBlank()) {
                    usedSpark = true
                    t.trim()
                } else {
                    "[Voice segment ${VoiceNoteStore.formatTimestamp(turn.startMs)}–${VoiceNoteStore.formatTimestamp(turn.endMs)}]"
                }
            } else {
                "[Offline voice segment ${VoiceNoteStore.formatTimestamp(turn.startMs)}–${VoiceNoteStore.formatTimestamp(turn.endMs)} — tap Sync to transcribe on DGX Spark]"
            }

            results.add(
                TranscriptSegment(
                    speakerIndex = turn.speakerIndex,
                    speakerName = "Speaker ${turn.speakerIndex + 1}",
                    startMs = turn.startMs,
                    endMs = turn.endMs,
                    text = text
                )
            )
        }

        val engineLabel = if (usedSpark) {
            "$diarizerEngine + Spark cohere-transcribe"
        } else {
            "$diarizerEngine (Offline)"
        }
        return results to engineLabel
    }

    fun syncToSparkMemory(
        note: VoiceNote,
        sparkMemoryUrl: String = DEFAULT_SPARK_MEMORY_URL
    ): Result<String> {
        return try {
            val audioFile = File(note.audioPath)
            if (!audioFile.exists()) return Result.failure(IllegalStateException("Audio file missing"))

            val boundary = "----OrbVoice${System.currentTimeMillis()}"
            val conn = (URL(sparkMemoryUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(conn.outputStream).use { out ->
                fun writeField(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }

                writeField("source", "orb-voice-razr")
                writeField("note_id", note.id)
                writeField("speaker_count", note.speakerCount.toString())
                writeField("transcript_markdown", VoiceNoteStore.renderMarkdown(note))

                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"\r\n")
                out.writeBytes("Content-Type: audio/mpeg\r\n\r\n")
                out.write(audioFile.readBytes())
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            conn.disconnect()
            if (code in 200..299) Result.success(body.ifBlank { "Synced ($code)" })
            else Result.failure(IllegalStateException("HTTP $code: $body"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isEndpointReachable(urlStr: String): Boolean {
        return try {
            val u = URL(urlStr)
            val healthUrl = URL("${u.protocol}://${u.host}:${if (u.port > 0) u.port else 80}/health")
            val conn = (healthUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 1200
                readTimeout = 1200
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        } catch (_: Exception) {
            false
        }
    }

    private fun callOpenAiCompatibleAsr(urlStr: String, wavBytes: ByteArray, fileName: String): String? {
        return try {
            val boundary = "----OrbVoiceAsr${System.currentTimeMillis()}"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 45000
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                out.writeBytes("cohere-transcribe\r\n")

                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                out.write(wavBytes)
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(resp).optString("text", "").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    fun sliceToWavBytes(pcm: ShortArray, startSample: Int, endSample: Int, sampleRate: Int = 16000): ByteArray {
        val s = startSample.coerceIn(0, pcm.size)
        val e = endSample.coerceIn(s, pcm.size)
        val numSamples = e - s
        val dataBytes = numSamples * 2
        val baos = ByteArrayOutputStream(44 + dataBytes)
        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // Mono
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }
        baos.write(hdr.array())
        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in s until e) body.putShort(pcm[i])
        baos.write(body.array())
        return baos.toByteArray()
    }
}
