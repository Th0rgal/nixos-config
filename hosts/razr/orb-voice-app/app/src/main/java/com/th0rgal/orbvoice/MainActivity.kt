package com.th0rgal.orbvoice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private var cutoutRect by mutableStateOf<Rect?>(null)
    private var screenBounds by mutableStateOf(Rect(0, 0, 1080, 2400))

    private fun refreshCutoutMetrics() {
        val metrics = windowManager.currentWindowMetrics
        screenBounds = metrics.bounds
        cutoutRect = metrics.windowInsets.displayCutout?.boundingRects?.firstOrNull()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        refreshCutoutMetrics()

        setContent {
            val prefs = remember { getSharedPreferences("orb_voice_prefs", Context.MODE_PRIVATE) }
            var themeOverride by remember { mutableStateOf(prefs.getString("theme_override", "system") ?: "system") }
            val sysDark = isSystemInDarkTheme()
            val useDark = when (themeOverride) {
                "dark" -> true
                "light" -> false
                else -> sysDark
            }
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = if (useDark) SystemBarStyle.dark(Color.TRANSPARENT)
                        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (useDark) SystemBarStyle.dark(Color.TRANSPARENT)
                        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                )
            }
            OrbVoiceTheme(darkTheme = useDark) {
                OrbVoiceApp(
                    cutoutRect = cutoutRect,
                    screenBounds = screenBounds,
                    isDarkTheme = useDark,
                    onToggleTheme = {
                        val next = if (useDark) "light" else "dark"
                        themeOverride = next
                        prefs.edit().putString("theme_override", next).apply()
                    }
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshCutoutMetrics()
    }
}

