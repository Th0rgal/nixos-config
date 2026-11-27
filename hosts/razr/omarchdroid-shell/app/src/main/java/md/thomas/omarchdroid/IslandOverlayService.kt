package md.thomas.omarchdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class OverlayComposeOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performAttach()
        savedStateController.performRestore(Bundle())
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}

class IslandOverlayService : LifecycleService() {

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var overlayOwner: OverlayComposeOwner? = null
    private var lastAppliedDark: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        attachOverlay()
        IslandState.setOverlayRunning(true)
        syncWallpaperForMode(isNightMode(resources.configuration))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncWallpaperForMode(isNightMode(newConfig))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SYNC_WALLPAPER) {
            lastAppliedDark = null
            syncWallpaperForMode(isNightMode(resources.configuration))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        IslandState.setOverlayRunning(false)
        composeView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: Throwable) {
            }
        }
        composeView = null
        overlayOwner?.stop()
        overlayOwner = null
        super.onDestroy()
    }

    private fun isNightMode(config: Configuration): Boolean {
        return (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun syncWallpaperForMode(isDark: Boolean) {
        if (lastAppliedDark == isDark) return
        val fileName = if (isDark) "omarchnix-dark.jpg" else "omarchnix-light.jpg"
        val file = File("/sdcard/Pictures/OmarchnixWallpapers/$fileName")
        if (!file.exists()) return
        lastAppliedDark = isDark
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@launch
                val wm = WallpaperManager.getInstance(this@IslandOverlayService)
                wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                bmp.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Omarchdroid Shell",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            description = "Dynamic Island (active-only) & Dark/Light wallpaper sync"
        }
        nm.createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Omarchdroid Shell")
            .setContentText("Island & Dark/Light wallpaper sync active")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()

        startForeground(NOTIF_ID, notif)
    }

    private fun attachOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val metrics = wm.currentWindowMetrics
        val cutoutRect = metrics.windowInsets.displayCutout?.boundingRects?.firstOrNull()
        val density = resources.displayMetrics.density

        // Razr 60 Ultra inner display: cutoutRect = Rect(554, 0 - 670, 130) @ 3.0 density.
        // Visible active pill wraps the lens and extends ~18px past the 130px StatusBar strip
        // so tapping the pill is always clickable; hidden completely (GONE) when idle at rest.
        val cutoutBottomPx = cutoutRect?.bottom?.takeIf { it > 0 } ?: (43 * density).toInt()
        val topOffsetPx = (6 * density).toInt()
        val activePillHeightDp = ((cutoutBottomPx - topOffsetPx) / density + 6f).coerceIn(38f, 48f)
        val cutoutWidthDp = if (cutoutRect != null && cutoutRect.width() < metrics.bounds.width() / 2) {
            (cutoutRect.width() / density).coerceIn(34f, 44f)
        } else {
            39f
        }

        val owner = OverlayComposeOwner().also { it.start() }
        overlayOwner = owner

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                DynamicIslandHost(
                    cutoutWidthDp = cutoutWidthDp,
                    pillHeightDp = activePillHeightDp,
                    onVisibilityChange = { visible ->
                        val target = if (visible) View.VISIBLE else View.GONE
                        if (this.visibility != target) {
                            this.visibility = target
                        }
                    },
                    onLaunchPackage = { pkg -> launchApp(pkg) },
                )
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topOffsetPx
            fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        view.visibility = View.GONE
        wm.addView(view, params)
        composeView = view
    }

    private fun launchApp(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    companion object {
        const val ACTION_STOP = "md.thomas.omarchdroid.STOP"
        const val ACTION_SYNC_WALLPAPER = "md.thomas.omarchdroid.SYNC_WALLPAPER"
        private const val CHANNEL_ID = "omarchdroid_island"
        private const val NOTIF_ID = 4201
    }
}

