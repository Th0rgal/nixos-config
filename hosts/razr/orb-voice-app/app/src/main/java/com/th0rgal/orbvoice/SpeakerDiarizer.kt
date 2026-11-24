package com.th0rgal.orbvoice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Environment
import android.system.Os
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class DiarizedTurn(
    val speakerIndex: Int,
    val startSample: Int,
    val endSample: Int
) {
    val startMs: Long get() = (startSample * 1000L) / 16000L
    val endMs: Long get() = (endSample * 1000L) / 16000L
}

/**
 * Multi-speaker voice diarization engine for Orb Voice on Motorola Razr 60 Ultra.
 *
 * - Neural tier (preferred): runs WeSpeaker ResNet34-LM (`speaker_embedding_static200.onnx`,
 *   input `feats: [1, 200, 80]` 80-bin Kaldi log-Mel Fbank -> `embs: [1, 256]`) on the
 *   Qualcomm Hexagon V79 NPU via ONNX Runtime QNN HTP EP (with automatic Oryon CPU EP fallback).
 * - DSP fallback tier (if no ONNX model is installed): voiced-only MFCC (DCT c1..c13) + median F0.
 * - Session-adaptive `OnlineSpeakerTracker` continuously refines each speaker's learned voiceprint
 *   centroid as they keep talking and merges signatures when two clusters are given the same label.
 */
object SpeakerDiarizer {
    private const val SAMPLE_RATE = 16000
    private const val FRAME_MS = 30
    private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // 480 samples
    const val STATIC_FBANK_FRAMES = 200 // 2.0s static window for Hexagon V79 QNN HTP
    const val MEL_BINS = 80

