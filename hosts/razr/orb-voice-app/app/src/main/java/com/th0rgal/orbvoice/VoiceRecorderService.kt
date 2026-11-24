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
    private val asrExecutor = Executors.newSingleThreadExecutor()
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
        // Cohere-Transcribe-2B with lang="fr" faithfully transcribes BOTH French & English verbatim
        // (whereas lang="en" forces French speech to be translated into English). If Translate mode
        // pairs two other languages (e.g. Spanish + Portuguese), pick the non-English language code.
        val cohereLangHint = run {
            val langMap = mapOf(
                "French" to "fr", "Spanish" to "es", "German" to "de",
                "Italian" to "it", "Portuguese" to "pt", "English" to "en"
            )
            val pair = listOf(orbCfgInit.translateLangA, orbCfgInit.translateLangB)
            val nonEn = pair.firstOrNull { !it.equals("English", ignoreCase = true) }
            langMap[nonEn] ?: "fr"
        }

        _state.value = _state.value.copy(statusMessage = "Loading Hexagon V79 NPU + ${preferredAsr.label}…")
        val neuralEngine = SpeakerDiarizer.NeuralSpeakerEngine.create(this)
        val localAsr = LocalAsrEngine.create(this, preferredId = orbCfgInit.localAsrModel, languageHint = cohereLangHint)
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
            localAsr?.close()
            neuralEngine?.close()
            recording.set(false)
            _state.value = RecorderState(statusMessage = "AudioRecord failed to initialize")
            releaseWakeLock()
            stopSelf()
            return
        }

        val startTimeMs = System.currentTimeMillis()
        val chunk = ShortArray(1600) // 100ms chunks
        val floatChunk = FloatArray(1600)
        val byteChunk = ByteArray(chunk.size * 2)
        val levelsWindow = ArrayDeque<Float>(48)

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

        val turnSamples = ArrayList<Short>(SAMPLE_RATE * 6)
        var inTurn = false
        var turnStartMs = 0L
        var silenceChunks = 0
        var noiseFloor = 0.008f
        var earlyClassified = false
        var lastPreviewDecodeMs = 0L
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
            asrExecutor.execute {
                val text = localAsr.transcribe(segFloats)
                if (text.isBlank()) return@execute
                val speakerIdx = tracker.classifyTurn(segShorts, allowNewSpeaker = true)
                currentSpeakerIndex = speakerIdx
                val cur = _state.value
                val spkName = cur.speakerNames[speakerIdx] ?: "Speaker ${speakerIdx + 1}"
                val newSeg = TranscriptSegment(
                    speakerIndex = speakerIdx,
                    speakerName = spkName,
                    startMs = tStart,
                    endMs = tEnd,
                    text = text,
                    translatedText = if (translateModeActive) "Translating…" else ""
                )
                val updatedList = cur.liveSegments + newSeg
                val targetIndex = updatedList.lastIndex
                _state.value = cur.copy(
                    activeSpeakerIndex = speakerIdx,
                    partialText = "",
                    liveSegments = updatedList
                )
                // A) Rolling live Orb Router autocorrect + auto-translate / bidirectional translate in parallel
                val orbCfg = OrbRouterConfig.load(this)
                val shouldRunLlm = orbCfg.isConfigured &&
                    (translateModeActive || orbCfg.autoTranslateTarget.isNotBlank() || orbCfg.livePolishEnabled) &&
                    text.length >= 2
                if (shouldRunLlm) {
                    val ctxSnap = updatedList.dropLast(1).takeLast(3)
                    val namesSnap = cur.speakerNames.values.toList()
                    val bidirA = if (translateModeActive) translateLangA else null
                    val bidirB = if (translateModeActive) translateLangB else null
                    llmExecutor.execute {
                        var res = OrbRouterClient.polishAndTranslateLive(
                            cfg = orbCfg,
                            rawText = text,
                            recentContext = ctxSnap,
                            knownSpeakers = namesSnap,
                            bidirectionalLangA = bidirA,
                            bidirectionalLangB = bidirB
                        )
                        // One quick retry if bidirectional translation came back empty
                        if (translateModeActive && (res == null || res.translated.isBlank())) {
                            res = OrbRouterClient.polishAndTranslateLive(
                                cfg = orbCfg,
                                rawText = text,
                                recentContext = emptyList(),
                                knownSpeakers = namesSnap,
                                bidirectionalLangA = bidirA,
                                bidirectionalLangB = bidirB
                            )
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
                            } else if (existingSeg.translatedText == "Translating…") {
                                copy[targetIndex] = existingSeg.copy(translatedText = "")
                            }
                            _state.value = nowState.copy(liveSegments = copy)
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
                // Gentle room-mic AGC for Silero VAD & Parakeet-v3 so quieter/distant speakers
                // (e.g. students answering across a room) are captured as cleanly as the main speaker.
                val agcGain = if (rmsRaw > 0.0025f && rmsRaw < 0.06f) {
                    (0.08f / rmsRaw).coerceIn(1.0f, 4.5f)
                } else {
                    1.0f
                }
                for (i in 0 until read) {
                    val boosted = (chunk[i] / 32768f) * agcGain
                    floatChunk[i] = boosted.coerceIn(-0.98f, 0.98f)
                }
                val rmsNorm = (rmsRaw * 6.5f).coerceIn(0.05f, 1.0f)
                if (levelsWindow.size >= 48) levelsWindow.removeFirst()
                levelsWindow.addLast(rmsNorm)

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
                            turnSamples.clear()
                        }
                        for (i in 0 until read) turnSamples.add(chunk[i])
                        // Live streaming preview every ~1.4s while speaking
                        if (elapsed - lastPreviewDecodeMs >= 1400L && turnSamples.size >= SAMPLE_RATE / 2) {
                            lastPreviewDecodeMs = elapsed
                            val snapFloats = FloatArray(turnSamples.size) { turnSamples[it] / 32768f }
                            asrExecutor.execute {
                                if (!recording.get()) return@execute
                                val preview = localAsr.transcribe(snapFloats)
                                if (preview.isNotBlank() && recording.get()) {
                                    _state.value = _state.value.copy(
                                        activeSpeakerIndex = _state.value.activeSpeakerIndex ?: currentSpeakerIndex,
                                        partialText = preview
                                    )
                                }
                            }
                        }
                    }
                    for (segFloats in completed) {
                        val segDurMs = (segFloats.size * 1000L) / SAMPLE_RATE
                        val tStart = (elapsed - segDurMs).coerceAtLeast(0L)
                        inTurn = false
                        turnSamples.clear()
                        submitParakeetTurn(segFloats, tStart, elapsed)
                    }
                    _state.value = _state.value.copy(
                        isRecording = true,
                        elapsedMs = elapsed,
                        levels = levelsWindow.toList()
                    )
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
                        turnSamples.clear()
                    }
                    silenceChunks = 0
                    for (i in 0 until read) turnSamples.add(chunk[i])

                    // Early speaker probe (~600ms) NEVER spawns a new speaker; only matches existing learned voices
                    if (!earlyClassified && turnSamples.size >= (SAMPLE_RATE * 6) / 10) {
                        earlyClassified = true
                        val probe = ShortArray(turnSamples.size) { turnSamples[it] }
                        val spk = tracker.classifyTurn(probe, allowNewSpeaker = false)
                        currentSpeakerIndex = spk
                        _state.value = _state.value.copy(activeSpeakerIndex = spk)
                    }
                } else if (inTurn) {
                    silenceChunks++
                    for (i in 0 until read) turnSamples.add(chunk[i])
                }

                // Flush a live speaker turn on 800ms pause OR 6s continuous speech
                val shouldFlush = inTurn && (silenceChunks >= 8 || turnSamples.size >= SAMPLE_RATE * 6)
                if (shouldFlush) {
                    val endMs = elapsed
                    val snapshot = ShortArray(turnSamples.size) { turnSamples[it] }
                    val tStart = turnStartMs
                    inTurn = false
                    silenceChunks = 0
                    earlyClassified = false
                    turnSamples.clear()

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

                _state.value = _state.value.copy(
                    isRecording = true,
                    elapsedMs = elapsed,
                    levels = levelsWindow.toList()
                )
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
                localAsr?.close()
                neuralEngine?.close()
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