private enum class VoiceTab(val label: String) {
    LIVE("Record"),
    TRANSLATE("Translate"),
    NOTES("Notes")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrbVoiceApp(
    cutoutRect: Rect?,
    screenBounds: Rect,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val recState by VoiceRecorderService.state.collectAsState()
    val npuStatus = remember { NpuTranscriptionEngine.probeHardware() }

    var notes by remember { mutableStateOf<List<VoiceNote>>(emptyList()) }
    var selectedNote by remember { mutableStateOf<VoiceNote?>(null) }
    var activeTab by remember { mutableStateOf(VoiceTab.LIVE) }
    var showSettings by remember { mutableStateOf(false) }
    var islandExpanded by remember { mutableStateOf(false) }

    // Rename target: either a live speaker (note == null) or a saved note's speaker
    var renameTarget by remember { mutableStateOf<Pair<VoiceNote?, Int>?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var syncingNoteId by remember { mutableStateOf<String?>(null) }
    var deepPolishingNoteId by remember { mutableStateOf<String?>(null) }
    var orbCfg by remember { mutableStateOf(OrbRouterConfig.load(context)) }
    var knownVoices by remember { mutableStateOf<List<VoiceprintProfile>>(emptyList()) }

    fun reloadKnownVoices() {
        scope.launch {
            knownVoices = withContext(Dispatchers.IO) { VoiceprintRegistry.loadAll(context) }
        }
    }

    val prefs = remember { context.getSharedPreferences("orb_voice_prefs", Context.MODE_PRIVATE) }
    var sparkAsrUrl by remember {
        mutableStateOf(prefs.getString("spark_asr_url", NpuTranscriptionEngine.DEFAULT_SPARK_ASR_URL)
            ?: NpuTranscriptionEngine.DEFAULT_SPARK_ASR_URL)
    }
    var sparkMemUrl by remember {
        mutableStateOf(prefs.getString("spark_mem_url", NpuTranscriptionEngine.DEFAULT_SPARK_MEMORY_URL)
            ?: NpuTranscriptionEngine.DEFAULT_SPARK_MEMORY_URL)
    }
    var keepScreenOn by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", false)) }

    // Keep screen awake only when recording AND toggled on; otherwise let screen sleep while foreground service records
    DisposableEffect(recState.isRecording, keepScreenOn) {
        val window = (context as? ComponentActivity)?.window
        if (recState.isRecording && keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun reloadNotes() {
        scope.launch {
            notes = withContext(Dispatchers.IO) { VoiceNoteStore.loadAllNotes(context) }
            selectedNote?.let { sel -> selectedNote = notes.firstOrNull { it.id == sel.id } }
        }
    }

    fun showNotice(msg: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    fun toggleKeepScreenOn() {
        keepScreenOn = !keepScreenOn
        prefs.edit().putBoolean("keep_screen_on", keepScreenOn).apply()
        showNotice(if (keepScreenOn) "Screen will stay awake while recording" else "Screen can sleep (recording continues)")
    }

    LaunchedEffect(Unit) {
        reloadNotes()
        reloadKnownVoices()
    }
    LaunchedEffect(activeTab) {
        if (activeTab == VoiceTab.NOTES) reloadNotes()
    }
    LaunchedEffect(recState.isRecording) {
        if (recState.isRecording) {
            activeTab = if (recState.isTranslateMode) VoiceTab.TRANSLATE else VoiceTab.LIVE
        }
    }
    LaunchedEffect(recState.lastCompletedNoteId) {
        if (recState.lastCompletedNoteId != null) reloadNotes()
    }

    fun startActiveModeCapture() {
        val isTrans = activeTab == VoiceTab.TRANSLATE
        VoiceRecorderService.startRecording(
            context = context,
            translateMode = isTrans,
            langA = orbCfg.translateLangA,
            langB = orbCfg.translateLangB
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.RECORD_AUDIO] == true) {
            startActiveModeCapture()
        } else {
            showNotice("Microphone permission is required")
        }
    }

    fun toggleRecord() {
        if (recState.isRecording) {
            VoiceRecorderService.stopRecording(context)
            islandExpanded = false
            return
        }
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            val req = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
            permLauncher.launch(req)
        } else {
            startActiveModeCapture()
        }
    }

    fun updateTranslatePair(langA: String, langB: String) {
        val updated = orbCfg.copy(translateLangA = langA, translateLangB = langB)
        OrbRouterConfig.save(context, updated)
        orbCfg = updated
    }

    fun shareFile(path: String, mimeType: String, title: String) {
        val file = File(path)
        if (!file.exists()) {
            showNotice("File not found: ${file.name}")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, title))
        } catch (e: Exception) {
            showNotice("Share failed: ${e.message}")
        }
    }

    Scaffold(
        containerColor = OrbBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 72.dp)
            ) { data ->
                Snackbar(
                    shape = RoundedCornerShape(10.dp),
                    containerColor = OrbSurfaceHover,
                    contentColor = OrbFgStrong,
                    modifier = Modifier.border(1.dp, OrbBorder, RoundedCornerShape(10.dp))
                ) {
                    Text(data.visuals.message, style = MaterialTheme.typography.bodySmall, color = OrbFgStrong)
                }
            }
        }
    ) { _ ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(OrbBg)
        ) {
            val layoutDirection = LocalLayoutDirection.current
            val cutoutPad = WindowInsets.displayCutout.asPaddingValues()
            val cutoutBottom = cutoutPad.calculateBottomPadding()
            val cutoutEnd = cutoutPad.calculateEndPadding(layoutDirection)

            val isWideScreen = maxWidth >= 640.dp
            // Motorola Razr 60 Ultra full-bleed cover screen: 480dp x 565dp with twin bottom-right cameras
            val isCoverScreen = !isWideScreen &&
                (maxHeight < 620.dp || maxWidth < 380.dp || cutoutBottom > 0.dp)
            val cameraSafeEndPadding = max(230.dp, cutoutEnd + 14.dp)

            if (isWideScreen) {
                WideTwoPaneLayout(
                    recState = recState,
                    npuStatus = npuStatus,
                    notes = notes,
                    selectedNote = selectedNote,
                    keepScreenOn = keepScreenOn,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = onToggleTheme,
                    onToggleKeepScreenOn = ::toggleKeepScreenOn,
                    onToggleRecord = ::toggleRecord,
                    onSelectNote = { selectedNote = it },
                    onRenameLiveSpeaker = { idx, cur ->
                        renameDraft = cur
                        renameTarget = null to idx
                    },
                    onOpenSettings = { showSettings = true }
                )
            } else {
                PhoneAndCoverBody(
                    recState = recState,
                    npuStatus = npuStatus,
                    notes = notes,
                    activeTab = activeTab,
                    isCoverScreen = isCoverScreen,
                    keepScreenOn = keepScreenOn,
                    isDarkTheme = isDarkTheme,
                    translateLangA = orbCfg.translateLangA,
                    translateLangB = orbCfg.translateLangB,
                    onUpdateTranslatePair = ::updateTranslatePair,
                    onToggleTheme = onToggleTheme,
                    onToggleKeepScreenOn = ::toggleKeepScreenOn,
                    onSelectTab = { activeTab = it },
                    onToggleRecord = ::toggleRecord,
                    onSelectNote = { selectedNote = it },
                    onRenameLiveSpeaker = { idx, cur ->
                        renameDraft = cur
                        renameTarget = null to idx
                    },
                    onOpenSettings = { showSettings = true }
                )
            }

            // Dynamic Island Camera-Hugging Live Recording Pill (inner screen punch-hole)
            if (!isCoverScreen && recState.isRecording) {
                DynamicIslandRecordingPill(
                    cutoutRect = cutoutRect,
                    screenBounds = screenBounds,
                    recState = recState,
                    expanded = islandExpanded,
                    onToggleExpand = { islandExpanded = !islandExpanded },
                    onStop = ::toggleRecord,
                    onShowStream = { activeTab = VoiceTab.LIVE; islandExpanded = false },
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

            // Razr 60 Ultra Cover-Screen Left-Aligned Floating Pill Dock (tucked left of twin cameras)
            if (isCoverScreen) {
                CoverScreenVoiceDock(
                    recState = recState,
                    activeTab = activeTab,
                    onToggleRecord = ::toggleRecord,
                    onToggleTab = {
                        activeTab = when (activeTab) {
                            VoiceTab.LIVE -> VoiceTab.TRANSLATE
                            VoiceTab.TRANSLATE -> VoiceTab.NOTES
                            VoiceTab.NOTES -> VoiceTab.LIVE
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 12.dp, end = cameraSafeEndPadding, bottom = 10.dp)
                )
            }
        }
    }

    // Note Detail Sheet (Michelangelus serif prose + color-coded speaker rename + Deep Audio Quorum Polish)
    selectedNote?.let { note ->
        NoteDetailSheet(
            note = note,
            isSyncing = syncingNoteId == note.id,
            isDeepPolishing = deepPolishingNoteId == note.id,
            deepModelLabel = orbCfg.deepAudioModel,
            onDismiss = { selectedNote = null },
            onRenameSpeaker = { speakerIdx, currentName ->
                renameDraft = currentName
                renameTarget = note to speakerIdx
            },
            onShareTranscript = { shareFile(note.markdownPath, "text/markdown", "Share Transcript") },
            onShareAudio = {
                val mime = if (note.audioPath.endsWith(".mp3", true)) "audio/mpeg" else "audio/mp4"
                shareFile(note.audioPath, mime, "Share Audio Recording")
            },
            onDeepAudioPolish = {
                if (!orbCfg.isConfigured) {
                    showSettings = true
                    showNotice("Set your Orb Router API key in Settings first")
                    return@NoteDetailSheet
                }
                deepPolishingNoteId = note.id
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        OrbRouterClient.deepAudioRetranscribe(orbCfg, note)
                    }
                    deepPolishingNoteId = null
                    res.fold(
                        onSuccess = { polished ->
                            val spkCount = polished.segments.map { it.speakerIndex }.distinct().size.coerceAtLeast(1)
                            val updated = note.copy(
                                title = polished.title,
                                summary = polished.summary,
                                segments = polished.segments,
                                speakerCount = spkCount,
                                engineUsed = "Deep Audio · ${orbCfg.deepAudioModel}"
                            )
                            withContext(Dispatchers.IO) { VoiceNoteStore.saveNote(context, updated) }
                            reloadNotes()
                            showNotice("Deep audio polish complete (${orbCfg.deepAudioModel})")
                        },
                        onFailure = { err -> showNotice("Deep polish failed: ${err.message}") }
                    )
                }
            },
            onSyncSpark = {
                syncingNoteId = note.id
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        NpuTranscriptionEngine.syncToSparkMemory(note, sparkMemUrl)
                    }
                    syncingNoteId = null
                    res.fold(
                        onSuccess = {
                            val updated = note.copy(syncedToSpark = true)
                            withContext(Dispatchers.IO) { VoiceNoteStore.saveNote(context, updated) }
                            reloadNotes()
                            showNotice("Synced to DGX Spark Memory")
                        },
                        onFailure = { err -> showNotice("Spark sync failed: ${err.message}") }
                    )
                }
            },
            onDelete = {
                scope.launch {
                    withContext(Dispatchers.IO) { VoiceNoteStore.deleteNote(note) }
                    selectedNote = null
                    reloadNotes()
                    showNotice("Deleted ${note.title}")
                }
            }
        )
    }

    // Orb Hairline Settings Sheet
    if (showSettings) {
        SettingsModalSheet(
            npuStatus = npuStatus,
            sparkAsrUrl = sparkAsrUrl,
            sparkMemUrl = sparkMemUrl,
            orbCfg = orbCfg,
            knownVoices = knownVoices,
            onDeleteVoice = { voiceName ->
                scope.launch {
                    withContext(Dispatchers.IO) { VoiceprintRegistry.delete(context, voiceName) }
                    reloadKnownVoices()
                    showNotice("Removed voiceprint: $voiceName")
                }
            },
            onSave = { asr, mem, newOrbCfg ->
                sparkAsrUrl = asr.trim()
                sparkMemUrl = mem.trim()
                prefs.edit()
                    .putString("spark_asr_url", sparkAsrUrl)
                    .putString("spark_mem_url", sparkMemUrl)
                    .apply()
                OrbRouterConfig.save(context, newOrbCfg)
                orbCfg = OrbRouterConfig.load(context)
                showSettings = false
                showNotice("Saved Orb Router & Spark settings")
            },
            onDismiss = { showSettings = false }
        )
    }

    // Rename Speaker Dialog (works BOTH live during recording AND on saved notes!)
    renameTarget?.let { (note, speakerIdx) ->
        val speakerColors = LocalOrbPalette.current.speakerColors
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = OrbSurface,
            titleContentColor = OrbFgStrong,
            textContentColor = OrbFgMuted,
            title = {
                Text("Label Speaker ${speakerIdx + 1}", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it },
                        singleLine = true,
                        placeholder = { Text("e.g. Thomas, Teacher, Nini") }
                    )
                    if (knownVoices.isNotEmpty()) {
                        Text(
                            text = "KNOWN VOICES (TAP TO ASSIGN)",
                            fontSize = 10.sp,
                            letterSpacing = 0.7.sp,
                            color = OrbFgSubtle
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            knownVoices.take(4).forEachIndexed { idx, vp ->
                                val c = speakerColors[idx % speakerColors.size]
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(c.copy(alpha = 0.16f))
                                        .clickable { renameDraft = vp.name }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(vp.name, style = MaterialTheme.typography.labelSmall, color = c)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameDraft.trim()
                    if (newName.isNotEmpty()) {
                        if (note == null) {
                            VoiceRecorderService.renameLiveSpeaker(speakerIdx, newName)
                            scope.launch {
                                kotlinx.coroutines.delay(250)
                                reloadKnownVoices()
                            }
                        } else {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    VoiceNoteStore.renameSpeaker(context, note, speakerIdx, newName)
                                }
                                reloadNotes()
                            }
                        }
                    }
                    renameTarget = null
                }) {
                    Text("Save", color = OrbBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel", color = OrbFgMuted)
                }
            }
        )
    }
}

