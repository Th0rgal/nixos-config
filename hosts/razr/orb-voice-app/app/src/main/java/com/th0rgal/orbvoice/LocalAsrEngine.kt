package com.th0rgal.orbvoice

import android.content.Context
import android.os.Environment
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineCohereTranscribeModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
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
    private val recognizer: OfflineRecognizer?,
    private val vad: Vad?,
    val modelLabel: String,
    private val useLocalQwen3Server: Boolean = false
) : Closeable {

    val hasSileroVad: Boolean get() = vad != null
    val isHeavyweight: Boolean get() = useLocalQwen3Server

    @Synchronized
    fun transcribe(samples: FloatArray): String {
        if (samples.size < 3200) return "" // < 200ms
        return try {
            val normalized = normalizeQuietAudio(samples)
            if (useLocalQwen3Server) {
                return LocalQwen3ServerClient.transcribe(normalized)
            }
            val rec = recognizer ?: return ""
            val t0 = System.nanoTime()
            val stream = rec.createStream()
            stream.acceptWaveform(normalized, 16000)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            stream.release()
            val ms = (System.nanoTime() - t0) / 1_000_000L
            Log.d("OrbVoice", "$modelLabel decoded ${samples.size / 16000f}s in ${ms}ms -> ${text.take(60)}")
            text
        } catch (e: Throwable) {
            Log.w("OrbVoice", "$modelLabel decode failed: ${e.message}")
            ""
        }
    }

    /**
     * Peak-limited RMS normalization to -23 dBFS (0.0708) for quiet far-field utterances.
     * Prevents ASR silence/empty-transcript drops on quiet room-mic speech (<= -40 dBFS)
     * while leaving normal-volume speech untouched.
     */
    private fun normalizeQuietAudio(samples: FloatArray): FloatArray {
        var sumSq = 0.0
        var peak = 0f
        for (v in samples) {
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
            sumSq += (v * v).toDouble()
        }
        val rms = kotlin.math.sqrt(sumSq / samples.size.coerceAtLeast(1)).toFloat()
        // Only boost genuinely quiet speech (above pure silence floor, below -26 dBFS)
        if (rms < 0.0004f || rms >= 0.05f || peak <= 1e-6f) return samples
        val targetRms = 0.0708f // -23 dBFS
        val gain = (targetRms / rms).coerceAtMost(0.95f / peak).coerceAtMost(12f)
        if (gain <= 1.15f) return samples
        return FloatArray(samples.size) { (samples[it] * gain).coerceIn(-1f, 1f) }
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

    @Synchronized
    fun resetVad() {
        try { vad?.reset() } catch (_: Exception) {}
    }

    override fun close() {
        try { vad?.release() } catch (_: Exception) {}
        try { recognizer?.release() } catch (_: Exception) {}
    }

    enum class AsrModel(val id: String, val label: String) {
        QWEN3_1_7B_LORA("qwen3-lora", "Qwen3 1.7B LoRA"),
        QWEN3_0_6B("qwen3-0.6b", "Qwen3-ASR 0.6B"),
        PARAKEET_V3("parakeet", "Parakeet-TDT 0.6B v3"),
        COHERE_2B("cohere", "Cohere Transcribe 2B"),
        CANARY_1B("canary-1b", "Canary-1B v2"),
        QWEN3_1_7B("qwen3-1.7b", "Qwen3-ASR 1.7B"),
        WHISPER_TURBO("whisper", "Whisper Large-v3 Turbo");

        companion object {
            fun fromId(id: String?): AsrModel = entries.firstOrNull { it.id == id } ?: QWEN3_1_7B_LORA
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

        private fun findCanaryDir(context: Context): File? =
            findModelDir(context, "canary-1b-v2", listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"))

        private val offlineTranslatorLock = Any()
        private var cachedCanaryKey: String? = null
        private var cachedCanaryRec: OfflineRecognizer? = null

        /**
         * 100% offline speech-to-text translation fallback using NVIDIA Canary-1B-v2 INT8
         * (~1.6-1.9s on Razr Oryon 6t). Used automatically in Translate mode when Orb Router
         * is unreachable (e.g. airplane mode / subway).
         */
        fun translateSpeechOffline(
            context: Context,
            samples: FloatArray,
            srcLangName: String,
            tgtLangName: String
        ): String? {
            val dir = findCanaryDir(context) ?: return null
            val langMap = mapOf(
                "english" to "en", "french" to "fr", "spanish" to "es",
                "german" to "de", "italian" to "it", "portuguese" to "pt"
            )
            val src = langMap[srcLangName.lowercase()] ?: return null
            val tgt = langMap[tgtLangName.lowercase()] ?: return null
            if (src == tgt) return null
            val key = "$src->$tgt"
            synchronized(offlineTranslatorLock) {
                return try {
                    if (cachedCanaryRec == null || cachedCanaryKey != key) {
                        try { cachedCanaryRec?.release() } catch (_: Exception) {}
                        cachedCanaryRec = OfflineRecognizer(
                            assetManager = null,
                            config = OfflineRecognizerConfig(
                                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 128),
                                modelConfig = OfflineModelConfig(
                                    canary = OfflineCanaryModelConfig(
                                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                                        decoder = File(dir, "decoder.int8.onnx").absolutePath,
                                        srcLang = src,
                                        tgtLang = tgt,
                                        usePnc = true
                                    ),
                                    tokens = File(dir, "tokens.txt").absolutePath,
                                    numThreads = 6,
                                    provider = "cpu"
                                )
                            )
                        )
                        cachedCanaryKey = key
                    }
                    val rec = cachedCanaryRec ?: return null
                    val s = rec.createStream()
                    s.acceptWaveform(samples, 16000)
                    rec.decode(s)
                    var out = rec.getResult(s).text.trim()
                    s.release()
                    // Mandatory Canary special-token loop guard (stop at first <|...|> token after text)
                    val spIdx = out.indexOf("<|")
                    if (spIdx > 0) out = out.substring(0, spIdx).trim()
                    out.ifBlank { null }
                } catch (e: Throwable) {
                    Log.w("OrbVoice", "Canary offline translate ($key) failed: ${e.message}")
                    null
                }
            }
        }

        private fun findQwen3Dir(context: Context, subdir: String): File? =
            findModelDir(context, subdir, listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx", "tokenizer/vocab.json"))

        private fun findParakeetDir(context: Context): File? =
            findModelDir(context, "parakeet-v3", listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"))

        private fun findWhisperDir(context: Context): File? =
            findModelDir(context, "whisper-turbo", listOf("turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt"))

        fun availableModels(context: Context): List<AsrModel> {
            val out = mutableListOf<AsrModel>()
            if (findCohereDir(context) != null) out.add(AsrModel.COHERE_2B)
            if (findQwen3Dir(context, "qwen3-asr-1.7b") != null) out.add(AsrModel.QWEN3_1_7B)
            if (findQwen3Dir(context, "qwen3-asr-0.6b") != null) out.add(AsrModel.QWEN3_0_6B)
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

        private fun createSileroVad(context: Context): Vad? {
            val vadFile = findSileroVad(context) ?: return null
            return try {
                val vCfg = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadFile.absolutePath,
                        threshold = 0.25f,
                        minSilenceDuration = 0.55f,
                        minSpeechDuration = 0.20f,
                        windowSize = 512,
                        maxSpeechDuration = 12.0f
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
        }

        private fun createSpecific(context: Context, model: AsrModel, languageHint: String): LocalAsrEngine? {
            val t0 = System.nanoTime()
            if (model == AsrModel.QWEN3_1_7B_LORA) {
                if (!LocalQwen3ServerClient.isHealthy()) {
                    Log.i("OrbVoice", "Local Qwen3-1.7B-LoRA llama-server not running on 127.0.0.1:8089; falling back")
                    return null
                }
                val vad = createSileroVad(context)
                Log.i("OrbVoice", "Connected to local Qwen3 1.7B LoRA daemon on 127.0.0.1:8089 (SileroVAD=${vad != null})")
                return LocalAsrEngine(recognizer = null, vad = vad, modelLabel = model.label, useLocalQwen3Server = true)
            }
            val modelCfg = when (model) {
                AsrModel.QWEN3_1_7B_LORA -> return null
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
                AsrModel.CANARY_1B -> {
                    val dir = findCanaryDir(context) ?: return null
                    val lang = languageHint.lowercase().take(2).ifBlank { "en" }
                    OfflineModelConfig(
                        canary = OfflineCanaryModelConfig(
                            encoder = File(dir, "encoder.int8.onnx").absolutePath,
                            decoder = File(dir, "decoder.int8.onnx").absolutePath,
                            srcLang = lang,
                            tgtLang = lang,
                            usePnc = true
                        ),
                        tokens = File(dir, "tokens.txt").absolutePath,
                        numThreads = 6,
                        debug = false,
                        provider = "cpu"
                    )
                }
                AsrModel.QWEN3_1_7B, AsrModel.QWEN3_0_6B -> {
                    val subdir = if (model == AsrModel.QWEN3_1_7B) "qwen3-asr-1.7b" else "qwen3-asr-0.6b"
                    val dir = findQwen3Dir(context, subdir) ?: return null
                    OfflineModelConfig(
                        qwen3Asr = OfflineQwen3AsrModelConfig(
                            convFrontend = File(dir, "conv_frontend.onnx").absolutePath,
                            encoder = File(dir, "encoder.int8.onnx").absolutePath,
                            decoder = File(dir, "decoder.int8.onnx").absolutePath,
                            tokenizer = File(dir, "tokenizer").absolutePath,
                            maxTotalLen = 512,
                            maxNewTokens = 128,
                            temperature = 1e-6f,
                            topP = 0.8f,
                            seed = 42,
                            hotwords = ""
                        ),
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
                val vad = createSileroVad(context)

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

            // 2. Qwen3-ASR 0.6B & 1.7B INT8 (zero-shot 30+ lang auto-detect + code-switching)
            for ((label, subdir) in listOf(
                "QWEN3-ASR-0.6B cpu-6t" to "qwen3-asr-0.6b",
                "QWEN3-ASR-1.7B cpu-6t" to "qwen3-asr-1.7b"
            )) {
                val qDir = File(modelsRoot, subdir)
                if (File(qDir, "decoder.int8.onnx").exists()) {
                    runCatching {
                        val t0 = System.nanoTime()
                        val rec = OfflineRecognizer(
                            assetManager = null,
                            config = OfflineRecognizerConfig(
                                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                                modelConfig = OfflineModelConfig(
                                    qwen3Asr = OfflineQwen3AsrModelConfig(
                                        convFrontend = File(qDir, "conv_frontend.onnx").absolutePath,
                                        encoder = File(qDir, "encoder.int8.onnx").absolutePath,
                                        decoder = File(qDir, "decoder.int8.onnx").absolutePath,
                                        tokenizer = File(qDir, "tokenizer").absolutePath,
                                        maxTotalLen = 512,
                                        maxNewTokens = 128,
                                        temperature = 1e-6f,
                                        topP = 0.8f,
                                        seed = 42,
                                        hotwords = ""
                                    ),
                                    numThreads = 6,
                                    provider = "cpu"
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

            // 3. Cohere-Transcribe-2B INT8 (cpu-6t, lang=fr)
            val cohereDir = File(modelsRoot, "cohere-transcribe")
            if (File(cohereDir, "encoder.int8.onnx").exists()) {
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
                                    language = "fr",
                                    usePunct = true,
                                    useItn = true
                                ),
                                tokens = File(cohereDir, "tokens.txt").absolutePath,
                                numThreads = 6,
                                provider = "cpu"
                            )
                        )
                    )
                    val loadMs = (System.nanoTime() - t0) / 1_000_000L
                    val tf0 = System.nanoTime(); val s1 = rec.createStream(); s1.acceptWaveform(frFloats, 16000); rec.decode(s1); val frTxt = rec.getResult(s1).text.trim(); s1.release(); val frMs = (System.nanoTime() - tf0) / 1_000_000L
                    val te0 = System.nanoTime(); val s2 = rec.createStream(); s2.acceptWaveform(enFloats, 16000); rec.decode(s2); val enTxt = rec.getResult(s2).text.trim(); s2.release(); val enMs = (System.nanoTime() - te0) / 1_000_000L
                    Log.i("ScribeBench", "COHERE-2B cpu-6t lang=fr: load=${loadMs}ms fr=${frMs}ms en=${enMs}ms | FR='$frTxt' | EN='$enTxt'")
                    rec.release()
                }.onFailure { Log.e("ScribeBench", "COHERE-2B failed: ${it.message}") }
            }

            // 4. Wave 2B: NVIDIA Canary-1B-v2 INT8 (25 EU langs + 100% OFFLINE Speech-to-Text Translation!)
            val canaryDir = File(modelsRoot, "canary-1b-v2")
            if (File(canaryDir, "encoder.int8.onnx").exists()) {
                val rng = java.util.Random(42L)
                fun makeNoisy(clean: FloatArray): FloatArray {
                    val att = FloatArray(clean.size) { clean[it] * 0.35f }
                    var p = 0.0
                    for (v in att) p += (v * v).toDouble()
                    val std = kotlin.math.sqrt((p / att.size.coerceAtLeast(1)) / 6.3095734448)
                    return FloatArray(att.size) { (att[it] + rng.nextGaussian() * std).toFloat().coerceIn(-1f, 1f) }
                }
                val frNoisy = makeNoisy(frFloats)
                val enNoisy = makeNoisy(enFloats)
                for ((tag, srcL, tgtL, clip) in listOf(
                    arrayOf("CANARY-1B FR->FR clean", "fr", "fr", "fr_clean"),
                    arrayOf("CANARY-1B FR->EN OFFLINE-TRANSLATE", "fr", "en", "fr_clean"),
                    arrayOf("CANARY-1B EN->EN clean", "en", "en", "en_clean"),
                    arrayOf("CANARY-1B EN->FR OFFLINE-TRANSLATE", "en", "fr", "en_clean"),
                    arrayOf("CANARY-1B EN->EN noisy-8dB", "en", "en", "en_noisy"),
                    arrayOf("CANARY-1B FR->FR noisy-8dB", "fr", "fr", "fr_noisy")
                )) {
                    runCatching {
                        val t0 = System.nanoTime()
                        val rec = OfflineRecognizer(
                            assetManager = null,
                            config = OfflineRecognizerConfig(
                                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 128),
                                modelConfig = OfflineModelConfig(
                                    canary = OfflineCanaryModelConfig(
                                        encoder = File(canaryDir, "encoder.int8.onnx").absolutePath,
                                        decoder = File(canaryDir, "decoder.int8.onnx").absolutePath,
                                        srcLang = srcL,
                                        tgtLang = tgtL,
                                        usePnc = true
                                    ),
                                    tokens = File(canaryDir, "tokens.txt").absolutePath,
                                    numThreads = 6,
                                    provider = "cpu"
                                )
                            )
                        )
                        val loadMs = (System.nanoTime() - t0) / 1_000_000L
                        val audio = when (clip) {
                            "fr_clean" -> frFloats
                            "en_clean" -> enFloats
                            "fr_noisy" -> frNoisy
                            else -> enNoisy
                        }
                        val td0 = System.nanoTime()
                        val s = rec.createStream()
                        s.acceptWaveform(audio, 16000)
                        rec.decode(s)
                        val txt = rec.getResult(s).text.trim()
                        s.release()
                        val decMs = (System.nanoTime() - td0) / 1_000_000L
                        Log.i("ScribeBench", "$tag: load=${loadMs}ms dec=${decMs}ms | OUT='$txt'")
                        rec.release()
                    }.onFailure { Log.e("ScribeBench", "$tag failed: ${it.message}") }
                }
            }

            // 5. Wave 5: Test Static Parakeet FP16 Encoder directly on Hexagon V79 NPU (rpcmem ShMem + Opt3)!
            val staticPk = File(modelsRoot, "parakeet-v3/encoder.fp16.static-T500.onnx")
            if (staticPk.exists()) {
                runCatching {
                    val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    val cacheFile = File(context.cacheDir, "qnn_v79_parakeet_T500_fp16.ctx.onnx")
                    val useCache = cacheFile.exists() && cacheFile.length() > 10_000L
                    val t0 = System.nanoTime()
                    val opts = ai.onnxruntime.OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(2)
                        addConfigEntry("session.disable_cpu_ep_fallback", "1")
                        if (!useCache) {
                            if (cacheFile.exists()) cacheFile.delete()
                            addConfigEntry("ep.context_enable", "1")
                            addConfigEntry("ep.context_embed_mode", "1")
                            addConfigEntry("ep.context_file_path", cacheFile.absolutePath)
                        }
                        val qnn = mutableMapOf(
                            "backend_path" to "$nativeLibDir/libQnnHtp.so",
                            "htp_performance_mode" to "burst",
                            "enable_htp_fp16_precision" to "1",
                            "htp_graph_finalization_optimization_mode" to "3",
                            "rpc_control_latency" to "10"
                        )
                        if (useCache) qnn["enable_htp_shared_memory_allocator"] = "1"
                        addQnn(qnn)
                    }
                    val sess = env.createSession(if (useCache) cacheFile.absolutePath else staticPk.absolutePath, opts)
                    val loadMs = (System.nanoTime() - t0) / 1_000_000L
                    val featBuf = java.nio.FloatBuffer.allocate(128 * 500)
                    val lenBuf = java.nio.LongBuffer.wrap(longArrayOf(400L))
                    val tAudio = ai.onnxruntime.OnnxTensor.createTensor(env, featBuf, longArrayOf(1L, 128L, 500L))
                    val tLen = ai.onnxruntime.OnnxTensor.createTensor(env, lenBuf, longArrayOf(1L))
                    val inputs = mapOf("audio_signal" to tAudio, "length" to tLen)
                    val r1t = System.nanoTime(); sess.run(inputs).close(); val ms1 = (System.nanoTime() - r1t) / 1_000_000L
                    val r2t = System.nanoTime(); sess.run(inputs).close(); val ms2 = (System.nanoTime() - r2t) / 1_000_000L
                    tAudio.close(); tLen.close(); sess.close()
                    Log.i("ScribeBench", "PARAKEET-ENCODER-HEXAGON-V79-NPU: cached=$useCache load=${loadMs}ms run1=${ms1}ms run2=${ms2}ms ctxKB=${cacheFile.length()/1024}")
                }.onFailure { Log.e("ScribeBench", "PARAKEET-ENCODER-HEXAGON-V79-NPU failed: ${it.message}") }
            }

            // 6. Wave 5: Test local Qwen3-1.7B-Scribe-LoRA llama-server on 127.0.0.1:8089!
            if (LocalQwen3ServerClient.isHealthy()) {
                val tf0 = System.nanoTime(); val frTxt = LocalQwen3ServerClient.transcribe(frFloats); val frMs = (System.nanoTime() - tf0) / 1_000_000L
                val te0 = System.nanoTime(); val enTxt = LocalQwen3ServerClient.transcribe(enFloats); val enMs = (System.nanoTime() - te0) / 1_000_000L
                Log.i("ScribeBench", "QWEN3-1.7B-SCRIBE-LORA (127.0.0.1:8089): fr=${frMs}ms en=${enMs}ms | FR='$frTxt' | EN='$enTxt'")
            }

            Log.i("ScribeBench", "=== SCRIBE BENCHMARK DONE ===")
        }
    }
}
