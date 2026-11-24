package com.th0rgal.orbvoice

import android.content.Context
import android.os.Environment
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.Closeable
import java.io.File

/**
 * 100% local, open-weights offline ASR + VAD engine using Sherpa-ONNX:
 *  - ASR: NVIDIA Parakeet-TDT-0.6B-v3 (INT8, ~640 MB RAM, 25 European languages incl. EN & FR,
 *         built-in punctuation & capitalization, RTF < 0.1 on Snapdragon 8 Elite Oryon).
 *  - VAD: Silero VAD v5 (~640 KB) for surgical speech/pause segmentation.
 */
class LocalAsrEngine private constructor(
    private val recognizer: OfflineRecognizer,
    private val vad: Vad?
) : Closeable {

    val hasSileroVad: Boolean get() = vad != null

    @Synchronized
    fun transcribe(samples: FloatArray): String {
        if (samples.size < 3200) return "" // < 200ms
        return try {
            val t0 = System.nanoTime()
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            stream.release()
            val ms = (System.nanoTime() - t0) / 1_000_000L
            Log.d("OrbVoice", "Parakeet-v3 decoded ${samples.size / 16000f}s in ${ms}ms -> ${text.take(60)}")
            text
        } catch (e: Throwable) {
            Log.w("OrbVoice", "Parakeet-v3 decode failed: ${e.message}")
            ""
        }
    }

    @Synchronized
    fun feedVad(chunk: FloatArray): List<FloatArray> {
        val v = vad ?: return emptyList()
        v.acceptWaveform(chunk)
        val out = mutableListOf<FloatArray>()
        while (!v.empty()) {
            val seg = v.front()
            if (seg.samples.isNotEmpty()) out.add(seg.samples.copyOf())
            v.pop()
        }
        return out
    }

    @Synchronized
    fun isSpeechActive(): Boolean = vad?.isSpeechDetected() ?: false

    @Synchronized
    fun flushVad(): List<FloatArray> {
        val v = vad ?: return emptyList()
        v.flush()
        val out = mutableListOf<FloatArray>()
        while (!v.empty()) {
            val seg = v.front()
            if (seg.samples.isNotEmpty()) out.add(seg.samples.copyOf())
            v.pop()
        }
        return out
    }

    override fun close() {
        try { vad?.release() } catch (_: Exception) {}
        try { recognizer.release() } catch (_: Exception) {}
    }

    companion object {
        private fun findParakeetDir(context: Context): File? {
            val candidates = listOf(
                File(VoiceNoteStore.modelsDir(context), "parakeet-v3"),
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "OrbVoice/models/parakeet-v3"
                )
            )
            return candidates.firstOrNull { dir ->
                File(dir, "encoder.int8.onnx").canRead() &&
                    File(dir, "decoder.int8.onnx").canRead() &&
                    File(dir, "joiner.int8.onnx").canRead() &&
                    File(dir, "tokens.txt").canRead()
            }
        }

        private fun findSileroVad(context: Context): File? {
            val candidates = listOf(
                File(VoiceNoteStore.modelsDir(context), "silero_vad.onnx"),
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "OrbVoice/models/silero_vad.onnx"
                )
            )
            return candidates.firstOrNull { it.exists() && it.canRead() && it.length() > 10_000L }
        }

        fun create(context: Context): LocalAsrEngine? {
            val dir = findParakeetDir(context) ?: run {
                Log.i("OrbVoice", "Parakeet-TDT-0.6B-v3 not found; will use SODA/Spark fallback")
                return null
            }
            return try {
                val t0 = System.nanoTime()
                val modelCfg = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder.int8.onnx").absolutePath,
                        joiner = File(dir, "joiner.int8.onnx").absolutePath
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                    modelType = "nemo_transducer"
                )
                val recCfg = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = modelCfg,
                    decodingMethod = "greedy_search"
                )
                val recognizer = OfflineRecognizer(assetManager = null, config = recCfg)

                val vadFile = findSileroVad(context)
                val vad = if (vadFile != null) {
                    try {
                        val vCfg = VadModelConfig(
                            sileroVadModelConfig = SileroVadModelConfig(
                                model = vadFile.absolutePath,
                                threshold = 0.25f,
                                minSilenceDuration = 0.45f,
                                minSpeechDuration = 0.15f,
                                windowSize = 512,
                                maxSpeechDuration = 8.0f
                            ),
                            sampleRate = 16000,
                            numThreads = 2,
                            provider = "cpu",
                            debug = false
                        )
                        Vad(assetManager = null, config = vCfg)
                    } catch (e: Throwable) {
                        Log.w("OrbVoice", "Silero VAD init failed: ${e.message}")
                        null
                    }
                } else null

                val ms = (System.nanoTime() - t0) / 1_000_000L
                Log.i("OrbVoice", "Parakeet-TDT-0.6B-v3 loaded in ${ms}ms (SileroVAD=${vad != null})")
                LocalAsrEngine(recognizer, vad)
            } catch (e: Throwable) {
                Log.w("OrbVoice", "Parakeet-TDT-0.6B-v3 init failed: ${e.message}")
                null
            }
        }
    }
}