@Composable
private fun DynamicIslandRecordingPill(
    cutoutRect: Rect?,
    screenBounds: Rect,
    recState: RecorderState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onStop: () -> Unit,
    onShowStream: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val speakerColors = LocalOrbPalette.current.speakerColors
    val activeColor = recState.activeSpeakerIndex?.let { speakerColors[it % speakerColors.size] } ?: OrbRed
    val cutoutWdp = with(density) { (cutoutRect?.width() ?: 96).toDp() }
    val cutoutHdp = with(density) { (cutoutRect?.height() ?: 92).toDp() }

    Box(modifier = modifier.padding(top = 4.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (expanded) OrbSurface.copy(alpha = 0.94f) else androidx.compose.ui.graphics.Color(0xFF000000),
            border = BorderStroke(
                1.dp,
                if (expanded) OrbBorder else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f)
            ),
            modifier = Modifier.animateContentSize()
        ) {
            if (!expanded) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onToggleExpand)
                        .height(cutoutHdp.coerceAtLeast(34.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(activeColor))
                    Spacer(modifier = Modifier.width(cutoutWdp.coerceAtLeast(28.dp)))
                    Text(
                        text = VoiceNoteStore.formatTimestamp(recState.elapsedMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = "Expand",
                        tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(activeColor))
                    Text(
                        text = VoiceNoteStore.formatTimestamp(recState.elapsedMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = OrbFgStrong
                    )
                    Text(
                        text = "Stream",
                        style = MaterialTheme.typography.labelMedium,
                        color = OrbBlue,
                        modifier = Modifier.clickable(onClick = onShowStream).padding(horizontal = 4.dp)
                    )
                    Text(
                        text = "Stop",
                        style = MaterialTheme.typography.labelMedium,
                        color = OrbRed,
                        modifier = Modifier.clickable(onClick = onStop).padding(horizontal = 4.dp)
                    )
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = "Collapse",
                        tint = OrbFgMuted,
                        modifier = Modifier.size(16.dp).clickable(onClick = onToggleExpand)
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverScreenVoiceDock(
    recState: RecorderState,
    activeTab: VoiceTab,
    onToggleRecord: () -> Unit,
    onToggleTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = OrbSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (recState.isRecording) OrbRed.copy(alpha = 0.6f) else OrbBorder),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (recState.isRecording) OrbRed else OrbFgStrong)
                    .clickable(enabled = !recState.isProcessing, onClick = onToggleRecord)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = when {
                            recState.isRecording -> Icons.Rounded.Stop
                            activeTab == VoiceTab.TRANSLATE -> Icons.Rounded.Translate
                            else -> Icons.Rounded.Mic
                        },
                        contentDescription = null,
                        tint = if (recState.isRecording) OrbFgStrong else OrbBg,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = when {
                            recState.isRecording -> VoiceNoteStore.formatTimestamp(recState.elapsedMs)
                            activeTab == VoiceTab.TRANSLATE -> "Trans"
                            else -> "Rec"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (recState.isRecording) OrbFgStrong else OrbBg
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(OrbSurfaceHover)
                    .border(1.dp, OrbBorder, RoundedCornerShape(10.dp))
                    .clickable(onClick = onToggleTab)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = when (activeTab) {
                        VoiceTab.LIVE -> "Trans"
                        VoiceTab.TRANSLATE -> "Notes"
                        VoiceTab.NOTES -> "Rec"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = OrbFgStrong
                )
            }
        }
    }
}

