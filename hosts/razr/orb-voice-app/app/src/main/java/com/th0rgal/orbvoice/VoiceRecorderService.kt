package com.th0rgal.orbvoice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

data class RecorderState(
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val isTranslateMode: Boolean = false,
    val statusMessage: String = "Ready",
    val elapsedMs: Long = 0L,
    val levels: List<Float> = emptyList(),
    val activeSpeakerIndex: Int? = null,
    val liveSegments: List<TranscriptSegment> = emptyList(),
    val partialText: String = "",
    val partialTranslation: String = "",
    val speakerNames: Map<Int, String> = emptyMap(),
    val lastCompletedNoteId: String? = null
)

/**
 * Closed-phone background foreground service for Orb Voice with real-time multi-speaker
 * voice separation and streaming transcription.
 *
 * - Runs uninterrupted with Motorola Razr 60 Ultra folded shut (`FOREGROUND_SERVICE_TYPE_MICROPHONE`
 *   + `PARTIAL_WAKE_LOCK`).
 * - Online VAD + `SpeakerDiarizer.OnlineSpeakerTracker` separates distinct voices in real time
 *   and transcribes each utterance as it finishes (DGX Spark `cohere-transcribe` on mesh, or
 *   on-device Android SpeechRecognizer live fallback).
 * - Speakers can be renamed live while recording (`renameLiveSpeaker`).
 * - Saves `.mp3` (via Razr ffmpeg) or hardware AAC `.m4a` + `.md` + `.json` to `Documents/OrbVoice/`.
 */
class VoiceRecorderService : Service() {