    fun findSpeakerModel(context: Context): File? {
        val candidates = listOf(
            File(VoiceNoteStore.modelsDir(context), "speaker_embedding_static200.onnx"),
            File(VoiceNoteStore.modelsDir(context), "speaker_embedding.onnx"),
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "OrbVoice/models/speaker_embedding_static200.onnx"
            ),
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "OrbVoice/models/speaker_embedding.onnx"
            )
        )
        return candidates.firstOrNull { it.exists() && it.canRead() && it.length() > 100_000L }
    }

    /**
     * Warm persistent ONNX Runtime session for real-time 256-dim WeSpeaker neural embeddings.
     * Kept resident in RAM (16 GB on Razr) for the duration of a recording session.
     */
    class NeuralSpeakerEngine private constructor(
        private val env: OrtEnvironment,
        private val session: OrtSession,
        private val inputName: String,
        val backendLabel: String
    ) : Closeable {

        // Reusable off-heap native DirectByteBuffer so ONNX Runtime QNN EP reads input features
        // without JVM heap pinning/copying on every speaker turn.
        private val directInputBuf: FloatBuffer = ByteBuffer
            .allocateDirect(STATIC_FBANK_FRAMES * MEL_BINS * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        private val inputShape = longArrayOf(1L, STATIC_FBANK_FRAMES.toLong(), MEL_BINS.toLong())

        @Synchronized
        fun extractEmbedding(pcm: ShortArray, start: Int = 0, end: Int = pcm.size): FloatArray? {
            val fbank = KaldiFbank80.extractStatic(pcm, start, end, STATIC_FBANK_FRAMES) ?: return null
            return try {
                val t0 = System.nanoTime()
                directInputBuf.clear()
                directInputBuf.put(fbank)
                directInputBuf.flip()
                val tensor = OnnxTensor.createTensor(env, directInputBuf, inputShape)
                tensor.use { t ->
                    session.run(mapOf(inputName to t)).use { res ->
                        val raw = res[0].value
                        val vec = when (raw) {
                            is Array<*> -> (raw[0] as? FloatArray)?.copyOf()
                            is FloatArray -> raw.copyOf()
                            else -> null
                        } ?: return null
                        l2Normalize(vec)
                        val ms = (System.nanoTime() - t0) / 1_000_000L
                        Log.d("OrbVoice", "NeuralSpeakerEngine ($backendLabel) emb=${vec.size}d in ${ms}ms")
                        vec
                    }
                }
            } catch (e: Throwable) {
                Log.w("OrbVoice", "NeuralSpeakerEngine inference failed: ${e.message}")
                null
            }
        }

        override fun close() {
            try { session.close() } catch (_: Exception) {}
        }

        companion object {
            fun create(context: Context): NeuralSpeakerEngine? {
                val modelFile = findSpeakerModel(context) ?: run {
                    Log.i("OrbVoice", "No speaker_embedding*.onnx found; using CPU MFCC fallback")
                    return null
                }
                val env = OrtEnvironment.getEnvironment()
                val npuReady = NpuTranscriptionEngine.probeHardware().isReady

                // 1. Qualcomm Hexagon V79 QNN HTP NPU (FP16 + Opt Level 3 Context Cache + rpcmem Shared Memory!)
                if (npuReady) {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    val cacheFile = File(
                        context.cacheDir,
                        "qnn_v79_opt3_${modelFile.nameWithoutExtension}_${modelFile.length()}.ctx.onnx"
                    )
                    val useCache = cacheFile.exists() && cacheFile.length() > 10_000L
                    Log.i("OrbVoice", "Probing Hexagon V79 NPU (Opt3+ShMem): backend=$nativeLibDir/libQnnHtp.so cached=$useCache")

                    // When loading a cached context binary, enable Qualcomm FastRPC rpcmem zero-copy shared memory!
                    val attempts = if (useCache) listOf(true, false) else listOf(false)
                    for (useSharedMem in attempts) {
                        try {
                            val opts = OrtSession.SessionOptions().apply {
                                setIntraOpNumThreads(2)
                                addConfigEntry("session.disable_cpu_ep_fallback", "1")
                                if (!useCache) {
                                    if (cacheFile.exists()) cacheFile.delete()
                                    addConfigEntry("ep.context_enable", "1")
                                    addConfigEntry("ep.context_embed_mode", "1")
                                    addConfigEntry("ep.context_file_path", cacheFile.absolutePath)
                                }
                                val qnnOpts = mutableMapOf(
                                    "backend_path" to "$nativeLibDir/libQnnHtp.so",
                                    "htp_performance_mode" to "burst",
                                    "enable_htp_fp16_precision" to "1",
                                    "htp_graph_finalization_optimization_mode" to "3",
                                    "rpc_control_latency" to "10"
                                )
                                if (useSharedMem) {
                                    qnnOpts["enable_htp_shared_memory_allocator"] = "1"
                                }
                                addQnn(qnnOpts)
                            }
                            val loadPath = if (useCache) cacheFile.absolutePath else modelFile.absolutePath
                            val session = env.createSession(loadPath, opts)
                            val inName = session.inputNames.first()
                            val label = if (useSharedMem) "Hexagon V79 NPU (ShMem)" else "Hexagon V79 NPU"
                            val engine = NeuralSpeakerEngine(env, session, inName, label)
                            val dummy = ShortArray(SAMPLE_RATE * 2) { if (it % 40 < 20) 1500 else -1500 }
                            val e1 = engine.extractEmbedding(dummy)
                            if (e1 != null) {
                                Log.i(
                                    "OrbVoice",
                                    "NeuralSpeakerEngine UNLOCKED on $label (${modelFile.name}, cached=$useCache, ctxSize=${cacheFile.length() / 1024}KB)"
                                )
                                return engine
                            }
                            engine.close()
                        } catch (e: Throwable) {
                            Log.w("OrbVoice", "QNN HTP V79 init failed (cached=$useCache, shmem=$useSharedMem): ${e.message}")
                            if (useCache && !useSharedMem) {
                                cacheFile.delete()
                            }
                        }
                    }

                    // 1b. Try Qualcomm Adreno 830 QNN GPU backend if HTP failed
                    try {
                        val opts = OrtSession.SessionOptions().apply {
                            setIntraOpNumThreads(2)
                            addConfigEntry("session.disable_cpu_ep_fallback", "1")
                            addQnn(
                                mapOf(
                                    "backend_path" to "$nativeLibDir/libQnnGpu.so"
                                )
                            )
                        }
                        val session = env.createSession(modelFile.absolutePath, opts)
                        val inName = session.inputNames.first()
                        val engine = NeuralSpeakerEngine(env, session, inName, "Adreno 830 QNN GPU")
                        val dummy = ShortArray(SAMPLE_RATE * 2) { if (it % 40 < 20) 1500 else -1500 }
                        val e1 = engine.extractEmbedding(dummy)
                        val e2 = engine.extractEmbedding(dummy)
                        if (e1 != null && e2 != null) {
                            Log.i("OrbVoice", "NeuralSpeakerEngine UNLOCKED on Adreno 830 QNN GPU (${modelFile.name})")
                            return engine
                        }
                        engine.close()
                    } catch (e: Throwable) {
                        Log.w("OrbVoice", "QNN GPU init failed, falling back to Oryon CPU: ${e.message}")
                    }
                }

                // 2. High-speed Oryon CPU EP fallback (4 threads, ~15ms per 2s utterance)
                return try {
                    val opts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(4)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                    val session = env.createSession(modelFile.absolutePath, opts)
                    val inName = session.inputNames.first()
                    Log.i("OrbVoice", "NeuralSpeakerEngine loaded on Oryon CPU (${modelFile.name})")
                    NeuralSpeakerEngine(env, session, inName, "WeSpeaker Oryon CPU")
                } catch (e: Throwable) {
                    Log.w("OrbVoice", "NeuralSpeakerEngine failed to load: ${e.message}")
                    null
                }
            }
        }
    }

    /**
     * Exact 80-bin Kaldi-compatible Log-Mel Filterbank extractor (16 kHz, 25ms window, 10ms hop,
     * 512-pt radix-2 FFT, pre-emphasis 0.97, 20Hz..7600Hz mel triangle filters, per-utterance CMN).
     */
    object KaldiFbank80 {
        private const val WIN_LEN = 400 // 25ms @ 16kHz
        private const val HOP_LEN = 160 // 10ms @ 16kHz
        private const val FFT_SIZE = 512
        private const val HALF_FFT = FFT_SIZE / 2 + 1 // 257

        private val poveyWindow = FloatArray(WIN_LEN) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (WIN_LEN - 1))).pow(0.85).toFloat()
        }

        private val melFilterBank: Array<FloatArray> by lazy { buildMelFilters() }
        private val bitRev = IntArray(FFT_SIZE).also { arr ->
            var j = 0
            for (i in 1 until FFT_SIZE) {
                var bit = FFT_SIZE shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                arr[i] = j
            }
        }
        private val cosTable = DoubleArray(FFT_SIZE / 2) { i -> cos(-2.0 * PI * i / FFT_SIZE) }
        private val sinTable = DoubleArray(FFT_SIZE / 2) { i -> sin(-2.0 * PI * i / FFT_SIZE) }

        private fun hzToMel(hz: Double): Double = 1127.0 * ln(1.0 + hz / 700.0)

        private fun buildMelFilters(): Array<FloatArray> {
            val lowMel = hzToMel(20.0)
            val highMel = hzToMel(7600.0)
            val melDelta = (highMel - lowMel) / (MEL_BINS + 1)
            val fftBinWidth = SAMPLE_RATE.toDouble() / FFT_SIZE
            return Array(MEL_BINS) { bin ->
                val leftMel = lowMel + bin * melDelta
                val centerMel = lowMel + (bin + 1) * melDelta
                val rightMel = lowMel + (bin + 2) * melDelta
                FloatArray(HALF_FFT) { k ->
                    val mel = hzToMel(k * fftBinWidth)
                    when {
                        mel <= leftMel || mel >= rightMel -> 0f
                        mel <= centerMel -> ((mel - leftMel) / (centerMel - leftMel)).toFloat()
                        else -> ((rightMel - mel) / (rightMel - centerMel)).toFloat()
                    }
                }
            }
        }

        private fun fftInPlace(re: DoubleArray, im: DoubleArray) {
            for (i in 0 until FFT_SIZE) {
                val j = bitRev[i]
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= FFT_SIZE) {
                val half = len shr 1
                val step = FFT_SIZE / len
                var i = 0
                while (i < FFT_SIZE) {
                    var k = 0
                    for (j in 0 until half) {
                        val wr = cosTable[k]
                        val wi = sinTable[k]
                        k += step
                        val uRe = re[i + j]
                        val uIm = im[i + j]
                        val vRe = re[i + j + half] * wr - im[i + j + half] * wi
                        val vIm = re[i + j + half] * wi + im[i + j + half] * wr
                        re[i + j] = uRe + vRe
                        im[i + j] = uIm + vIm
                        re[i + j + half] = uRe - vRe
                        im[i + j + half] = uIm - vIm
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        /**
         * Extracts static `[targetFrames, 80]` CMN-normalized Kaldi log-Mel filterbanks.
         * Keeps active speech frames; if fewer than [targetFrames], wraps cleanly (no zero-padding artifact).
         */
        fun extractStatic(
            pcm: ShortArray,
            start: Int = 0,
            end: Int = pcm.size,
            targetFrames: Int = STATIC_FBANK_FRAMES
        ): FloatArray? {
            val safeStart = start.coerceIn(0, pcm.size)
            val safeEnd = end.coerceIn(safeStart, pcm.size)
            val len = safeEnd - safeStart
            if (len < WIN_LEN) return null

            val totalFrames = (len - WIN_LEN) / HOP_LEN + 1
            if (totalFrames <= 0) return null

            val re = DoubleArray(FFT_SIZE)
            val im = DoubleArray(FFT_SIZE)
            val powerSpec = DoubleArray(HALF_FFT)
            val filters = melFilterBank

            // Compute raw frames (up to 300 frames = 3s) and keep voiced/active frames if possible
            val rawFrames = ArrayList<FloatArray>(minOf(totalFrames, 300))
            val step = if (totalFrames > 300) totalFrames / 300 else 1
            var f = 0
            while (f < totalFrames && rawFrames.size < 300) {
                val offset = safeStart + f * HOP_LEN
                f += step

                // DC removal + pre-emphasis (0.97) + Povey window
                var mean = 0.0
                for (i in 0 until WIN_LEN) mean += pcm[offset + i].toDouble()
                mean /= WIN_LEN

                var prev = pcm[offset].toDouble() - mean
                var frameEnergy = 0.0
                for (i in 0 until WIN_LEN) {
                    val cur = pcm[offset + i].toDouble() - mean
                    val pre = if (i == 0) cur * 0.03 else cur - 0.97 * prev
                    prev = cur
                    val w = pre * poveyWindow[i]
                    re[i] = w
                    im[i] = 0.0
                    frameEnergy += cur * cur
                }
                for (i in WIN_LEN until FFT_SIZE) {
                    re[i] = 0.0
                    im[i] = 0.0
                }
                val rms = sqrt(frameEnergy / WIN_LEN) / 32768.0
                if (rms < 0.006) continue // skip pure silence frames

                fftInPlace(re, im)
                for (k in 0 until HALF_FFT) {
                    powerSpec[k] = re[k] * re[k] + im[k] * im[k]
                }
                val melFrame = FloatArray(MEL_BINS)
                for (b in 0 until MEL_BINS) {
                    val filt = filters[b]
                    var sum = 0.0
                    for (k in 0 until HALF_FFT) {
                        val w = filt[k]
                        if (w != 0f) sum += powerSpec[k] * w
                    }
                    melFrame[b] = ln(sum.coerceAtLeast(1e-10)).toFloat()
                }
                rawFrames.add(melFrame)
            }

            if (rawFrames.size < 12) return null // Not enough active speech

            // Tile/center-crop into exact [targetFrames, 80] tensor
            val out = FloatArray(targetFrames * MEL_BINS)
            val nRaw = rawFrames.size
            val startIdx = if (nRaw > targetFrames) (nRaw - targetFrames) / 2 else 0
            for (t in 0 until targetFrames) {
                val src = rawFrames[(startIdx + t) % nRaw]
                System.arraycopy(src, 0, out, t * MEL_BINS, MEL_BINS)
            }

            // Per-utterance Cepstral Mean Normalization (CMN) required by WeSpeaker / 3D-Speaker
            val colMean = DoubleArray(MEL_BINS)
            for (t in 0 until targetFrames) {
                val base = t * MEL_BINS
                for (b in 0 until MEL_BINS) colMean[b] += out[base + b]
            }
            for (b in 0 until MEL_BINS) colMean[b] /= targetFrames
            for (t in 0 until targetFrames) {
                val base = t * MEL_BINS
                for (b in 0 until MEL_BINS) out[base + b] = (out[base + b] - colMean[b]).toFloat()
            }
            return out
        }
    }

    /**
     * Offline diarization pass for full recordings.
     */
    fun diarize(
        context: Context,
        pcm: ShortArray,
        maxSpeakers: Int = 6
    ): Pair<List<DiarizedTurn>, String> {
        val spans = detectSpeechSpans(pcm)
        if (spans.isEmpty()) return emptyList<DiarizedTurn>() to "Silence"

        val neural = NeuralSpeakerEngine.create(context)
        val tracker = OnlineSpeakerTracker(neuralEngine = neural, maxSpeakers = maxSpeakers)
        val engineTag = neural?.backendLabel ?: "CPU Voiced MFCC+F0"

        val turns = mutableListOf<DiarizedTurn>()
        try {
            for ((s, e) in spans) {
                val spk = tracker.classifyTurn(pcm, s, e, allowNewSpeaker = true)
                val last = turns.lastOrNull()
                if (last != null && last.speakerIndex == spk && (s - last.endSample) < SAMPLE_RATE * 2) {
                    turns[turns.lastIndex] = last.copy(endSample = e)
                } else {
                    turns.add(DiarizedTurn(speakerIndex = spk, startSample = s, endSample = e))
                }
            }
        } finally {
            neural?.close()
        }
        return turns to engineTag
    }

    data class VoiceSample(
        val embedding: FloatArray,
        val medianF0Hz: Float,
        val voicedFrames: Int
    )

    /**
     * Session-adaptive online speaker classifier.
     * Uses 256-dim WeSpeaker ResNet34-LM neural embeddings on Hexagon V79 NPU / Oryon CPU when
     * available (cosine threshold 0.50), or voiced MFCC+F0 fallback (0.72), continuously learning
     * sharper speaker centroids over the session and supporting manual label merges.
     */
    class OnlineSpeakerTracker(
        private val neuralEngine: NeuralSpeakerEngine? = null,
        private val maxSpeakers: Int = 6
    ) {
        private val useNeural = neuralEngine != null
        // Calibrated on Razr 60 Ultra live telemetry (WeSpeaker ResNet293-LM, no AS-Norm):
        //   different speakers ~0.05..0.24, same speaker short clip ~0.38..0.48, long clip ~0.55..0.75
        private val matchThreshold: Float = if (useNeural) 0.33f else 0.72f
        private val minVoicedFramesForNewSpeaker: Int = if (useNeural) 22 else 10 // >= 2.2s to spawn new speaker

        private val centroids = mutableListOf<FloatArray>()
        private val meanF0 = mutableListOf<Float>()
        private val totalWeight = mutableListOf<Float>()
        private val mergedInto = mutableMapOf<Int, Int>()

        val engineName: String get() = neuralEngine?.backendLabel ?: "CPU Voiced MFCC+F0"

        private fun resolve(idx: Int): Int {
            var cur = idx
            while (mergedInto.containsKey(cur)) {
                cur = mergedInto[cur] ?: break
            }
            return cur
        }

        @Synchronized
        fun mergeSpeakers(fromIdx: Int, intoIdx: Int) {
            val src = resolve(fromIdx)
            val dst = resolve(intoIdx)
            if (src == dst || src !in centroids.indices || dst !in centroids.indices) return
            val wSrc = totalWeight[src]
            val wDst = totalWeight[dst]
            val wSum = (wSrc + wDst).coerceAtLeast(1f)
            val cDst = centroids[dst]
            val cSrc = centroids[src]
            for (k in cDst.indices) {
                cDst[k] = (cDst[k] * wDst + cSrc[k] * wSrc) / wSum
            }
            l2Normalize(cDst)
            meanF0[dst] = (meanF0[dst] * wDst + meanF0[src] * wSrc) / wSum
            totalWeight[dst] = wSum
            mergedInto[src] = dst
        }

        @Synchronized
        fun classifyTurn(
            pcm: ShortArray,
            start: Int = 0,
            end: Int = pcm.size,
            allowNewSpeaker: Boolean = true
        ): Int {
            if (end - start < FRAME_SAMPLES) return 0

            val neuralEmb = if (useNeural) neuralEngine?.extractEmbedding(pcm, start, end) else null
            val dspSample = if (neuralEmb == null) extractVoiceSample(pcm, start, end) else null

            val emb: FloatArray
            val f0Hz: Float
            val voicedCount: Int
            if (neuralEmb != null) {
                emb = neuralEmb
                f0Hz = 150f
                voicedCount = ((end - start) / 1600).coerceAtLeast(1) // 1 unit = 100ms
            } else if (dspSample != null && dspSample.voicedFrames > 0) {
                emb = dspSample.embedding
                f0Hz = dspSample.medianF0Hz
                voicedCount = dspSample.voicedFrames
            } else {
                return if (centroids.isEmpty()) 0 else resolve(0)
            }

            // Guard against dimension mismatch if neural failed mid-session
            if (centroids.isNotEmpty() && centroids[0].size != emb.size) {
                return resolve(0)
            }

            val weight = voicedCount.toFloat().coerceIn(1f, 60f)
            if (centroids.isEmpty()) {
                centroids.add(emb.copyOf())
                meanF0.add(f0Hz)
                totalWeight.add(weight)
                Log.d("OrbVoice", "SpeakerTracker[$engineName]: init Speaker 1 dim=${emb.size}")
                return 0
            }

            var bestIdx = -1
            var bestScore = -2f
            var bestCos = -1f
            for (c in centroids.indices) {
                if (mergedInto.containsKey(c)) continue
                val cos = cosineSim(emb, centroids[c])
                val score = if (neuralEmb != null) {
                    cos
                } else {
                    val f0A = f0Hz.coerceIn(75f, 360f)
                    val f0B = meanF0[c].coerceIn(75f, 360f)
                    val semitones = kotlin.math.abs(12.0 * ln((f0A / f0B).toDouble()) / ln(2.0)).toFloat()
                    val pitchPenalty = ((semitones - 2.0f).coerceAtLeast(0f) * 0.045f).coerceAtMost(0.35f)
                    cos - pitchPenalty
                }
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = c
                    bestCos = cos
                }
            }

            val activeCount = centroids.indices.count { !mergedInto.containsKey(it) }
            val canSpawn = allowNewSpeaker &&
                voicedCount >= minVoicedFramesForNewSpeaker &&
                activeCount < maxSpeakers &&
                bestScore < matchThreshold

            Log.d(
                "OrbVoice",
                "SpeakerTracker[$engineName]: best=S${bestIdx + 1} score=${"%.2f".format(bestScore)} cos=${"%.2f".format(bestCos)} thresh=$matchThreshold spawn=$canSpawn"
            )

            return if (!canSpawn && bestIdx >= 0) {
                val wOld = totalWeight[bestIdx]
                val effectiveNew = weight.coerceAtMost(wOld * 0.30f + 4f)
                val wSum = wOld + effectiveNew
                val cent = centroids[bestIdx]
                for (k in cent.indices) {
                    cent[k] = (cent[k] * wOld + emb[k] * effectiveNew) / wSum
                }
                l2Normalize(cent)
                meanF0[bestIdx] = (meanF0[bestIdx] * wOld + f0Hz * effectiveNew) / wSum
                totalWeight[bestIdx] = (wOld + weight).coerceAtMost(600f)
                resolve(bestIdx)
            } else if (canSpawn) {
                centroids.add(emb.copyOf())
                meanF0.add(f0Hz)
                totalWeight.add(weight)
                centroids.lastIndex
            } else {
                resolve(bestIdx.coerceAtLeast(0))
            }
        }

        @Synchronized
        fun seedKnownVoiceprints(profiles: List<VoiceprintProfile>): Map<Int, String> {
            val expectedDim = if (useNeural) 256 else 16
            val seededNames = mutableMapOf<Int, String>()
            for (p in profiles) {
                if (p.embedding.size != expectedDim) continue
                if (centroids.size >= maxSpeakers) break
                val vec = p.embedding.copyOf()
                l2Normalize(vec)
                centroids.add(vec)
                meanF0.add(150f)
                totalWeight.add(p.sampleWeight.coerceIn(10f, 300f))
                seededNames[centroids.lastIndex] = p.name
            }
            if (seededNames.isNotEmpty()) {
                Log.i("OrbVoice", "Seeded ${seededNames.size} known voiceprints: ${seededNames.values}")
            }
            return seededNames
        }

        @Synchronized
        fun getCentroid(speakerIdx: Int): FloatArray? {
            val resolved = resolve(speakerIdx)
            return if (resolved in centroids.indices) centroids[resolved].copyOf() else null
        }

        @Synchronized
        fun speakerCount(): Int = centroids.indices.count { !mergedInto.containsKey(it) }.coerceAtLeast(1)
    }

    private fun detectSpeechSpans(pcm: ShortArray): List<Pair<Int, Int>> {
        val numFrames = pcm.size / FRAME_SAMPLES
        if (numFrames <= 0) return emptyList()

        val energies = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            var sumSq = 0.0
            val base = f * FRAME_SAMPLES
            for (i in 0 until FRAME_SAMPLES) {
                val v = pcm[base + i] / 32768.0
                sumSq += v * v
            }
            energies[f] = sqrt(sumSq / FRAME_SAMPLES).toFloat()
        }

        val sorted = energies.sortedArray()
        val noiseFloor = sorted[(numFrames * 0.2f).toInt().coerceIn(0, numFrames - 1)]
        val speechThresh = (noiseFloor * 2.6f).coerceAtLeast(0.012f)

        val active = BooleanArray(numFrames) { energies[it] >= speechThresh }
        val spans = mutableListOf<Pair<Int, Int>>()
        var inSpeech = false
        var startFrame = 0
        var silenceRun = 0
        val maxPauseFrames = 20
        val minSpeechFrames = 14

        for (f in 0 until numFrames) {
            if (active[f]) {
                if (!inSpeech) {
                    inSpeech = true
                    startFrame = (f - 2).coerceAtLeast(0)
                }
                silenceRun = 0
            } else if (inSpeech) {
                silenceRun++
                if (silenceRun >= maxPauseFrames) {
                    val endFrame = (f - silenceRun + 3).coerceAtMost(numFrames)
                    if (endFrame - startFrame >= minSpeechFrames) {
                        spans.add((startFrame * FRAME_SAMPLES) to (endFrame * FRAME_SAMPLES).coerceAtMost(pcm.size))
                    }
                    inSpeech = false
                    silenceRun = 0
                }
            }
        }
        if (inSpeech && numFrames - startFrame >= minSpeechFrames) {
            spans.add((startFrame * FRAME_SAMPLES) to pcm.size)
        }
        return spans
    }

    private fun extractVoiceSample(pcm: ShortArray, start: Int, end: Int): VoiceSample {
        val safeStart = start.coerceIn(0, pcm.size)
        val safeEnd = end.coerceIn(safeStart, pcm.size)
        val len = safeEnd - safeStart
        val frameLen = 640
        val hop = 400
        if (len < frameLen) return VoiceSample(FloatArray(16), 150f, 0)

        val freqs = floatArrayOf(
            150f, 220f, 300f, 400f, 520f, 680f, 860f, 1080f,
            1320f, 1600f, 1920f, 2280f, 2680f, 3100f, 3550f, 3950f
        )
        val goertzelCoeffs = DoubleArray(freqs.size) { b -> 2.0 * cos(2.0 * PI * freqs[b] / SAMPLE_RATE) }
        val bandAccum = DoubleArray(freqs.size)
        val f0List = ArrayList<Float>(32)
        var zcrAccum = 0.0
        var voicedCount = 0

        val totalFrames = ((len - frameLen) / hop) + 1
        val stepFrames = (totalFrames / 48).coerceAtLeast(1)
        var fIdx = 0
        while (fIdx < totalFrames) {
            val pos = safeStart + fIdx * hop
            fIdx += stepFrames

            var energy = 0.0
            var zcr = 0
            for (i in 0 until frameLen) {
                val s = pcm[pos + i] / 32768.0
                energy += s * s
                if (i > 0 && ((pcm[pos + i] >= 0) != (pcm[pos + i - 1] >= 0))) zcr++
            }
            val rms = sqrt(energy / frameLen)
            val zcrRate = zcr.toDouble() / frameLen
            if (rms < 0.014 || zcrRate > 0.24) continue

            var bestLag = 100
            var bestCorr = 0.0
            for (lag in 47..200 step 2) {
                var corr = 0.0
                var normA = 0.0
                var normB = 0.0
                val lim = frameLen - lag
                var i = 0
                while (i < lim) {
                    val a = pcm[pos + i].toDouble()
                    val b = pcm[pos + i + lag].toDouble()
                    corr += a * b
                    normA += a * a
                    normB += b * b
                    i += 2
                }
                val r = corr / sqrt(normA * normB).coerceAtLeast(1.0)
                if (r > bestCorr) {
                    bestCorr = r
                    bestLag = lag
                }
            }
            if (bestCorr < 0.30) continue

            voicedCount++
            f0List.add(SAMPLE_RATE.toFloat() / bestLag.toFloat())
            zcrAccum += zcrRate

            val frameBands = DoubleArray(freqs.size)
            var bandMean = 0.0
            for (b in freqs.indices) {
                val coeff = goertzelCoeffs[b]
                var q1 = 0.0
                var q2 = 0.0
                for (i in 0 until frameLen) {
                    val x = pcm[pos + i] / 32768.0
                    val q0 = coeff * q1 - q2 + x
                    q2 = q1
                    q1 = q0
                }
                val logP = ln((q1 * q1 + q2 * q2 - q1 * q2 * coeff).coerceAtLeast(1e-9))
                frameBands[b] = logP
                bandMean += logP
            }
            bandMean /= freqs.size
            for (b in freqs.indices) bandAccum[b] += (frameBands[b] - bandMean)
        }

        if (voicedCount == 0) return VoiceSample(FloatArray(16), 150f, 0)
        f0List.sort()
        val medianF0 = f0List[f0List.size / 2]
        val avgBands = DoubleArray(freqs.size) { b -> bandAccum[b] / voicedCount }
        val feat = FloatArray(16)
        val m = freqs.size
        for (k in 1..13) {
            var sum = 0.0
            for (b in 0 until m) sum += avgBands[b] * cos(PI * k * (b + 0.5) / m)
            val weight = if (k == 1) 0.45 else if (k <= 10) 1.15 else 0.85
            feat[k - 1] = (sum * weight).toFloat()
        }
        val logF0 = (ln(medianF0.coerceIn(75f, 360f).toDouble()) - ln(150.0)).toFloat()
        feat[13] = logF0 * 2.2f
        feat[14] = ((zcrAccum / voicedCount) - 0.10).toFloat() * 4.0f
        feat[15] = ((avgBands[1] + avgBands[2] + avgBands[3]) - (avgBands[10] + avgBands[11] + avgBands[12])).toFloat() * 0.25f

        l2Normalize(feat)
        return VoiceSample(feat, medianF0, voicedCount)
    }

    private fun l2Normalize(v: FloatArray) {
        var norm = 0f
        for (x in v) norm += x * x
        val d = sqrt(norm)
        if (d > 1e-6f) {
            for (i in v.indices) v[i] /= d
        }
    }

    private fun cosineSim(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) dot += a[i] * b[i]
        return dot
    }
}