@Composable
private fun PhoneAndCoverBody(
    recState: RecorderState,
    npuStatus: NpuHardwareStatus,
    notes: List<VoiceNote>,
    activeTab: VoiceTab,
    isCoverScreen: Boolean,
    keepScreenOn: Boolean,
    isDarkTheme: Boolean,
    translateLangA: String,
    translateLangB: String,
    onUpdateTranslatePair: (String, String) -> Unit,
    onToggleTheme: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onSelectTab: (VoiceTab) -> Unit,
    onToggleRecord: () -> Unit,
    onSelectNote: (VoiceNote) -> Unit,
    onRenameLiveSpeaker: (Int, String) -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = if (isCoverScreen) 14.dp else 20.dp)
            .padding(top = if (isCoverScreen) 8.dp else 14.dp)
    ) {
        TopOrbBar(
            recState = recState,
            npuStatus = npuStatus,
            compact = isCoverScreen,
            keepScreenOn = keepScreenOn,
            isDarkTheme = isDarkTheme,
            onToggleTheme = onToggleTheme,
            onToggleKeepScreenOn = onToggleKeepScreenOn,
            onOpenSettings = onOpenSettings
        )

        Spacer(modifier = Modifier.height(if (isCoverScreen) 8.dp else 16.dp))

        if (!isCoverScreen) {
            HeroRecorderCard(recState = recState, onToggleRecord = onToggleRecord)
            Spacer(modifier = Modifier.height(14.dp))
            SegmentedTabBar(activeTab = activeTab, notesCount = notes.size, onSelect = onSelectTab)
            Spacer(modifier = Modifier.height(10.dp))
        } else if (recState.isRecording) {
            WaveformBarCanvas(
                levels = recState.levels,
                modifier = Modifier.fillMaxWidth().height(24.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (activeTab == VoiceTab.TRANSLATE) {
            TranslateLangBar(
                langA = translateLangA,
                langB = translateLangB,
                onUpdate = onUpdateTranslatePair
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (activeTab == VoiceTab.LIVE || activeTab == VoiceTab.TRANSLATE) {
            LiveTranscriptStream(
                recState = recState,
                bottomPadding = if (isCoverScreen) 78.dp else 16.dp,
                onRenameSpeaker = onRenameLiveSpeaker,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            NotesListPane(
                notes = notes,
                bottomPadding = if (isCoverScreen) 78.dp else 16.dp,
                onSelectNote = onSelectNote,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun TranslateLangBar(
    langA: String,
    langB: String,
    onUpdate: (String, String) -> Unit
) {
    val langs = OrbRouterConfig.SUPPORTED_LANGUAGES
    fun nextLang(cur: String, other: String): String {
        val idx = langs.indexOf(cur).coerceAtLeast(0)
        for (offset in 1..langs.size) {
            val cand = langs[(idx + offset) % langs.size]
            if (cand != other) return cand
        }
        return cur
    }
    Surface(
        color = OrbSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, OrbBlue.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(OrbSurfaceHover)
                    .clickable { onUpdate(nextLang(langA, langB), langB) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(langA, style = MaterialTheme.typography.labelLarge, color = OrbFgStrong)
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(OrbBlue.copy(alpha = 0.16f))
                    .clickable { onUpdate(langB, langA) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.SwapHoriz, "Swap languages", tint = OrbBlue, modifier = Modifier.size(16.dp))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(OrbSurfaceHover)
                    .clickable { onUpdate(langA, nextLang(langB, langA)) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(langB, style = MaterialTheme.typography.labelLarge, color = OrbFgStrong)
            }
        }
    }
}

@Composable
private fun WideTwoPaneLayout(
    recState: RecorderState,
    npuStatus: NpuHardwareStatus,
    notes: List<VoiceNote>,
    selectedNote: VoiceNote?,
    keepScreenOn: Boolean,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleRecord: () -> Unit,
    onSelectNote: (VoiceNote) -> Unit,
    onRenameLiveSpeaker: (Int, String) -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 16.dp)
    ) {
        TopOrbBar(
            recState = recState,
            npuStatus = npuStatus,
            compact = false,
            keepScreenOn = keepScreenOn,
            isDarkTheme = isDarkTheme,
            onToggleTheme = onToggleTheme,
            onToggleKeepScreenOn = onToggleKeepScreenOn,
            onOpenSettings = onOpenSettings
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(modifier = Modifier.weight(0.95f).fillMaxHeight()) {
                HeroRecorderCard(recState = recState, onToggleRecord = onToggleRecord)
                Spacer(modifier = Modifier.height(14.dp))
                NotesListPane(
                    notes = notes,
                    bottomPadding = 16.dp,
                    onSelectNote = onSelectNote,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
            Surface(
                color = OrbSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, OrbBorder),
                modifier = Modifier.weight(1.05f).fillMaxHeight()
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                    Text(
                        text = if (recState.isRecording) "LIVE MULTI-SPEAKER STREAM" else "LIVE STREAM (TAP SPEAKER TO LABEL)",
                        fontSize = 11.sp,
                        letterSpacing = 0.8.sp,
                        color = OrbFgSubtle
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LiveTranscriptStream(
                        recState = recState,
                        bottomPadding = 12.dp,
                        onRenameSpeaker = onRenameLiveSpeaker,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TopOrbBar(
    recState: RecorderState,
    npuStatus: NpuHardwareStatus,
    compact: Boolean,
    keepScreenOn: Boolean,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val dotColor = when {
        recState.isRecording -> OrbRed
        recState.isProcessing -> OrbAmber
        else -> OrbGreen
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Text("Scribe", style = MaterialTheme.typography.titleMedium, color = OrbFgStrong)
            StatusChip(npuStatus.badgeLabel, if (npuStatus.isReady) OrbGreen else OrbFgMuted)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!compact) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(OrbSurface)
                        .border(1.dp, OrbBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, tint = OrbFgMuted, modifier = Modifier.size(13.dp))
                    Text("Documents/OrbVoice", style = MaterialTheme.typography.labelSmall, color = OrbFgMuted)
                }
            }
            if (recState.isRecording) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (keepScreenOn) OrbAmber.copy(alpha = 0.22f) else OrbSurfaceHover)
                        .border(
                            1.dp,
                            if (keepScreenOn) OrbAmber.copy(alpha = 0.65f) else OrbBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable(onClick = onToggleKeepScreenOn),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Coffee,
                        contentDescription = if (keepScreenOn) "Stay Awake ON" else "Stay Awake OFF",
                        tint = if (keepScreenOn) OrbAmber else OrbFgMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(OrbSurfaceHover)
                    .clickable(onClick = onToggleTheme),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Rounded.WbSunny else Icons.Rounded.DarkMode,
                    contentDescription = if (isDarkTheme) "Switch to Light Theme" else "Switch to Dark Theme",
                    tint = OrbFgMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(OrbSurfaceHover)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Tune, "Settings", tint = OrbFgMuted, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun HeroRecorderCard(recState: RecorderState, onToggleRecord: () -> Unit) {
    Surface(
        color = OrbSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (recState.isRecording) OrbRed.copy(alpha = 0.55f) else OrbBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            recState.isRecording -> "Live · ${VoiceNoteStore.formatTimestamp(recState.elapsedMs)}"
                            recState.isProcessing -> "Saving & indexing…"
                            else -> "Audio Notes"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = OrbFgStrong
                    )
                    Text(
                        text = recState.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = OrbFgMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (recState.isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = OrbAmber)
                }
            }

            AnimatedVisibility(visible = recState.isRecording) {
                WaveformBarCanvas(levels = recState.levels, modifier = Modifier.fillMaxWidth().height(40.dp))
            }

            Button(
                onClick = onToggleRecord,
                enabled = !recState.isProcessing,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (recState.isRecording) OrbRed else OrbFgStrong,
                    contentColor = if (recState.isRecording) OrbFgStrong else OrbBg
                ),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(
                    imageVector = if (recState.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (recState.isRecording) "Stop & Save Note" else "Record Audio Note",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun SegmentedTabBar(activeTab: VoiceTab, notesCount: Int, onSelect: (VoiceTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(OrbSurface)
            .border(1.dp, OrbBorder, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        VoiceTab.entries.forEach { tab ->
            val selected = tab == activeTab
            val label = if (tab == VoiceTab.NOTES) "Notes ($notesCount)" else tab.label
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) OrbSurfaceHover else androidx.compose.ui.graphics.Color.Transparent)
                    .then(if (selected) Modifier.border(1.dp, OrbBorder, RoundedCornerShape(7.dp)) else Modifier)
                    .clickable { onSelect(tab) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) OrbFgStrong else OrbFgMuted
                )
            }
        }
    }
}

private data class SpeakerTurnGroup(
    val speakerIndex: Int,
    val speakerName: String,
    val startMs: Long,
    val endMs: Long,
    val paragraphs: List<String>,
    val translations: List<String> = emptyList(),
    val livePartial: String? = null
)

private fun groupConsecutiveSegments(
    segments: List<TranscriptSegment>,
    activeSpeakerIndex: Int? = null,
    activeSpeakerName: String? = null,
    partialText: String = "",
    elapsedMs: Long = 0L
): List<SpeakerTurnGroup> {
    val groups = mutableListOf<SpeakerTurnGroup>()
    for (seg in segments) {
        val last = groups.lastOrNull()
        val tr = seg.translatedText.trim()
        if (last != null && last.speakerIndex == seg.speakerIndex) {
            groups[groups.lastIndex] = last.copy(
                speakerName = seg.speakerName,
                endMs = maxOf(last.endMs, seg.endMs),
                paragraphs = last.paragraphs + seg.text,
                translations = if (tr.isNotEmpty()) last.translations + tr else last.translations
            )
        } else {
            groups.add(
                SpeakerTurnGroup(
                    speakerIndex = seg.speakerIndex,
                    speakerName = seg.speakerName,
                    startMs = seg.startMs,
                    endMs = seg.endMs,
                    paragraphs = listOf(seg.text),
                    translations = if (tr.isNotEmpty()) listOf(tr) else emptyList()
                )
            )
        }
    }
    if (partialText.isNotBlank()) {
        val spkIdx = activeSpeakerIndex ?: groups.lastOrNull()?.speakerIndex ?: 0
        val spkName = activeSpeakerName ?: "Speaker ${spkIdx + 1}"
        val last = groups.lastOrNull()
        if (last != null && last.speakerIndex == spkIdx) {
            groups[groups.lastIndex] = last.copy(
                endMs = maxOf(last.endMs, elapsedMs),
                livePartial = partialText
            )
        } else {
            groups.add(
                SpeakerTurnGroup(
                    speakerIndex = spkIdx,
                    speakerName = spkName,
                    startMs = elapsedMs,
                    endMs = elapsedMs,
                    paragraphs = emptyList(),
                    livePartial = partialText
                )
            )
        }
    }
    return groups
}

@Composable
private fun LiveTranscriptStream(
    recState: RecorderState,
    bottomPadding: Dp,
    onRenameSpeaker: (Int, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val speakerColors = LocalOrbPalette.current.speakerColors
    val listState = rememberLazyListState()
    val activeIdx = recState.activeSpeakerIndex ?: 0
    val activeName = recState.speakerNames[activeIdx] ?: "Speaker ${activeIdx + 1}"
    val groups = remember(recState.liveSegments, recState.partialText, recState.activeSpeakerIndex, recState.speakerNames) {
        groupConsecutiveSegments(
            segments = recState.liveSegments,
            activeSpeakerIndex = activeIdx,
            activeSpeakerName = activeName,
            partialText = recState.partialText,
            elapsedMs = recState.elapsedMs
        )
    }

    val lastChars = (groups.lastOrNull()?.paragraphs?.sumOf { it.length } ?: 0) + recState.partialText.length
    LaunchedEffect(groups.size, recState.liveSegments.size, lastChars) {
        if (groups.isNotEmpty()) {
            // Scroll to the trailing bottom_anchor item so even a tall multi-sentence speaker card
            // stays pinned to its latest line at the bottom of the screen.
            listState.scrollToItem(groups.size)
        }
    }

    if (groups.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.GraphicEq, null, tint = OrbFgSubtle, modifier = Modifier.size(26.dp))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (recState.isRecording) "Listening — speak to see live colored voices…" else "Tap Rec to stream multi-speaker text live",
                    style = MaterialTheme.typography.bodySmall,
                    color = OrbFgSubtle
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = bottomPadding)
    ) {
        items(groups) { grp ->
            val sColor = speakerColors[grp.speakerIndex % speakerColors.size]
            val ts = if (grp.endMs > grp.startMs + 2000L) {
                "${VoiceNoteStore.formatTimestamp(grp.startMs)} – ${VoiceNoteStore.formatTimestamp(grp.endMs)}"
            } else {
                VoiceNoteStore.formatTimestamp(grp.startMs)
            }
            SpeakerGroupCard(
                speakerName = grp.speakerName,
                timestamp = ts,
                paragraphs = grp.paragraphs,
                translations = grp.translations,
                livePartial = grp.livePartial,
                color = sColor,
                useSerif = false,
                onClickSpeaker = { onRenameSpeaker(grp.speakerIndex, grp.speakerName) }
            )
        }
        item(key = "bottom_anchor") {
            Spacer(modifier = Modifier.height(1.dp))
        }
    }
}

@Composable
private fun SpeakerGroupCard(
    speakerName: String,
    timestamp: String,
    paragraphs: List<String>,
    translations: List<String> = emptyList(),
    livePartial: String? = null,
    color: androidx.compose.ui.graphics.Color,
    useSerif: Boolean,
    onClickSpeaker: () -> Unit
) {
    val bodyStyle = if (useSerif) {
        MaterialTheme.typography.bodyMedium.copy(fontFamily = OrbSerifFont, fontSize = 15.sp, lineHeight = 22.sp)
    } else {
        MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)
    }
    Surface(
        color = OrbSurface,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, color.copy(alpha = if (livePartial != null) 0.45f else 0.22f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Subtle colored vertical speaker rail
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(((paragraphs.size + if (livePartial != null) 1 else 0) * 22 + 24).coerceIn(36, 220).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.78f))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(color.copy(alpha = 0.16f))
                            .clickable(onClick = onClickSpeaker)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
                        Text(
                            text = if (livePartial != null) "$speakerName · live" else speakerName,
                            style = MaterialTheme.typography.labelMedium,
                            color = color
                        )
                    }
                    Text(text = timestamp, style = MaterialTheme.typography.labelSmall, color = OrbFgSubtle)
                }
                if (paragraphs.isNotEmpty()) {
                    Text(
                        text = paragraphs.joinToString(" "),
                        style = if (translations.isNotEmpty()) MaterialTheme.typography.bodySmall else bodyStyle,
                        color = if (translations.isNotEmpty()) OrbFgMuted else OrbFgStrong
                    )
                }
                if (translations.isNotEmpty()) {
                    Text(
                        text = "→ ${translations.joinToString(" ")}",
                        style = bodyStyle,
                        color = OrbFgStrong
                    )
                }
                if (!livePartial.isNullOrBlank()) {
                    Text(
                        text = "${livePartial}▍",
                        style = bodyStyle,
                        color = OrbFgMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesListPane(
    notes: List<VoiceNote>,
    bottomPadding: Dp,
    onSelectNote: (VoiceNote) -> Unit,
    modifier: Modifier = Modifier
) {
    if (notes.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No saved audio notes yet", style = MaterialTheme.typography.bodySmall, color = OrbFgSubtle)
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        items(notes, key = { it.id }) { note ->
            NoteCard(note = note, onClick = { onSelectNote(note) })
        }
    }
}

@Composable
private fun StatusChip(text: String, tint: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(OrbSurface)
            .border(1.dp, OrbBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun WaveformBarCanvas(levels: List<Float>, modifier: Modifier = Modifier) {
    val barColor = OrbRed
    val idleColor = OrbBorder
    Canvas(modifier = modifier) {
        val barCount = 48
        val gap = 3.dp.toPx()
        val barW = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
        val midY = size.height / 2f
        val startOffset = (barCount - levels.size).coerceAtLeast(0)
        for (i in 0 until barCount) {
            val lvl = if (i >= startOffset) levels[i - startOffset] else 0.06f
            val h = (size.height * lvl).coerceAtLeast(3.dp.toPx())
            val x = i * (barW + gap)
            drawRoundRect(
                color = if (i >= startOffset) barColor else idleColor,
                topLeft = Offset(x, midY - h / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

@Composable
private fun NoteCard(note: VoiceNote, onClick: () -> Unit) {
    val preview = note.segments.firstOrNull()?.let { "${it.speakerName}: ${it.text}" }
        ?: "Tap to inspect audio & transcript"
    Surface(
        color = OrbSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, OrbBorder),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = OrbFgStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = VoiceNoteStore.formatTimestamp(note.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = OrbFgMuted
                )
            }
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = OrbFgMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip("${note.speakerCount} voice${if (note.speakerCount > 1) "s" else ""}", OrbBlue)
                StatusChip(File(note.audioPath).extension.uppercase(), OrbFgMuted)
                if (note.syncedToSpark) StatusChip("Spark", OrbGreen)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteDetailSheet(
    note: VoiceNote,
    isSyncing: Boolean,
    isDeepPolishing: Boolean,
    deepModelLabel: String,
    onDismiss: () -> Unit,
    onRenameSpeaker: (Int, String) -> Unit,
    onShareTranscript: () -> Unit,
    onShareAudio: () -> Unit,
    onDeepAudioPolish: () -> Unit,
    onSyncSpark: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val speakerColors = LocalOrbPalette.current.speakerColors
    var isPlaying by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(note.id) {
        onDispose {
            try { player?.stop(); player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    fun togglePlayback() {
        if (isPlaying) {
            try { player?.pause() } catch (_: Exception) {}
            isPlaying = false
            return
        }
        try {
            val mp = player ?: MediaPlayer().apply {
                setDataSource(note.audioPath)
                prepare()
                setOnCompletionListener { isPlaying = false }
            }.also { player = it }
            mp.start()
            isPlaying = true
        } catch (_: Exception) {
            isPlaying = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OrbSurface,
        contentColor = OrbFgStrong
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(note.title, style = MaterialTheme.typography.titleMedium, color = OrbFgStrong)
                    Text(
                        "${VoiceNoteStore.formatTimestamp(note.durationMs)} · ${note.engineUsed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = OrbFgMuted
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete",
                    tint = OrbRed,
                    modifier = Modifier.size(22.dp).clickable(onClick = onDelete)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill(
                    label = if (isPlaying) "Pause" else "Play",
                    icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    tint = OrbFgStrong,
                    onClick = ::togglePlayback,
                    modifier = Modifier.weight(1f)
                )
                ActionPill("Share .md", Icons.Rounded.Share, OrbBlue, onShareTranscript, Modifier.weight(1f))
                ActionPill("Share Audio", Icons.Rounded.Share, OrbFgMuted, onShareAudio, Modifier.weight(1f))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill(
                    label = if (isDeepPolishing) "Polishing…" else "Deep Audio ($deepModelLabel)",
                    icon = Icons.Rounded.AutoAwesome,
                    tint = OrbAmber,
                    onClick = { if (!isDeepPolishing) onDeepAudioPolish() },
                    modifier = Modifier.weight(1.25f)
                )
                ActionPill(
                    label = if (isSyncing) "Syncing…" else if (note.syncedToSpark) "Synced" else "Spark Sync",
                    icon = Icons.Rounded.CloudUpload,
                    tint = if (note.syncedToSpark) OrbGreen else OrbFgStrong,
                    onClick = { if (!isSyncing) onSyncSpark() },
                    modifier = Modifier.weight(0.75f)
                )
            }

            if (note.summary.isNotBlank()) {
                Surface(
                    color = OrbBg,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, OrbBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = note.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = OrbFgMuted,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            HorizontalDivider(color = OrbBorder)

            Text(
                text = "TRANSCRIPT (TAP SPEAKER TO LABEL)",
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = OrbFgSubtle
            )

            val groupedNoteTurns = remember(note.segments) { groupConsecutiveSegments(note.segments) }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(300.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(groupedNoteTurns) { grp ->
                    val sColor = speakerColors[grp.speakerIndex % speakerColors.size]
                    SpeakerGroupCard(
                        speakerName = grp.speakerName,
                        timestamp = "${VoiceNoteStore.formatTimestamp(grp.startMs)} – ${VoiceNoteStore.formatTimestamp(grp.endMs)}",
                        paragraphs = grp.paragraphs,
                        translations = grp.translations,
                        color = sColor,
                        useSerif = true,
                        onClickSpeaker = { onRenameSpeaker(grp.speakerIndex, grp.speakerName) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsModalSheet(
    npuStatus: NpuHardwareStatus,
    sparkAsrUrl: String,
    sparkMemUrl: String,
    orbCfg: OrbRouterConfig,
    knownVoices: List<VoiceprintProfile>,
    onDeleteVoice: (String) -> Unit,
    onSave: (String, String, OrbRouterConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val speakerColors = LocalOrbPalette.current.speakerColors
    var asrDraft by remember { mutableStateOf(sparkAsrUrl) }
    var memDraft by remember { mutableStateOf(sparkMemUrl) }
    var orbUrlDraft by remember { mutableStateOf(orbCfg.baseUrl) }
    var orbKeyDraft by remember { mutableStateOf(orbCfg.apiKey) }
    var fastModelDraft by remember { mutableStateOf(orbCfg.fastModel) }
    var deepModelDraft by remember { mutableStateOf(orbCfg.deepAudioModel) }
    var livePolishDraft by remember { mutableStateOf(orbCfg.livePolishEnabled) }
    var autoTranslateDraft by remember { mutableStateOf(orbCfg.autoTranslateTarget) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OrbSurface,
        contentColor = OrbFgStrong
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Scribe Settings", style = MaterialTheme.typography.titleMedium, color = OrbFgStrong)
            Text(
                text = "Hardware: ${npuStatus.badgeLabel} · WeSpeaker ResNet293-LM · Parakeet-TDT-0.6B-v3",
                style = MaterialTheme.typography.labelSmall,
                color = if (npuStatus.isReady) OrbGreen else OrbFgMuted
            )
            HorizontalDivider(color = OrbBorder)

            Text(
                text = "KNOWN VOICES (${knownVoices.size} SAVED 256-D SIGNATURES)",
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = OrbFgSubtle
            )
            if (knownVoices.isEmpty()) {
                Text(
                    text = "Tap any colored speaker pill while recording to save their voice signature.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OrbFgMuted
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    knownVoices.forEachIndexed { idx, vp ->
                        val c = speakerColors[idx % speakerColors.size]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(OrbBg)
                                .border(1.dp, OrbBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(c))
                                Text(vp.name, style = MaterialTheme.typography.labelLarge, color = OrbFgStrong)
                                Text(
                                    "${vp.embedding.size}D · w=${vp.sampleWeight.toInt()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OrbFgSubtle
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Delete voiceprint",
                                tint = OrbRed,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onDeleteVoice(vp.name) }
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = OrbBorder)

            Text(
                text = "ORB ROUTER LLM POLISH & QUORUM",
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = OrbFgSubtle
            )
            OutlinedTextField(
                value = orbUrlDraft,
                onValueChange = { orbUrlDraft = it },
                label = { Text("Orb Router Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = orbKeyDraft,
                onValueChange = { orbKeyDraft = it },
                label = { Text("Orb Router API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = fastModelDraft,
                onValueChange = { fastModelDraft = it },
                label = { Text("Live & Summary Model (A + B)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = deepModelDraft,
                onValueChange = { deepModelDraft = it },
                label = { Text("Deep Audio / Quorum Model (C)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live rolling autocorrect while recording", style = MaterialTheme.typography.bodySmall, color = OrbFgStrong)
                Switch(checked = livePolishDraft, onCheckedChange = { livePolishDraft = it })
            }
            Text(
                text = "AUTO-TRANSLATE TARGET (RECORD MODE)",
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = OrbFgSubtle
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val options = listOf("" to "Off") + OrbRouterConfig.SUPPORTED_LANGUAGES.take(4).map { it to it.take(2).uppercase() }
                options.forEach { (value, shortLabel) ->
                    val selected = autoTranslateDraft.equals(value, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) OrbBlue.copy(alpha = 0.22f) else OrbBg)
                            .border(1.dp, if (selected) OrbBlue else OrbBorder, RoundedCornerShape(8.dp))
                            .clickable { autoTranslateDraft = value }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) OrbBlue else OrbFgMuted
                        )
                    }
                }
            }

            HorizontalDivider(color = OrbBorder)
            Text(
                text = "DGX SPARK LOCAL MESH",
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = OrbFgSubtle
            )
            OutlinedTextField(
                value = asrDraft,
                onValueChange = { asrDraft = it },
                label = { Text("DGX Spark ASR Endpoint") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = memDraft,
                onValueChange = { memDraft = it },
                label = { Text("DGX Spark Memory Ingest URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    onSave(
                        asrDraft,
                        memDraft,
                        OrbRouterConfig(
                            baseUrl = orbUrlDraft,
                            apiKey = orbKeyDraft,
                            fastModel = fastModelDraft,
                            deepAudioModel = deepModelDraft,
                            livePolishEnabled = livePolishDraft,
                            autoTranslateTarget = autoTranslateDraft,
                            translateLangA = orbCfg.translateLangA,
                            translateLangB = orbCfg.translateLangB
                        )
                    )
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OrbFgStrong, contentColor = OrbBg),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text("Save Settings", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = OrbBg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, OrbBorder),
        modifier = modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = OrbFgStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
