package com.th0rgal.orbvoice

import android.content.Context
import android.os.Environment
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCohereTranscribeModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 100% local, open-weights offline ASR + VAD engine using Sherpa-ONNX:
 *  - ASR: NVIDIA Parakeet-TDT-0.6B-v3 (INT8, ~640 MB RAM, 25 European languages incl. EN & FR,
 *         built-in punctuation & capitalization, RTF < 0.1 on Snapdragon 8 Elite Oryon).
 *  - VAD: Silero VAD v5 (~640 KB) for surgical speech/pause segmentation.
 */
class LocalAsrEngine private constructor(
    private val recognizer: OfflineRecognizer,
    private val vad: Vad?,
    val modelLabel: String
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
            Log.d("OrbVoice", "$modelLabel decoded ${samples.size / 16000f}s in ${ms}ms -> ${text.take(60)}")
            text
        } catch (e: Throwable) {
            Log.w("OrbVoice", "$modelLabel decode failed: ${e.message}")
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

    enum class AsrModel(val id: String, val label: String) {
        COHERE_2B("cohere", "Cohere Transcribe 2B"),
        PARAKEET_V3("parakeet", "Parakeet-TDT 0.6B v3"),
        WHISPER_TURBO("whisper", "Whisper Large-v3 Turbo");

        companion object {
            fun fromId(id: String?): AsrModel = entries.firstOrNull { it.id == id } ?: COHERE_2B
        }
    }

    companion object {
        private fun findModelDir(context: Context, subdir: String, requiredFiles: List<String>): File? {
            val candidates = listOf(
                File(VoiceNoteStore.modelsDir(context), subdir),
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "OrbVoice/models/$subdir"
                )
            )
            return candidates.firstOrNull { dir ->
                requiredFiles.all { File(dir, it).canRead() }
            }
        }

        private fun findCohereDir(context: Context): File? =
            findModelDir(context, "cohere-transcribe", listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"))

        private fun findParakeetDir(context: Context): File? =
            findModelDir(context, "parakeet-v3", listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"))

        private fun findWhisperDir(context: Context): File? =
            findModelDir(context, "whisper-turbo", listOf("turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt"))

        fun availableModels(context: Context): List<AsrModel> {
            val out = mutableListOf<AsrModel>()
            if (findCohereDir(context) != null) out.add(AsrModel.COHERE_2B)
            if (findParakeetDir(context) != null) out.add(AsrModel.PARAKEET_V3)
            if (findWhisperDir(context) != null) out.add(AsrModel.WHISPER_TURBO)
            return out
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

        fun create(context: Context, preferredId: String? = null, languageHint: String = "en"): LocalAsrEngine? {
            val preferred = AsrModel.fromId(preferredId)
            val order = listOf(preferred) + AsrModel.entries.filter { it != preferred }
            for (model in order) {
                val engine = createSpecific(context, model, languageHint)
                if (engine != null) return engine
            }
            Log.i("OrbVoice", "No local ASR model found; will use SODA/Spark fallback")
            return null
        }

        private fun createSpecific(context: Context, model: AsrModel, languageHint: String): LocalAsrEngine? {
            val t0 = System.nanoTime()
            val modelCfg = when (model) {
                AsrModel.COHERE_2B -> {
                    val dir = findCohereDir(context) ?: return null
                    val lang = languageHint.lowercase().take(2).ifBlank { "en" }
                    OfflineModelConfig(
                        cohereTranscribe = OfflineCohereTranscribeModelConfig(
                            encoder = File(dir, "encoder.int8.onnx").absolutePath,
                            decoder = File(dir, "decoder.int8.onnx").absolutePath,
                            language = lang,
                            usePunct = true,
                            useItn = true
                        ),
                        tokens = File(dir, "tokens.txt").absolutePath,
                        numThreads = 6,
                        debug = false,
                        provider = "cpu"
                    )
                }
                AsrModel.PARAKEET_V3 -> {
                    val dir = findParakeetDir(context) ?: return null
                    OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = File(dir, "encoder.int8.onnx").absolutePath,
                            decoder = File(dir, "decoder.int8.onnx").absolutePath,
                            joiner = File(dir, "joiner.int8.onnx").absolutePath
                        ),
                        tokens = File(dir, "tokens.txt").absolutePath,
                        numThreads = 6,
                        debug = false,
                        provider = "cpu",
                        modelType = "nemo_transducer"
                    )
                }
                AsrModel.WHISPER_TURBO -> {
                    val dir = findWhisperDir(context) ?: return null
                    OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = File(dir, "turbo-encoder.int8.onnx").absolutePath,
                            decoder = File(dir, "turbo-decoder.int8.onnx").absolutePath,
                            language = "",
                            task = "transcribe"
                        ),
                        tokens = File(dir, "turbo-tokens.txt").absolutePath,
                        numThreads = 6,
                        debug = false,
                        provider = "cpu"
                    )
                }
            }

            return try {
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
                Log.i("OrbVoice", "${model.label} loaded in ${ms}ms (SileroVAD=${vad != null})")
                LocalAsrEngine(recognizer, vad, model.label)
            } catch (e: Throwable) {
                Log.w("OrbVoice", "${model.label} init failed: ${e.message}")
                null
            }
        }

        /**
         * Optimization benchmark:
         *  1. Hexagon V79 NPU Speaker Engine cold compile vs cached Context Binary reload!
         *  2. Cohere-Transcribe-2B thread/provider sweep (cpu-4t, cpu-6t, xnnpack-6t, lang=en/fr)
         *  3. Parakeet-TDT-0.6B-v3 (cpu-6t vs xnnpack-6t)
         */
        fun runBenchmarkSuite(context: Context, wavPath: String) {
            Log.i("ScribeBench", "=== SCRIBE BENCHMARK START wav=$wavPath ===")
            val wavFile = File(wavPath)
            if (!wavFile.exists()) {
                Log.e("ScribeBench", "WAV not found: $wavPath")
                return
            }
            val bytes = wavFile.readBytes()
            val numSamples = ((bytes.size - 44).coerceAtLeast(0)) / 2
            val shorts = ShortArray(numSamples)
            ByteBuffer.wrap(bytes, 44, numSamples * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            val frStart = (16000 * 0.7).toInt().coerceAtMost(numSamples)
            val frEnd = (16000 * 6.5).toInt().coerceAtMost(numSamples)
            val enStart = (16000 * 8.5).toInt().coerceAtMost(numSamples)
            val enEnd = (16000 * 15.4).toInt().coerceAtMost(numSamples)
            val frFloats = FloatArray((frEnd - frStart).coerceAtLeast(0)) { shorts[frStart + it] / 32768f }
            val enFloats = FloatArray((enEnd - enStart).coerceAtLeast(0)) { shorts[enStart + it] / 32768f }

            // 1a. Speaker Embedding cold compile (creates V79 context binary cache)
            val spkT0 = System.nanoTime()
            val spk1 = SpeakerDiarizer.NeuralSpeakerEngine.create(context)
            val spkLoad1 = (System.nanoTime() - spkT0) / 1_000_000L
            spk1?.close()

            // 1b. Speaker Embedding WARM CACHED reload from Hexagon V79 context binary!
            val spkT1 = System.nanoTime()
            val spk2 = SpeakerDiarizer.NeuralSpeakerEngine.create(context)
            val spkLoad2 = (System.nanoTime() - spkT1) / 1_000_000L
            if (spk2 != null) {
                val t1 = System.nanoTime()
                val embFr = spk2.extractEmbedding(shorts, frStart, frEnd)
                val ms1 = (System.nanoTime() - t1) / 1_000_000L
                val t2 = System.nanoTime()
                val embEn = spk2.extractEmbedding(shorts, enStart, enEnd)
                val ms2 = (System.nanoTime() - t2) / 1_000_000L
                var cos = 0f
                if (embFr != null && embEn != null) {
                    for (i in embFr.indices) cos += embFr[i] * embEn[i]
                }
                Log.i("ScribeBench", "SPEAKER[${spk2.backendLabel}]: coldLoad=${spkLoad1}ms cachedLoad=${spkLoad2}ms turn1=${ms1}ms turn2=${ms2}ms diffSpeakerCos=${"%.3f".format(cos)}")
                spk2.close()
            }

            val modelsRoot = VoiceNoteStore.modelsDir(context)

            // 2. Cohere-Transcribe-2B sweep: cpu-6t (lang=en), cpu-6t (lang=fr), xnnpack-6t (lang=fr)
            val cohereDir = File(modelsRoot, "cohere-transcribe")
            if (File(cohereDir, "encoder.int8.onnx").exists()) {
                for ((label, prov, threads, lang) in listOf(
                    arrayOf("COHERE-2B cpu-6t lang=en", "cpu", "6", "en"),
                    arrayOf("COHERE-2B cpu-6t lang=fr", "cpu", "6", "fr"),
                    arrayOf("COHERE-2B xnnpack-6t lang=fr", "xnnpack", "6", "fr")
                )) {
                    runCatching {
                        val t0 = System.nanoTime()
                        val rec = OfflineRecognizer(
                            assetManager = null,
                            config = OfflineRecognizerConfig(
                                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                                modelConfig = OfflineModelConfig(
                                    cohereTranscribe = OfflineCohereTranscribeModelConfig(
                                        encoder = File(cohereDir, "encoder.int8.onnx").absolutePath,
                                        decoder = File(cohereDir, "decoder.int8.onnx").absolutePath,
                                        language = lang,
                                        usePunct = true,
                                        useItn = true
                                    ),
                                    tokens = File(cohereDir, "tokens.txt").absolutePath,
                                    numThreads = threads.toInt(),
                                    provider = prov
                                )
                            )
                        )
                        val loadMs = (System.nanoTime() - t0) / 1_000_000L
                        val tf0 = System.nanoTime(); val s1 = rec.createStream(); s1.acceptWaveform(frFloats, 16000); rec.decode(s1); val frTxt = rec.getResult(s1).text.trim(); s1.release(); val frMs = (System.nanoTime() - tf0) / 1_000_000L
                        val te0 = System.nanoTime(); val s2 = rec.createStream(); s2.acceptWaveform(enFloats, 16000); rec.decode(s2); val enTxt = rec.getResult(s2).text.trim(); s2.release(); val enMs = (System.nanoTime() - te0) / 1_000_000L
                        Log.i("ScribeBench", "$label: load=${loadMs}ms fr=${frMs}ms en=${enMs}ms | FR='$frTxt' | EN='$enTxt'")
                        rec.release()
                    }.onFailure { Log.e("ScribeBench", "$label failed: ${it.message}") }
                }
            }

            // 3. Parakeet-TDT-0.6B-v3 sweep: cpu-6t vs xnnpack-6t
            val pkDir = File(modelsRoot, "parakeet-v3")
            if (File(pkDir, "encoder.int8.onnx").exists()) {
                for ((label, prov) in listOf("PARAKEET-V3 cpu-6t" to "cpu", "PARAKEET-V3 xnnpack-6t" to "xnnpack")) {
                    runCatching {
                        val t0 = System.nanoTime()
                        val rec = OfflineRecognizer(
                            assetManager = null,
                            config = OfflineRecognizerConfig(
                                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                                modelConfig = OfflineModelConfig(
                                    transducer = OfflineTransducerModelConfig(
                                        encoder = File(pkDir, "encoder.int8.onnx").absolutePath,
                                        decoder = File(pkDir, "decoder.int8.onnx").absolutePath,
                                        joiner = File(pkDir, "joiner.int8.onnx").absolutePath
                                    ),
                                    tokens = File(pkDir, "tokens.txt").absolutePath,
                                    numThreads = 6,
                                    provider = prov,
                                    modelType = "nemo_transducer"
                                )
                            )
                        )
                        val loadMs = (System.nanoTime() - t0) / 1_000_000L
                        val tf0 = System.nanoTime(); val s1 = rec.createStream(); s1.acceptWaveform(frFloats, 16000); rec.decode(s1); val frTxt = rec.getResult(s1).text.trim(); s1.release(); val frMs = (System.nanoTime() - tf0) / 1_000_000L
                        val te0 = System.nanoTime(); val s2 = rec.createStream(); s2.acceptWaveform(enFloats, 16000); rec.decode(s2); val enTxt = rec.getResult(s2).text.trim(); s2.release(); val enMs = (System.nanoTime() - te0) / 1_000_000L
                        Log.i("ScribeBench", "$label: load=${loadMs}ms fr=${frMs}ms en=${enMs}ms | FR='$frTxt' | EN='$enTxt'")
                        rec.release()
                    }.onFailure { Log.e("ScribeBench", "$label failed: ${it.message}") }
                }
            }

            Log.i("ScribeBench", "=== SCRIBE BENCHMARK DONE ===")
        }
    }
}
