package com.th0rgal.orbvoice

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Persistent Warm Engine Pool exploiting the Razr 60 Ultra's 16 GB unified LPDDR5X RAM.
 *
 * Keeps both the Hexagon V79 NPU speaker engine (WeSpeaker ResNet293-LM, ~57 MB context binary)
 * and the active local ASR model (Cohere Transcribe 2B / Qwen3-ASR / Parakeet-TDT 0.6B) resident
 * across recording sessions so tapping Record starts capture instantaneously (< 50ms) instead of
 * reloading multi-GB ONNX weights from flash on every session.
 */
object ScribeEnginePool {
    private val lock = Any()
    private val prewarmExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scribe-prewarm").apply { isDaemon = true }
    }

    @Volatile private var speakerEngine: SpeakerDiarizer.NeuralSpeakerEngine? = null
    @Volatile private var asrEngine: LocalAsrEngine? = null
    @Volatile private var loadedAsrKey: String? = null

    data class WarmEngines(
        val speaker: SpeakerDiarizer.NeuralSpeakerEngine?,
        val asr: LocalAsrEngine?
    )

    fun resolveLanguageHint(cfg: OrbRouterConfig): String {
        val langMap = mapOf(
            "French" to "fr", "Spanish" to "es", "German" to "de",
            "Italian" to "it", "Portuguese" to "pt", "English" to "en"
        )
        val pair = listOf(cfg.translateLangA, cfg.translateLangB)
        val nonEn = pair.firstOrNull { !it.equals("English", ignoreCase = true) }
        return langMap[nonEn] ?: "fr"
    }

    /** Background pre-warm on app launch so first Record tap is instant. */
    fun prewarmAsync(context: Context) {
        val appCtx = context.applicationContext
        prewarmExecutor.execute {
            try {
                val cfg = OrbRouterConfig.load(appCtx)
                acquire(appCtx, cfg.localAsrModel, resolveLanguageHint(cfg))
            } catch (e: Throwable) {
                Log.w("OrbVoice", "ScribeEnginePool prewarm failed: ${e.message}")
            }
        }
    }

    /**
     * Returns warm resident NPU speaker + local ASR engines.
     * Reuses existing sessions in < 1ms when (modelId, languageHint) matches;
     * reloads only when the user changes ASR model or language pair in Settings.
     */
    fun acquire(context: Context, preferredModelId: String, languageHint: String): WarmEngines {
        val appCtx = context.applicationContext
        val key = "$preferredModelId:$languageHint"
        synchronized(lock) {
            if (speakerEngine == null) {
                speakerEngine = SpeakerDiarizer.NeuralSpeakerEngine.create(appCtx)
            }
            if (asrEngine == null || loadedAsrKey != key) {
                try { asrEngine?.close() } catch (_: Exception) {}
                asrEngine = null
                loadedAsrKey = null
                val created = LocalAsrEngine.create(
                    appCtx,
                    preferredId = preferredModelId,
                    languageHint = languageHint
                )
                asrEngine = created
                if (created != null) {
                    loadedAsrKey = key
                }
            } else {
                // Reset Silero VAD internal state between sessions so prior audio never leaks
                asrEngine?.resetVad()
            }
            return WarmEngines(speakerEngine, asrEngine)
        }
    }

    fun invalidateAsr() {
        synchronized(lock) {
            try { asrEngine?.close() } catch (_: Exception) {}
            asrEngine = null
            loadedAsrKey = null
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            try { asrEngine?.close() } catch (_: Exception) {}
            try { speakerEngine?.close() } catch (_: Exception) {}
            asrEngine = null
            speakerEngine = null
            loadedAsrKey = null
        }
    }
}