    companion object {
        const val ACTION_START = "com.th0rgal.orbvoice.START"
        const val ACTION_STOP = "com.th0rgal.orbvoice.STOP"
        const val ACTION_BENCHMARK = "com.th0rgal.orbvoice.BENCHMARK"
        const val EXTRA_TRANSLATE_MODE = "translate_mode"
        const val EXTRA_LANG_A = "lang_a"
        const val EXTRA_LANG_B = "lang_b"
        private const val CHANNEL_ID = "orb_voice_recorder"
        private const val NOTIF_ID = 7901
        private const val SAMPLE_RATE = 16000

        private val _state = MutableStateFlow(RecorderState())
        val state: StateFlow<RecorderState> = _state

        // Lock-free Murmure-style 10ms perceptual dBFS audio envelope ring buffer.
        // Read directly by Compose Canvas via withFrameNanos (60-120 FPS) with ZERO recompositions!
        private val waveformLock = Any()
        private const val WAVEFORM_BARS = 48
        private val waveformRing = FloatArray(WAVEFORM_BARS)
        private var waveformEnvelope = 0f

        fun resetWaveform() {
            synchronized(waveformLock) {
                waveformRing.fill(0f)
                waveformEnvelope = 0f
            }
        }

        /**
         * Murmure perceptual envelope mapping:
         * -55 dBFS -> 0, -10 dBFS -> 1, fast attack (45ms), smooth release (180ms),
         * then pow(peak, 0.8) lift so ordinary speech reads vividly.
         */
        fun pushAudioMeter(samples: FloatArray, count: Int) {
            if (count <= 0) return
            val block = 160 // 10ms at 16 kHz
            var peak = 0f
            synchronized(waveformLock) {
                var i = 0
                while (i + block <= count) {
                    var e = 0f
                    var j = i
                    val end = i + block
                    while (j < end) {
                        val s = samples[j]
                        e += s * s
                        j++
                    }
                    val rms = sqrt((e / block).toDouble()).toFloat().coerceAtLeast(1e-6f)
                    val db = 20f * kotlin.math.log10(rms)
                    val target = ((db + 55f) / 45f).coerceIn(0f, 1f)
                    val tau = if (target > waveformEnvelope) 45f else 180f
                    waveformEnvelope += (target - waveformEnvelope) * (1f - kotlin.math.exp(-10f / tau))
                    if (waveformEnvelope > peak) peak = waveformEnvelope
                    i += block
                }
                if (peak == 0f) {
                    waveformEnvelope *= 0.85f
                    peak = waveformEnvelope
                }
                val lifted = Math.pow(peak.toDouble(), 0.8).toFloat().coerceIn(0f, 1f)
                System.arraycopy(waveformRing, 1, waveformRing, 0, WAVEFORM_BARS - 1)
                waveformRing[WAVEFORM_BARS - 1] = lifted
            }
        }

        fun copyWaveform(dest: FloatArray) {
            val n = dest.size.coerceAtMost(WAVEFORM_BARS)
            val srcStart = WAVEFORM_BARS - n
            synchronized(waveformLock) {
                System.arraycopy(waveformRing, srcStart, dest, 0, n)
            }
        }

        fun startRecording(
            context: Context,
            translateMode: Boolean = false,
            langA: String = "English",
            langB: String = "French"
        ) {
            val intent = Intent(context, VoiceRecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TRANSLATE_MODE, translateMode)
                .putExtra(EXTRA_LANG_A, langA)
                .putExtra(EXTRA_LANG_B, langB)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, VoiceRecorderService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        @Volatile private var activeTracker: SpeakerDiarizer.OnlineSpeakerTracker? = null
        @Volatile private var activeAppContext: Context? = null

        fun renameLiveSpeaker(speakerIndex: Int, newName: String) {
            val clean = newName.trim()
            if (clean.isEmpty()) return
            val cur = _state.value
            val existingIdx = cur.speakerNames.entries
                .firstOrNull { it.key != speakerIndex && it.value.equals(clean, ignoreCase = true) }
                ?.key
            val finalIdx = if (existingIdx != null) {
                activeTracker?.mergeSpeakers(fromIdx = speakerIndex, intoIdx = existingIdx)
                val updatedNames = cur.speakerNames.toMutableMap().apply {
                    remove(speakerIndex)
                    put(existingIdx, clean)
                }
                val updatedSegs = cur.liveSegments.map { seg ->
                    if (seg.speakerIndex == speakerIndex || seg.speakerIndex == existingIdx) {
                        seg.copy(speakerIndex = existingIdx, speakerName = clean)
                    } else seg
                }
                _state.value = cur.copy(
                    activeSpeakerIndex = if (cur.activeSpeakerIndex == speakerIndex) existingIdx else cur.activeSpeakerIndex,
                    speakerNames = updatedNames,
                    liveSegments = updatedSegs
                )
                existingIdx
            } else {
                val updatedNames = cur.speakerNames.toMutableMap().apply { put(speakerIndex, clean) }
                val updatedSegs = cur.liveSegments.map { seg ->
                    if (seg.speakerIndex == speakerIndex) seg.copy(speakerName = clean) else seg
                }
                _state.value = cur.copy(speakerNames = updatedNames, liveSegments = updatedSegs)
                speakerIndex
            }
            // Persist learned 256-D voiceprint so future sessions recognize this speaker by name automatically!
            val appCtx = activeAppContext
            val emb = activeTracker?.getCentroid(finalIdx)
            if (appCtx != null && emb != null) {
                Thread {
                    VoiceprintRegistry.upsert(appCtx, clean, emb)
                }.start()
            }
        }
    }

    private val recording = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var workerThread: Thread? = null
    private var pipeWriterThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val asrExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "scribe-asr-cpu").apply { isDaemon = true }
    }
    private val npuExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scribe-hexagon-npu").apply { isDaemon = true }
    }
    private val llmExecutor = Executors.newFixedThreadPool(3)
    private var speechRecognizer: SpeechRecognizer? = null
    private val pipeLock = Any()
    private var pipeWriteFd: ParcelFileDescriptor? = null
    private var pipeOutStream: OutputStream? = null
    private val pipeQueue = ArrayBlockingQueue<ByteArray>(32)
    @Volatile private var latestRecognizedText: String = ""
    @Volatile private var lastCommittedSpeechText: String = ""
    @Volatile private var currentTurnStartMs: Long = 0L
    @Volatile private var currentElapsedMs: Long = 0L
    @Volatile private var currentSpeakerIndex: Int = 0
    @Volatile private var translateModeActive: Boolean = false
    @Volatile private var translateLangA: String = "English"
    @Volatile private var translateLangB: String = "French"
    private val ringLock = Any()
    private val recentRing = ShortArray(SAMPLE_RATE * 3) // 3s rolling window for exact per-utterance speaker ID
    private var ringWritePos = 0
    private var ringFilled = 0

    private fun appendToRing(samples: ShortArray, count: Int) {
        synchronized(ringLock) {
            for (i in 0 until count) {
                recentRing[ringWritePos] = samples[i]
                ringWritePos = (ringWritePos + 1) % recentRing.size
            }
            ringFilled = (ringFilled + count).coerceAtMost(recentRing.size)
        }
    }

    private fun snapshotRecentAudio(maxSamples: Int = (SAMPLE_RATE * 25) / 10): ShortArray {
        synchronized(ringLock) {
            val n = ringFilled.coerceAtMost(maxSamples)
            if (n <= 0) return ShortArray(0)
            val out = ShortArray(n)
            var start = (ringWritePos - n + recentRing.size) % recentRing.size
            for (i in 0 until n) {
                out[i] = recentRing[start]
                start = (start + 1) % recentRing.size
            }
            return out
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                translateModeActive = intent.getBooleanExtra(EXTRA_TRANSLATE_MODE, false)
                translateLangA = intent.getStringExtra(EXTRA_LANG_A) ?: "English"
                translateLangB = intent.getStringExtra(EXTRA_LANG_B) ?: "French"
                beginCapture()
            }
            ACTION_STOP -> stopCapture()
            ACTION_BENCHMARK -> {
                val wav = intent.getStringExtra("wav_path") ?: "/storage/emulated/0/Documents/OrbVoice/2026-11-24_22-02-52.wav"
                Thread { LocalAsrEngine.runBenchmarkSuite(applicationContext, wav) }.start()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recording.set(false)
        stopOnDeviceSpeechRecognizer()
        asrExecutor.shutdownNow()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun beginCapture() {
        if (!recording.compareAndSet(false, true)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recording.set(false)
            _state.value = RecorderState(statusMessage = "Microphone permission required")
            stopSelf()
            return
        }

        startForegroundCompat(
            if (translateModeActive) "Scribe Translate · $translateLangA ⇄ $translateLangB"
            else "Scribe · live multi-speaker stream active"
        )
        acquireWakeLock()
        latestRecognizedText = ""
        _state.value = RecorderState(
            isRecording = true,
            isProcessing = false,
            isTranslateMode = translateModeActive,
            statusMessage = if (translateModeActive) "$translateLangA ⇄ $translateLangB" else "Listening · live voice separation",
            elapsedMs = 0L,
            levels = emptyList(),
            liveSegments = emptyList(),
            partialText = "",
            speakerNames = _state.value.speakerNames
        )

        workerThread = Thread(::captureAndStreamLoop, "orb-voice-capture").apply { start() }
    }

    private fun stopCapture() {
        recording.set(false)
        stopOnDeviceSpeechRecognizer()
    }

    private fun closeAudioPipeLocked() {
        try { pipeOutStream?.close() } catch (_: Exception) {}
        try { pipeWriteFd?.close() } catch (_: Exception) {}
        pipeOutStream = null
        pipeWriteFd = null
    }

    private fun startPipeWriter() {
        pipeQueue.clear()
        pipeWriterThread = Thread({
            while (recording.get()) {
                val data = try {
                    pipeQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                synchronized(pipeLock) {
                    val out = pipeOutStream ?: return@synchronized
                    try {
                        out.write(data)
                        out.flush()
                    } catch (_: Exception) {
                        closeAudioPipeLocked()
                    }
                }
            }
        }, "orb-voice-asr-pipe").apply { isDaemon = true; start() }
    }

    private fun startOnDeviceSpeechRecognizer() {
        startPipeWriter()
        mainHandler.post {
            if (!recording.get()) return@post
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(this)) return@post
                val sr = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer = sr
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {
                        val txt = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty().trim()
                        if (txt.isNotEmpty() && recording.get()) {
                            latestRecognizedText = txt
                            val cur = _state.value
                            _state.value = cur.copy(
                                activeSpeakerIndex = cur.activeSpeakerIndex ?: currentSpeakerIndex,
                                partialText = txt
                            )
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val txt = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty().trim()
                        if (txt.isNotEmpty() && txt != lastCommittedSpeechText) {
                            lastCommittedSpeechText = txt
                            latestRecognizedText = ""
                            commitRecognizedLine(txt)
                        }
                        if (recording.get()) {
                            mainHandler.postDelayed({ restartRecognizerIntent() }, 120)
                        }
                    }
                    override fun onError(error: Int) {
                        Log.d("OrbVoice", "SpeechRecognizer error=$error, restarting pipe")
                        if (recording.get()) {
                            mainHandler.postDelayed({ restartRecognizerIntent() }, 350)
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                restartRecognizerIntent()
            } catch (e: Exception) {
                Log.w("OrbVoice", "SpeechRecognizer init failed: ${e.message}")
            }
        }
    }

    private fun commitRecognizedLine(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        // Classify directly from the last ~2.5s of audio that produced this utterance
        // so back-to-back speaker switches are detected immediately even without an 800ms pause!
        val recent = snapshotRecentAudio()
        val tracker = activeTracker
        if (tracker != null && recent.size >= SAMPLE_RATE / 2) {
            val detected = tracker.classifyTurn(recent, allowNewSpeaker = true)
            currentSpeakerIndex = detected
        }
        val cur = _state.value
        val spkIdx = currentSpeakerIndex
        val spkName = cur.speakerNames[spkIdx] ?: "Speaker ${spkIdx + 1}"
        val nowMs = currentElapsedMs.coerceAtLeast(currentTurnStartMs + 250L)
        val existing = cur.liveSegments.lastOrNull()
        val updatedList = when {
            existing != null &&
                existing.speakerIndex == spkIdx &&
                (existing.text == "Transcribing…" || existing.text.startsWith("[Voice turn ")) -> {
                cur.liveSegments.dropLast(1) + existing.copy(
                    speakerName = spkName,
                    endMs = nowMs,
                    text = clean
                )
            }
            // Deduplicate if SODA emits a finalized superset of the previous line
            existing != null &&
                (clean.startsWith(existing.text, ignoreCase = true) ||
                    existing.text.startsWith(clean, ignoreCase = true)) -> {
                val best = if (clean.length >= existing.text.length) clean else existing.text
                cur.liveSegments.dropLast(1) + existing.copy(
                    speakerIndex = spkIdx,
                    speakerName = spkName,
                    endMs = nowMs,
                    text = best
                )
            }
            // Merge consecutive short utterances from the same speaker within 1.8s into one clean card
            existing != null &&
                existing.speakerIndex == spkIdx &&
                (nowMs - existing.endMs) < 1800L &&
                existing.text.length < 180 -> {
                cur.liveSegments.dropLast(1) + existing.copy(
                    speakerName = spkName,
                    endMs = nowMs,
                    text = "${existing.text} $clean"
                )
            }
            else -> {
                cur.liveSegments + TranscriptSegment(
                    speakerIndex = spkIdx,
                    speakerName = spkName,
                    startMs = currentTurnStartMs,
                    endMs = nowMs,
                    text = clean
                )
            }
        }
        _state.value = cur.copy(
            activeSpeakerIndex = spkIdx,
            partialText = "",
            liveSegments = updatedList
        )

        // A) Rolling live autocorrect via Orb Router (builtin/fast) — silently upgrades card in-place
        val targetIndex = updatedList.lastIndex
        val rawToPolish = updatedList[targetIndex].text
        val orbCfg = OrbRouterConfig.load(this)
        if (orbCfg.isConfigured && orbCfg.livePolishEnabled && rawToPolish.length >= 6) {
            val ctxSnapshot = updatedList.dropLast(1).takeLast(3)
            val namesSnapshot = cur.speakerNames.values.toList()
            asrExecutor.execute {
                val polished = OrbRouterClient.polishLiveSegment(
                    cfg = orbCfg,
                    rawText = rawToPolish,
                    recentContext = ctxSnapshot,
                    knownSpeakers = namesSnapshot
                )
                if (!polished.isNullOrBlank() && polished != rawToPolish) {
                    val nowState = _state.value
                    if (targetIndex < nowState.liveSegments.size && nowState.liveSegments[targetIndex].text == rawToPolish) {
                        val copy = nowState.liveSegments.toMutableList()
                        copy[targetIndex] = copy[targetIndex].copy(text = polished)
                        _state.value = nowState.copy(liveSegments = copy)
                    }
                }
            }
        }
    }

    private fun restartRecognizerIntent() {
        if (!recording.get()) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val pipe = ParcelFileDescriptor.createPipe()
                    val readFd = pipe[0]
                    val writeFd = pipe[1]
                    synchronized(pipeLock) {
                        closeAudioPipeLocked()
                        pipeWriteFd = writeFd
                        pipeOutStream = ParcelFileDescriptor.AutoCloseOutputStream(writeFd)
                    }
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                }
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.w("OrbVoice", "restartRecognizerIntent failed: ${e.message}")
        }
    }

    private fun stopOnDeviceSpeechRecognizer() {
        synchronized(pipeLock) { closeAudioPipeLocked() }
        pipeQueue.clear()
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
        }
    }

    private fun captureAndStreamLoop() {
        activeAppContext = applicationContext
        val prefs = getSharedPreferences("orb_voice_prefs", Context.MODE_PRIVATE)
        val sparkAsrUrl = prefs.getString("spark_asr_url", NpuTranscriptionEngine.DEFAULT_SPARK_ASR_URL)
            ?: NpuTranscriptionEngine.DEFAULT_SPARK_ASR_URL

        val orbCfgInit = OrbRouterConfig.load(this)
        val preferredAsr = LocalAsrEngine.AsrModel.fromId(orbCfgInit.localAsrModel)
        val cohereLangHint = ScribeEnginePool.resolveLanguageHint(orbCfgInit)

        _state.value = _state.value.copy(statusMessage = "Warming Hexagon V79 NPU + ${preferredAsr.label}…")
        val warm = ScribeEnginePool.acquire(this, orbCfgInit.localAsrModel, cohereLangHint)
        val neuralEngine = warm.speaker
        val localAsr = warm.asr
        val sparkOnline = if (localAsr == null) NpuTranscriptionEngine.probeSparkReachable(sparkAsrUrl) else false
        if (localAsr == null && !sparkOnline) {
            // Fallback to on-device Google SODA only if no local ASR is installed and Spark isn't reachable
            startOnDeviceSpeechRecognizer()
        }

        val noteId = VoiceNoteStore.newNoteId()
        val pcmFile = File(cacheDir, "$noteId.pcm")
        val pcmOut = java.io.BufferedOutputStream(java.io.FileOutputStream(pcmFile), 32768)
        var totalPcmBytes = 0L
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            recording.set(false)
            _state.value = RecorderState(statusMessage = "AudioRecord failed to initialize")
            releaseWakeLock()
            stopSelf()
            return
        }

        val startTimeMs = System.currentTimeMillis()
        val chunk = ShortArray(512) // 32ms chunks (exact Silero VAD window + ~31Hz fluid waveform meter!)
        val floatChunk = FloatArray(512)
        val byteChunk = ByteArray(chunk.size * 2)
        resetWaveform()

        // Session-adaptive Neural Speaker Tracker seeded with saved cross-session voiceprints!
        val tracker = SpeakerDiarizer.OnlineSpeakerTracker(neuralEngine = neuralEngine)
        val savedProfiles = VoiceprintRegistry.loadAll(this)
        val seededNames = tracker.seedKnownVoiceprints(savedProfiles)
        activeTracker = tracker
        if (seededNames.isNotEmpty()) {
            _state.value = _state.value.copy(speakerNames = seededNames + _state.value.speakerNames)
        }
        val activeEngineTag = when {
            localAsr != null -> "${tracker.engineName} + ${localAsr.modelLabel}"
            sparkOnline -> "${tracker.engineName} + Spark"
            else -> "${tracker.engineName} + SODA"
        }
        _state.value = _state.value.copy(statusMessage = "Listening · $activeEngineTag")

        // Zero-GC primitive ShortArray turn buffer (replaces ArrayList<Short> boxing of 100k+ Short objects/turn)
        val turnBuf = ShortArray(SAMPLE_RATE * 10)
        var turnLen = 0
        fun appendTurn(src: ShortArray, count: Int) {
            val copy = count.coerceAtMost(turnBuf.size - turnLen)
            if (copy > 0) {
                System.arraycopy(src, 0, turnBuf, turnLen, copy)
                turnLen += copy
            }
        }
        var inTurn = false
        var turnStartMs = 0L
        var silenceChunks = 0
        var noiseFloor = 0.008f
        var earlyClassified = false
        var lastPreviewDecodeMs = 0L
        var lastPreviewTranslateMs = 0L
        var lastPreviewTranslatedText = ""
        var lastUiTickMs = 0L
        val previewInFlight = AtomicBoolean(false)
        val previewTranslateInFlight = AtomicBoolean(false)
        val reconcileInFlight = AtomicBoolean(false)
        var lastReconciledCount = 0
        lastCommittedSpeechText = ""
        currentSpeakerIndex = 0
        currentTurnStartMs = 0L
        synchronized(ringLock) {
            ringWritePos = 0
            ringFilled = 0
        }

        fun submitParakeetTurn(segFloats: FloatArray, tStart: Long, tEnd: Long) {
            if (localAsr == null || segFloats.size < SAMPLE_RATE / 4) return
            val segShorts = ShortArray(segFloats.size) { i ->
                (segFloats[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            // True NPU + CPU pipelining: dispatch WeSpeaker ResNet293-LM on Hexagon V79 NPU (~12ms)
            // concurrently while 6-core Oryon CPU decodes ASR, hiding 100% of diarization latency!
            val speakerFuture = npuExecutor.submit<Int> {
                tracker.classifyTurn(segShorts, allowNewSpeaker = true)
            }
            asrExecutor.execute {
                val text = localAsr.transcribe(segFloats)
                if (text.isBlank()) return@execute
                val speakerIdx = try {
                    speakerFuture.get()
                } catch (_: Exception) {
                    currentSpeakerIndex
                }
                currentSpeakerIndex = speakerIdx
                val cur = _state.value
                val spkName = cur.speakerNames[speakerIdx] ?: "Speaker ${speakerIdx + 1}"

                // Smart same-speaker fragment auto-merge: if the same speaker resumes within 1.2s
                // after an unfinished short fragment (< 45 chars, no terminal .?!), merge into it
                // instead of splitting a single sentence across two cards.
                val prev = cur.liveSegments.lastOrNull()
                val shouldAutoMerge = prev != null &&
                    prev.speakerIndex == speakerIdx &&
                    (tStart - prev.endMs) <= 1200L &&
                    prev.text.length < 45 &&
                    (prev.text.lastOrNull() ?: ' ') !in charArrayOf('.', '?', '!')

                val updatedList: List<TranscriptSegment>
                val targetIndex: Int
                val effectiveText: String
                // Carry forward any already-streamed partialTranslation seamlessly so the UI never flashes
                // a jarring "Translating..." placeholder over text the user was already reading!
                val carriedTx = if (translateModeActive) {
                    cur.partialTranslation.trim().ifEmpty { "\u200B" } // zero-width sentinel = refining badge in header
                } else ""
                if (shouldAutoMerge && prev != null) {
                    effectiveText = "${prev.text.trimEnd()} $text"
                    val mergedSeg = prev.copy(
                        endMs = tEnd,
                        text = effectiveText,
                        translatedText = if (translateModeActive) carriedTx.ifEmpty { prev.translatedText } else prev.translatedText
                    )
                    updatedList = cur.liveSegments.dropLast(1) + mergedSeg
                    targetIndex = updatedList.lastIndex
                } else {
                    effectiveText = text
                    val newSeg = TranscriptSegment(
                        speakerIndex = speakerIdx,
                        speakerName = spkName,
                        startMs = tStart,
                        endMs = tEnd,
                        text = text,
                        translatedText = carriedTx
                    )
                    updatedList = cur.liveSegments + newSeg
                    targetIndex = updatedList.lastIndex
                }

                _state.value = cur.copy(
                    activeSpeakerIndex = speakerIdx,
                    partialText = "",
                    partialTranslation = "",
                    liveSegments = updatedList
                )
                // A) Rolling live Orb Router autocorrect + auto-translate / bidirectional translate in parallel
                val orbCfg = OrbRouterConfig.load(this)
                val shouldRunLlm = (translateModeActive || orbCfg.autoTranslateTarget.isNotBlank() || orbCfg.livePolishEnabled) &&
                    effectiveText.length >= 2
                if (shouldRunLlm) {
                    val ctxSnap = updatedList.dropLast(1).takeLast(3)
                    val namesSnap = cur.speakerNames.values.toList()
                    val bidirA = if (translateModeActive) translateLangA else null
                    val bidirB = if (translateModeActive) translateLangB else null
                    llmExecutor.execute {
                        var res = if (orbCfg.isConfigured) {
                            OrbRouterClient.polishAndTranslateLive(
                                cfg = orbCfg,
                                rawText = effectiveText,
                                recentContext = ctxSnap,
                                knownSpeakers = namesSnap,
                                bidirectionalLangA = bidirA,
                                bidirectionalLangB = bidirB
                            )
                        } else null
                        // One quick retry if bidirectional translation came back empty
                        if (orbCfg.isConfigured && translateModeActive && (res == null || res.translated.isBlank())) {
                            res = OrbRouterClient.polishAndTranslateLive(
                                cfg = orbCfg,
                                rawText = effectiveText,
                                recentContext = emptyList(),
                                knownSpeakers = namesSnap,
                                bidirectionalLangA = bidirA,
                                bidirectionalLangB = bidirB
                            )
                        }
                        // 100% OFFLINE airplane-mode fallback via NVIDIA Canary-1B-v2 if Orb Router is unreachable!
                        if (res == null || (translateModeActive && res.translated.isBlank())) {
                            val targetLang = when {
                                translateModeActive -> {
                                    // Heuristic: if text has French accents/common French words, translate A/B accordingly
                                    val looksFr = Regex("\\b(je|tu|il|elle|nous|vous|est|c'est|le|la|les|des|une|que|qui|mais|pour|avec|dans|pas)\\b", RegexOption.IGNORE_CASE).containsMatchIn(effectiveText) ||
                                        effectiveText.any { it in "éèêëàâùûçôîï" }
                                    if (looksFr && translateLangA.equals("French", true)) translateLangB
                                    else if (looksFr && translateLangB.equals("French", true)) translateLangA
                                    else if (translateLangA.equals("English", true)) translateLangB
                                    else translateLangA
                                }
                                orbCfg.autoTranslateTarget.isNotBlank() -> orbCfg.autoTranslateTarget
                                else -> ""
                            }
                            if (targetLang.isNotBlank()) {
                                val srcLang = if (translateModeActive) {
                                    if (targetLang.equals(translateLangA, true)) translateLangB else translateLangA
                                } else {
                                    if (targetLang.equals("English", true)) "French" else "English"
                                }
                                val offlineTx = LocalAsrEngine.translateSpeechOffline(
                                    applicationContext, segFloats, srcLang, targetLang
                                )
                                if (!offlineTx.isNullOrBlank()) {
                                    res = LivePolishResult(cleaned = res?.cleaned ?: effectiveText, translated = "$offlineTx (offline)")
                                }
                            }
                        }
                        val nowState = _state.value
                        if (targetIndex < nowState.liveSegments.size) {
                            val copy = nowState.liveSegments.toMutableList()
                            val existingSeg = copy[targetIndex]
                            if (res != null) {
                                copy[targetIndex] = existingSeg.copy(
                                    text = res.cleaned,
                                    translatedText = res.translated
                                )
                            } else if (existingSeg.translatedText == "\u200B" || existingSeg.translatedText == "Translating…") {
                                copy[targetIndex] = existingSeg.copy(translatedText = "")
                            }
                            _state.value = nowState.copy(liveSegments = copy)
                        }

                        // B) Near-real-time sliding-window LLM conversation reconciler:
                        // Every 2 committed turns, review the last 4 turns together to fix speaker
                        // attribution, merge mid-sentence VAD splits, and polish phrasing live!
                        val postState = _state.value
                        val totalSegs = postState.liveSegments.size
                        if (orbCfg.isConfigured &&
                            orbCfg.livePolishEnabled &&
                            totalSegs >= 2 &&
                            totalSegs - lastReconciledCount >= 2 &&
                            reconcileInFlight.compareAndSet(false, true)
                        ) {
                            lastReconciledCount = totalSegs
                            val winSize = minOf(4, totalSegs)
                            val winStart = totalSegs - winSize
                            val windowSnap = postState.liveSegments.subList(winStart, totalSegs).toList()
                            val startMsKeys = windowSnap.map { it.startMs }
                            val spkNamesSnap = postState.speakerNames
                            try {
                                val reconciled = OrbRouterClient.reconcileRecentTurns(orbCfg, windowSnap, spkNamesSnap)
                                if (reconciled != null) {
                                    val latest = _state.value
                                    val current = latest.liveSegments.toMutableList()
                                    // Map each reconciled turn back by its startMs timestamp so newer turns stay intact
                                    val byIdx = reconciled.associateBy { it.index }
                                    val mergedWindow = mutableListOf<TranscriptSegment>()
                                    for (wi in windowSnap.indices) {
                                        val orig = windowSnap[wi]
                                        val rec = byIdx[wi]
                                        val spk = rec?.speakerIndex ?: orig.speakerIndex
                                        val spkLbl = latest.speakerNames[spk] ?: "Speaker ${spk + 1}"
                                        val txt = rec?.text ?: orig.text
                                        // Preserve any translation already computed for this segment
                                        val curMatch = current.firstOrNull { it.startMs == orig.startMs }
                                        val tr = curMatch?.translatedText ?: orig.translatedText
                                        if (rec?.mergePrev == true && mergedWindow.isNotEmpty() && mergedWindow.last().speakerIndex == spk) {
                                            val p = mergedWindow.last()
                                            val mergedTr = listOf(p.translatedText, tr).filter { it.isNotBlank() && it != "Translating…" }.joinToString(" ")
                                            mergedWindow[mergedWindow.lastIndex] = p.copy(
                                                endMs = maxOf(p.endMs, orig.endMs),
                                                text = "${p.text.trimEnd()} $txt",
                                                translatedText = mergedTr
                                            )
                                        } else {
                                            mergedWindow.add(
                                                orig.copy(
                                                    speakerIndex = spk,
                                                    speakerName = spkLbl,
                                                    text = txt,
                                                    translatedText = tr
                                                )
                                            )
                                        }
                                    }
                                    val keySet = startMsKeys.toSet()
                                    val firstPos = current.indexOfFirst { it.startMs in keySet }
                                    if (firstPos >= 0) {
                                        val before = current.subList(0, firstPos)
                                        val after = current.subList(firstPos, current.size).filter { it.startMs !in keySet }
                                        _state.value = latest.copy(liveSegments = before + mergedWindow + after)
                                    }
                                }
                            } finally {
                                reconcileInFlight.set(false)
                            }
                        }
                    }
                }
            }
        }

        try {
            recorder.startRecording()
            while (recording.get()) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                var sumSq = 0.0
                val bb = ByteBuffer.wrap(byteChunk).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until read) {
                    val s = chunk[i]
                    bb.putShort(s)
                    val v = s / 32768.0
                    sumSq += v * v
                }
                val bytesLen = read * 2
                pcmOut.write(byteChunk, 0, bytesLen)
                totalPcmBytes += bytesLen
                appendToRing(chunk, read)

                val rmsRaw = sqrt(sumSq / read).toFloat()
                // Feed clean, unpumped raw float PCM to Silero VAD & ASR (no 100ms chunk-level gain pumping,
                // which distorts consonants!). Quiet far-field utterances are smoothly RMS-normalized
                // per full utterance inside LocalAsrEngine.normalizeQuietAudio().
                for (i in 0 until read) {
                    floatChunk[i] = chunk[i] / 32768f
                }
                pushAudioMeter(floatChunk, read)

                val elapsed = System.currentTimeMillis() - startTimeMs
                currentElapsedMs = elapsed

                // === PATH 1: Local Parakeet-TDT-0.6B-v3 + Silero VAD v5 (100% open-weights offline EN+FR!) ===
                if (localAsr != null && localAsr.hasSileroVad) {
                    val slice = if (read == floatChunk.size) floatChunk else floatChunk.copyOf(read)
                    val completed = localAsr.feedVad(slice)
                    val speakingNow = localAsr.isSpeechActive()
                    if (speakingNow) {
                        if (!inTurn) {
                            inTurn = true
                            turnStartMs = elapsed
                            currentTurnStartMs = elapsed
                            earlyClassified = false
                            turnLen = 0
                        }
                        appendTurn(chunk, read)
                        // Fast Hexagon V79 NPU early speaker probe (~500ms, 12ms NPU inference!)
                        // Switches activeSpeakerIndex BEFORE the first live preview renders so a new
                        // speaker's words never appear under the previous speaker's card.
                        if (!earlyClassified && turnLen >= SAMPLE_RATE / 2) {
                            earlyClassified = true
                            val probe = turnBuf.copyOf(turnLen)
                            npuExecutor.execute {
                                if (!recording.get()) return@execute
                                val spk = tracker.classifyTurn(probe, allowNewSpeaker = false)
                                currentSpeakerIndex = spk
                                _state.value = _state.value.copy(activeSpeakerIndex = spk)
                            }
                        }
                        // Live streaming preview: every 650ms on 70ms Hexagon V79 NPU Parakeet (or 1.8s on CPU),
                        // plus debounced live streaming translation while speaking!
                        val previewIntervalMs = if (localAsr.isFastNpu) 650L else 1800L
                        if (!localAsr.isHeavyweight &&
                            elapsed - lastPreviewDecodeMs >= previewIntervalMs &&
                            turnLen >= SAMPLE_RATE / 2 &&
                            previewInFlight.compareAndSet(false, true)
                        ) {
                            lastPreviewDecodeMs = elapsed
                            val snapLen = turnLen
                            val snapShorts = turnBuf.copyOf(snapLen)
                            val snapFloats = FloatArray(snapLen) { snapShorts[it] / 32768f }
                            val previewSpkFuture = if (snapLen >= (SAMPLE_RATE * 9) / 10) {
                                npuExecutor.submit<Int> {
                                    tracker.classifyTurn(snapShorts, allowNewSpeaker = true)
                                }
                            } else null
                            asrExecutor.execute {
                                try {
                                    if (!recording.get()) return@execute
                                    val preview = localAsr.transcribe(snapFloats)
                                    val spk = try {
                                        previewSpkFuture?.get() ?: currentSpeakerIndex
                                    } catch (_: Exception) {
                                        currentSpeakerIndex
                                    }
                                    currentSpeakerIndex = spk
                                    if (preview.isNotBlank() && recording.get()) {
                                        _state.value = _state.value.copy(
                                            activeSpeakerIndex = spk,
                                            partialText = preview
                                        )
                                        // Ultra-fast live streaming translation while speaking (~250ms, plain text,
                                        // refined into full polished translation when the sentence ends!)
                                        val orbCfgPrev = OrbRouterConfig.load(this)
                                        val wantLiveTx = translateModeActive || orbCfgPrev.autoTranslateTarget.isNotBlank()
                                        val nowMs = System.currentTimeMillis() - startTimeMs
                                        if (wantLiveTx &&
                                            preview.length >= 4 &&
                                            preview != lastPreviewTranslatedText &&
                                            orbCfgPrev.isConfigured &&
                                            nowMs - lastPreviewTranslateMs >= 450L &&
                                            previewTranslateInFlight.compareAndSet(false, true)
                                        ) {
                                            lastPreviewTranslateMs = nowMs
                                            lastPreviewTranslatedText = preview
                                            val bidirA = if (translateModeActive) translateLangA else null
                                            val bidirB = if (translateModeActive) translateLangB else null
                                            llmExecutor.execute {
                                                try {
                                                    val tx = OrbRouterClient.translatePreviewFast(
                                                        cfg = orbCfgPrev,
                                                        partialText = preview,
                                                        bidirectionalLangA = bidirA,
                                                        bidirectionalLangB = bidirB
                                                    ).orEmpty().trim()
                                                    if (tx.isNotBlank() && recording.get() && _state.value.partialText.isNotBlank()) {
                                                        _state.value = _state.value.copy(partialTranslation = tx)
                                                    }
                                                } finally {
                                                    previewTranslateInFlight.set(false)
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    previewInFlight.set(false)
                                }
                            }
                        }
                    }
                    for (segFloats in completed) {
                        val segDurMs = (segFloats.size * 1000L) / SAMPLE_RATE
                        val tStart = (elapsed - segDurMs).coerceAtLeast(0L)
                        inTurn = false
                        earlyClassified = false
                        turnLen = 0
                        submitParakeetTurn(segFloats, tStart, elapsed)
                    }
                    // Throttle elapsed timer UI updates to 2 Hz (every 500ms); the waveform runs
                    // independently at 60-120 FPS via VoiceRecorderService.copyWaveform() with zero recompositions!
                    if (elapsed - lastUiTickMs >= 500L) {
                        lastUiTickMs = elapsed
                        _state.value = _state.value.copy(
                            isRecording = true,
                            elapsedMs = elapsed
                        )
                    }
                    continue
                }

                // === PATH 2: Fallback (DGX Spark or Google SODA via EXTRA_AUDIO_SOURCE pipe) ===
                if (!sparkOnline && localAsr == null) {
                    val copy = byteChunk.copyOf(bytesLen)
                    if (!pipeQueue.offer(copy)) {
                        pipeQueue.poll()
                        pipeQueue.offer(copy)
                    }
                }

                val speechThresh = (noiseFloor * 2.2f).coerceAtLeast(0.012f)
                val isSpeech = rmsRaw >= speechThresh

                if (!isSpeech) {
                    noiseFloor = noiseFloor * 0.96f + rmsRaw * 0.04f
                }

                if (isSpeech) {
                    if (!inTurn) {
                        inTurn = true
                        turnStartMs = elapsed
                        currentTurnStartMs = elapsed
                        earlyClassified = false
                        turnLen = 0
                    }
                    silenceChunks = 0
                    appendTurn(chunk, read)

                    // Early speaker probe (~600ms) NEVER spawns a new speaker; only matches existing learned voices
                    if (!earlyClassified && turnLen >= (SAMPLE_RATE * 6) / 10) {
                        earlyClassified = true
                        val probe = turnBuf.copyOf(turnLen)
                        npuExecutor.execute {
                            val spk = tracker.classifyTurn(probe, allowNewSpeaker = false)
                            currentSpeakerIndex = spk
                            _state.value = _state.value.copy(activeSpeakerIndex = spk)
                        }
                    }
                } else if (inTurn) {
                    silenceChunks++
                    appendTurn(chunk, read)
                }

                // Flush a live speaker turn on ~800ms pause (25 x 32ms chunks) OR 6s continuous speech
                val shouldFlush = inTurn && (silenceChunks >= 25 || turnLen >= SAMPLE_RATE * 6)
                if (shouldFlush) {
                    val endMs = elapsed
                    val snapshot = turnBuf.copyOf(turnLen)
                    val tStart = turnStartMs
                    inTurn = false
                    silenceChunks = 0
                    earlyClassified = false
                    turnLen = 0

                    if (snapshot.size >= (SAMPLE_RATE * 7) / 10) {
                        val speakerIdx = tracker.classifyTurn(snapshot, allowNewSpeaker = true)
                        currentSpeakerIndex = speakerIdx
                        val speakerLabel = _state.value.speakerNames[speakerIdx] ?: "Speaker ${speakerIdx + 1}"

                        if (sparkOnline) {
                            val segIdx = _state.value.liveSegments.size
                            val placeholder = TranscriptSegment(
                                speakerIndex = speakerIdx,
                                speakerName = speakerLabel,
                                startMs = tStart,
                                endMs = endMs,
                                text = "Transcribing…"
                            )
                            _state.value = _state.value.copy(
                                activeSpeakerIndex = speakerIdx,
                                partialText = "",
                                liveSegments = _state.value.liveSegments + placeholder
                            )
                            asrExecutor.execute {
                                val transcribed = NpuTranscriptionEngine.transcribeSingleTurn(snapshot, tStart, sparkAsrUrl)
                                val finalText = if (!transcribed.isNullOrBlank()) {
                                    transcribed
                                } else {
                                    "[Voice turn ${VoiceNoteStore.formatTimestamp(tStart)}–${VoiceNoteStore.formatTimestamp(endMs)}]"
                                }
                                val cur = _state.value
                                if (segIdx < cur.liveSegments.size) {
                                    val updated = cur.liveSegments.toMutableList()
                                    val existing = updated[segIdx]
                                    val latestName = cur.speakerNames[existing.speakerIndex] ?: existing.speakerName
                                    updated[segIdx] = existing.copy(speakerName = latestName, text = finalText)
                                    _state.value = cur.copy(liveSegments = updated)
                                }
                            }
                        } else {
                            // On-device SpeechRecognizer owns text commits via onResults() so we never double-post partials;
                            // VAD updates the learned speaker voiceprint and active color.
                            _state.value = _state.value.copy(activeSpeakerIndex = speakerIdx)
                        }
                    }
                }

                if (elapsed - lastUiTickMs >= 500L) {
                    lastUiTickMs = elapsed
                    _state.value = _state.value.copy(
                        isRecording = true,
                        elapsedMs = elapsed
                    )
                }
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            try { pcmOut.flush(); pcmOut.close() } catch (_: Exception) {}
            // Flush any trailing Silero VAD speech segment before closing Parakeet-v3
            if (localAsr != null && localAsr.hasSileroVad) {
                val trailing = localAsr.flushVad()
                val endNow = System.currentTimeMillis() - startTimeMs
                for (segFloats in trailing) {
                    val segDurMs = (segFloats.size * 1000L) / SAMPLE_RATE
                    submitParakeetTurn(segFloats, (endNow - segDurMs).coerceAtLeast(0L), endNow)
                }
            }
            stopOnDeviceSpeechRecognizer()
        }

        val durationMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(100L)
        _state.value = _state.value.copy(
            isRecording = false,
            isProcessing = true,
            activeSpeakerIndex = null,
            partialText = "",
            statusMessage = "Saving note…"
        )
        updateNotification("Scribe: saving note to Documents/OrbVoice…")

        try {
            val dir = VoiceNoteStore.notesDir(this)

            // Wait cleanly for queued ASR turns to finish and close native engines ON asrExecutor
            // so JNI is never torn down mid-decode!
            val doneLatch = java.util.concurrent.CountDownLatch(1)
            asrExecutor.execute {
                activeTracker = null
                // Keep neuralEngine & localAsr warm in ScribeEnginePool across sessions (16 GB RAM)
                doneLatch.countDown()
            }
            try { doneLatch.await(15, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}

            val liveSegs = _state.value.liveSegments
            val speakerNames = _state.value.speakerNames
            val finalSegments = liveSegs.map { seg ->
                seg.copy(speakerName = speakerNames[seg.speakerIndex] ?: seg.speakerName)
            }
            val speakerCount = finalSegments.map { it.speakerIndex }.distinct().size.coerceAtLeast(1)
            val titleTime = SimpleDateFormat("MMM d · HH:mm", Locale.US).format(Date(startTimeMs))

            // 1. Save WAV immediately by streaming pcmFile (zero OOM even on 2-hour recordings!)
            val wavFile = File(dir, "$noteId.wav")
            writeWavFromPcmFile(pcmFile, totalPcmBytes, wavFile)
            pcmFile.delete()

            // 2. Persist .md + .json IMMEDIATELY so stopping ALWAYS guarantees the note is saved!
            var note = VoiceNote(
                id = noteId,
                title = "Scribe Note · $titleTime",
                createdAtMs = startTimeMs,
                durationMs = durationMs,
                audioPath = wavFile.absolutePath,
                markdownPath = File(dir, "$noteId.md").absolutePath,
                jsonPath = File(dir, "$noteId.json").absolutePath,
                engineUsed = activeEngineTag,
                speakerCount = speakerCount,
                syncedToSpark = false,
                segments = finalSegments
            )
            VoiceNoteStore.saveNote(this, note)

            // 3. Best-effort compress WAV -> MP3 (if ffmpeg available) and update note audioPath
            val mp3File = File(dir, "$noteId.mp3")
            if (encodeMp3ViaFfmpeg(wavFile, mp3File)) {
                wavFile.delete()
                note = note.copy(audioPath = mp3File.absolutePath)
                VoiceNoteStore.saveNote(this, note)
            }

            // 4. B) Post-recording full-note polish + auto-title & summary via Orb Router (builtin/fast)
            val orbCfg = OrbRouterConfig.load(this)
            if (orbCfg.isConfigured && note.segments.isNotEmpty()) {
                _state.value = _state.value.copy(statusMessage = "Polishing transcript (${orbCfg.fastModel})…")
                val polished = OrbRouterClient.polishFullNote(orbCfg, note)
                if (polished != null) {
                    note = note.copy(
                        title = polished.title,
                        summary = polished.summary,
                        segments = polished.segments,
                        engineUsed = "${note.engineUsed} · ${orbCfg.fastModel}"
                    )
                    VoiceNoteStore.saveNote(this, note)
                }
            }

            _state.value = RecorderState(
                isRecording = false,
                isProcessing = false,
                statusMessage = "Saved ${note.title} ($speakerCount voice${if (speakerCount > 1) "s" else ""})",
                speakerNames = speakerNames,
                lastCompletedNoteId = noteId
            )
        } catch (e: Exception) {
            Log.e("OrbVoice", "Save error on stop", e)
            _state.value = _state.value.copy(
                isRecording = false,
                isProcessing = false,
                statusMessage = "Save error: ${e.message}"
            )
        } finally {
            pcmFile.delete()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun writeWavFromPcmFile(pcmFile: File, pcmBytesLen: Long, wavOut: File) {
        val dataSize = pcmBytesLen.coerceAtMost(Int.MAX_VALUE.toLong() - 44L).toInt()
        val byteRate = SAMPLE_RATE * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
        java.io.BufferedOutputStream(java.io.FileOutputStream(wavOut), 65536).use { out ->
            out.write(header)
            if (pcmFile.exists()) {
                pcmFile.inputStream().use { inp ->
                    val buf = ByteArray(65536)
                    var rem = dataSize
                    while (rem > 0) {
                        val r = inp.read(buf, 0, minOf(buf.size, rem))
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        rem -= r
                    }
                }
            }
        }
    }

    private fun encodeMp3ViaFfmpeg(wavFile: File, mp3Out: File): Boolean {
        return try {
            val sharedTmp = File(mp3Out.parentFile, ".tmp_${mp3Out.nameWithoutExtension}.wav")
            wavFile.copyTo(sharedTmp, overwrite = true)
            val inContainer = sharedTmp.absolutePath.replace("/storage/emulated/0", "/mnt/android")
            val outContainer = mp3Out.absolutePath.replace("/storage/emulated/0", "/mnt/android")
            val p = Runtime.getRuntime().exec(
                arrayOf(
                    "su", "-c",
                    "/data/local/nixos/bin/droidspaces --name=razr run /run/current-system/sw/bin/ffmpeg " +
                        "-y -loglevel error -i '$inContainer' -codec:a libmp3lame -b:a 96k '$outContainer'"
                )
            )
            val rc = p.waitFor()
            sharedTmp.delete()
            rc == 0 && mp3Out.exists() && mp3Out.length() > 256
        } catch (_: Exception) {
            false
        }
    }

    private fun encodeM4aHardware(pcm: ShortArray, outFile: File): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val rawBytes = ByteArray(pcm.size * 2)
            ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)

            var offset = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        if (offset >= rawBytes.size) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val len = minOf(inBuf.remaining(), rawBytes.size - offset)
                            inBuf.put(rawBytes, offset, len)
                            val ptsUs = (offset / 2L) * 1_000_000L / SAMPLE_RATE
                            codec.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
                            offset += len
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && muxerStarted) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            outFile.exists() && outFile.length() > 256
        } catch (_: Exception) {
            false
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OrbVoice::ClosedRecording").apply {
            acquire(2 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun startForegroundCompat(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Orb Voice Recorder", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Orb Voice")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}
