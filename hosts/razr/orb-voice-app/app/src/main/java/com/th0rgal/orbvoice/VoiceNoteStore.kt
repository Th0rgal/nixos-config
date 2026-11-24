package com.th0rgal.orbvoice

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TranscriptSegment(
    val speakerIndex: Int,
    val speakerName: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translatedText: String = ""
)

data class VoiceNote(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val audioPath: String,
    val markdownPath: String,
    val jsonPath: String,
    val engineUsed: String,
    val speakerCount: Int,
    val syncedToSpark: Boolean = false,
    val summary: String = "",
    val segments: List<TranscriptSegment> = emptyList()
)

object VoiceNoteStore {
    const val PUBLIC_SUBDIR = "Documents/OrbVoice"

    fun notesDir(context: Context): File {
        // Prefer public /sdcard/Documents/OrbVoice (accessible from Android Files, Google Drive,
        // and NixOS Flea at /mnt/android/Documents/OrbVoice). Fall back to app external files.
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "OrbVoice"
        )
        if (publicDir.exists() || publicDir.mkdirs()) {
            val probe = File(publicDir, ".writable")
            try {
                probe.writeText("ok")
                probe.delete()
                return publicDir
            } catch (_: Exception) {
                // Scoped storage restriction — fall through to app-owned dir + MediaStore export
            }
        }
        val appDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "OrbVoice")
        appDir.mkdirs()
        return appDir
    }

    fun modelsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
        dir.mkdirs()
        return dir
    }

    fun newNoteId(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        return fmt.format(Date())
    }

    fun formatTimestamp(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    fun saveNote(context: Context, note: VoiceNote) {
        val mdContent = renderMarkdown(note)
        File(note.markdownPath).writeText(mdContent)

        val root = JSONObject().apply {
            put("id", note.id)
            put("title", note.title)
            put("created_at_ms", note.createdAtMs)
            put("duration_ms", note.durationMs)
            put("audio_path", note.audioPath)
            put("markdown_path", note.markdownPath)
            put("engine_used", note.engineUsed)
            put("speaker_count", note.speakerCount)
            put("synced_to_spark", note.syncedToSpark)
            put("summary", note.summary)
            val segs = JSONArray()
            for (s in note.segments) {
                segs.put(JSONObject().apply {
                    put("speaker_index", s.speakerIndex)
                    put("speaker_name", s.speakerName)
                    put("start_ms", s.startMs)
                    put("end_ms", s.endMs)
                    put("text", s.text)
                    if (s.translatedText.isNotBlank()) put("translated_text", s.translatedText)
                })
            }
            put("segments", segs)
        }
        File(note.jsonPath).writeText(root.toString(2))

        // Mirror transcript & audio to public MediaStore Documents/OrbVoice if saved in app-private dir
        val publicRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "OrbVoice"
        )
        if (!File(note.markdownPath).absolutePath.startsWith(publicRoot.absolutePath)) {
            exportToMediaStore(context, File(note.markdownPath), "text/markdown")
            val af = File(note.audioPath)
            if (af.exists()) {
                val mime = if (af.extension.equals("mp3", true)) "audio/mpeg" else "audio/mp4"
                exportToMediaStore(context, af, mime)
            }
        }
    }

    private fun exportToMediaStore(context: Context, file: File, mimeType: String) {
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLIC_SUBDIR)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { inp -> inp.copyTo(out) }
            }
        } catch (_: Exception) {
            // Best-effort mirror
        }
    }

    fun renderMarkdown(note: VoiceNote): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(note.createdAtMs))
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("id: \"${note.id}\"")
        sb.appendLine("title: \"${note.title.replace("\"", "'")}\"")
        sb.appendLine("recorded_at: \"$dateStr\"")
        sb.appendLine("duration: \"${formatTimestamp(note.durationMs)}\"")
        sb.appendLine("speakers: ${note.speakerCount}")
        sb.appendLine("engine: \"${note.engineUsed}\"")
        sb.appendLine("audio_file: \"${File(note.audioPath).name}\"")
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("# ${note.title}")
        sb.appendLine()
        if (note.summary.isNotBlank()) {
            sb.appendLine("> ${note.summary}")
            sb.appendLine()
        }
        if (note.segments.isEmpty()) {
            sb.appendLine("*(No speech segments detected)*")
        } else {
            for (seg in note.segments) {
                val ts = formatTimestamp(seg.startMs)
                sb.appendLine("**[$ts] ${seg.speakerName}:** ${seg.text}")
                if (seg.translatedText.isNotBlank()) {
                    sb.appendLine("  *→ ${seg.translatedText}*")
                }
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    fun loadAllNotes(context: Context): List<VoiceNote> {
        val dir = notesDir(context)
        val jsonFiles = dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedByDescending { it.name }
            .orEmpty()

        return jsonFiles.mapNotNull { jf ->
            try {
                val obj = JSONObject(jf.readText())
                val segArr = obj.optJSONArray("segments") ?: JSONArray()
                val segs = ArrayList<TranscriptSegment>(segArr.length())
                for (i in 0 until segArr.length()) {
                    val s = segArr.getJSONObject(i)
                    segs.add(
                        TranscriptSegment(
                            speakerIndex = s.optInt("speaker_index", 0),
                            speakerName = s.optString("speaker_name", "Speaker 1"),
                            startMs = s.optLong("start_ms", 0L),
                            endMs = s.optLong("end_ms", 0L),
                            text = s.optString("text", ""),
                            translatedText = if (s.isNull("translated_text")) "" else s.optString("translated_text", "")
                        )
                    )
                }
                VoiceNote(
                    id = obj.optString("id", jf.nameWithoutExtension),
                    title = obj.optString("title", jf.nameWithoutExtension),
                    createdAtMs = obj.optLong("created_at_ms", jf.lastModified()),
                    durationMs = obj.optLong("duration_ms", 0L),
                    audioPath = obj.optString("audio_path", ""),
                    markdownPath = obj.optString("markdown_path", File(dir, "${jf.nameWithoutExtension}.md").absolutePath),
                    jsonPath = jf.absolutePath,
                    engineUsed = obj.optString("engine_used", "NPU"),
                    speakerCount = obj.optInt("speaker_count", segs.map { it.speakerIndex }.distinct().size.coerceAtLeast(1)),
                    syncedToSpark = obj.optBoolean("synced_to_spark", false),
                    summary = obj.optString("summary", ""),
                    segments = segs
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun renameSpeaker(context: Context, note: VoiceNote, speakerIndex: Int, newName: String): VoiceNote {
        val updated = note.copy(
            segments = note.segments.map { seg ->
                if (seg.speakerIndex == speakerIndex) seg.copy(speakerName = newName.trim().ifBlank { seg.speakerName })
                else seg
            }
        )
        saveNote(context, updated)
        return updated
    }

    fun deleteNote(note: VoiceNote) {
        try { File(note.audioPath).delete() } catch (_: Exception) {}
        try { File(note.markdownPath).delete() } catch (_: Exception) {}
        try { File(note.jsonPath).delete() } catch (_: Exception) {}
    }
}