@Composable
private fun DynamicIslandHost(
    cutoutWidthDp: Float,
    pillHeightDp: Float,
    onVisibilityChange: (Boolean) -> Unit,
    onLaunchPackage: (String) -> Unit,
) {
    val state by IslandState.snapshot.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val hasRecentNotif = state.latestNotification?.let { (nowMs - it.timestampMs) < 9_000L } == true
    val hasLiveActivity = state.scribeActive || state.sideSystemActive || state.media?.isPlaying == true || hasRecentNotif

    LaunchedEffect(state.latestNotification?.timestampMs) {
        if (state.latestNotification != null) {
            nowMs = System.currentTimeMillis()
            delay(9_200L)
            nowMs = System.currentTimeMillis()
        }
    }

    val shouldShow = expanded || hasLiveActivity
    LaunchedEffect(shouldShow) {
        onVisibilityChange(shouldShow)
    }

    if (!shouldShow) return

    val statusDotColor = when {
        state.scribeActive -> ObsidianPalette.red
        state.sideSystemActive -> ObsidianPalette.green
        state.media?.isPlaying == true -> ObsidianPalette.blue
        else -> ObsidianPalette.fgStrong
    }

    val containerBg = if (expanded) Color(0xF5181818) else Color(0xFF000000)
    val containerBorder = if (expanded) ObsidianPalette.border else Color.White.copy(alpha = 0.14f)
    val corner = if (expanded) 22.dp else (pillHeightDp / 2f).dp

    Box(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded }
            .padding(bottom = if (expanded) 0.dp else 10.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(corner))
                .background(containerBg)
                .border(1.dp, containerBorder, RoundedCornerShape(corner))
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    )
                )
                .then(
                    if (!expanded) {
                        Modifier
                            .height(pillHeightDp.dp)
                            .padding(horizontal = 14.dp)
                    } else {
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!expanded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.scribeActive) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = "Scribe",
                            tint = ObsidianPalette.red,
                            modifier = Modifier.size(16.dp),
                        )
                    } else if (state.media?.isPlaying == true) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = "Media",
                            tint = ObsidianPalette.blue,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(statusDotColor),
                        )
                    }

                    Spacer(modifier = Modifier.width(cutoutWidthDp.dp))

                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusDotColor),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.widthIn(min = 270.dp, max = 336.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Spacer(modifier = Modifier.height((pillHeightDp - 14f).coerceAtLeast(18f).dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor),
                            )
                            Text(
                                text = when {
                                    state.scribeActive -> "SCRIBE LIVE"
                                    state.sideSystemActive -> "SIDESYSTEM"
                                    state.media != null -> "NOW PLAYING"
                                    else -> "OMARCHDROID"
                                },
                                fontFamily = IbmPlexSans,
                                fontWeight = FontWeight.Medium,
                                fontSize = 10.sp,
                                letterSpacing = 0.8.sp,
                                color = ObsidianPalette.fgMuted,
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Collapse",
                            tint = ObsidianPalette.fgMuted,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { expanded = false },
                        )
                    }

                    state.media?.let { media ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = media.title,
                                    fontFamily = IbmPlexSans,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    color = ObsidianPalette.fgStrong,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (media.artist.isNotEmpty()) {
                                    Text(
                                        text = media.artist,
                                        fontFamily = IbmPlexSans,
                                        fontSize = 11.sp,
                                        color = ObsidianPalette.fgMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (media.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = ObsidianPalette.fgStrong,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable {
                                        val tc = media.controller?.transportControls
                                        if (media.isPlaying) tc?.pause() else tc?.play()
                                    },
                            )
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Next",
                                tint = ObsidianPalette.fgMuted,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable {
                                        media.controller?.transportControls?.skipToNext()
                                    },
                            )
                        }
                    }

                    if (state.media == null) {
                        state.latestNotification?.let { notif ->
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ObsidianPalette.surfaceHover)
                                    .clickable {
                                        try {
                                            notif.contentIntent?.send()
                                        } catch (_: Throwable) {
                                        }
                                        expanded = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = notif.title.ifEmpty { notif.appLabel },
                                    fontFamily = IbmPlexSans,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = ObsidianPalette.fgStrong,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (notif.text.isNotEmpty()) {
                                    Text(
                                        text = notif.text,
                                        fontFamily = IbmPlexSans,
                                        fontSize = 11.sp,
                                        color = ObsidianPalette.fgMuted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IslandQuickChip(
                            label = if (state.scribeActive) "Scribe • Live" else "Scribe",
                            dotColor = if (state.scribeActive) ObsidianPalette.red else ObsidianPalette.fgSubtle,
                            icon = Icons.Rounded.Mic,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                expanded = false
                                onLaunchPackage(IslandState.PKG_SCRIBE)
                            },
                        )
                        IslandQuickChip(
                            label = if (state.sideSystemActive) "SideSystem • On" else "SideSystem",
                            dotColor = if (state.sideSystemActive) ObsidianPalette.green else ObsidianPalette.fgSubtle,
                            icon = Icons.Rounded.Terminal,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                expanded = false
                                onLaunchPackage(IslandState.PKG_SIDESYSTEM)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IslandQuickChip(
    label: String,
    dotColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianPalette.surfaceHover)
            .border(1.dp, ObsidianPalette.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = dotColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            fontFamily = IbmPlexSans,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            color = ObsidianPalette.fgStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
