package com.th0rgal.omarchdroid

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Lens
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        setContent {
            val useDark = isSystemInDarkTheme()
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = if (useDark) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    },
                    navigationBarStyle = if (useDark) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    },
                )
            }

            OmarchTheme(darkTheme = useDark) {
                OmarchdroidRoot(
                    onToggleIsland = { toggleIsland() },
                    onRequestOverlay = { requestOverlayPermission() },
                    onRequestNotificationListener = { requestNotificationListener() },
                    onLaunchPackage = { pkg -> launchPackage(pkg) },
                    onOpenPersonalize = { openMotoPersonalize() },
                )
            }
        }
    }

    private fun toggleIsland() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        val running = IslandState.snapshot.value.overlayRunning
        if (running) {
            startService(Intent(this, IslandOverlayService::class.java).apply {
                action = IslandOverlayService.ACTION_STOP
            })
        } else {
            startForegroundService(Intent(this, IslandOverlayService::class.java))
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun requestNotificationListener() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun launchPackage(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun openMotoPersonalize() {
        val intent = packageManager.getLaunchIntentForPackage("com.motorola.personalize")
            ?: Intent(Settings.ACTION_DISPLAY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}

@Composable
private fun OmarchdroidRoot(
    onToggleIsland: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestNotificationListener: () -> Unit,
    onLaunchPackage: (String) -> Unit,
    onOpenPersonalize: () -> Unit,
) {
    val palette = LocalOmarchPalette.current
    val context = LocalContext.current
    val state by IslandState.snapshot.collectAsState()

    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var batteryPct by remember { mutableIntStateOf(readBatteryPct(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            batteryPct = readBatteryPct(context)
            delay(15_000L)
        }
    }

    val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
    val cutoutBottom = cutoutPadding.calculateBottomPadding()
    val cutoutEnd = cutoutPadding.calculateEndPadding(LocalLayoutDirection.current)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg),
    ) {
        val isWideScreen = maxWidth >= 640.dp
        val isCoverScreen = !isWideScreen &&
            (maxHeight < 620.dp || maxWidth < 380.dp || cutoutBottom > 0.dp)
        val cameraSafeEndPadding = max(230.dp, cutoutEnd + 14.dp)

        if (isCoverScreen) {
            CoverScreenPanel(
                nowMs = nowMs,
                batteryPct = batteryPct,
                state = state,
                cameraSafeEndPadding = cameraSafeEndPadding,
                onToggleIsland = onToggleIsland,
                onLaunchPackage = onLaunchPackage,
            )
        } else {
            InnerControlCenter(
                nowMs = nowMs,
                batteryPct = batteryPct,
                state = state,
                canOverlay = Settings.canDrawOverlays(context),
                onToggleIsland = onToggleIsland,
                onRequestOverlay = onRequestOverlay,
                onRequestNotificationListener = onRequestNotificationListener,
                onLaunchPackage = onLaunchPackage,
                onOpenPersonalize = onOpenPersonalize,
            )
        }
    }
}

@Composable
private fun CoverScreenPanel(
    nowMs: Long,
    batteryPct: Int,
    state: IslandSnapshot,
    cameraSafeEndPadding: androidx.compose.ui.unit.Dp,
    onToggleIsland: () -> Unit,
    onLaunchPackage: (String) -> Unit,
) {
    val palette = LocalOmarchPalette.current
    val timeText = remember(nowMs) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs)) }
    val dateText = remember(nowMs) {
        SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(nowMs)).uppercase(Locale.getDefault())
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Top editorial Michelangelus clock + Lilex telemetry chip
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dateText,
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp,
                    color = palette.fgSubtle,
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.surface)
                        .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (state.overlayRunning) palette.green else palette.fgSubtle),
                    )
                    Text(
                        text = "$batteryPct%",
                        style = TextStyle(
                            fontFamily = LilexMono,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            fontFeatureSettings = "tnum",
                            color = palette.fgStrong,
                        ),
                    )
                }
            }

            Text(
                text = timeText,
                style = TextStyle(
                    fontFamily = Michelangelus,
                    fontSize = 62.sp,
                    lineHeight = 64.sp,
                    color = palette.fgStrong,
                ),
            )

            if (state.media != null) {
                Text(
                    text = state.media.title,
                    fontFamily = IbmPlexSans,
                    fontSize = 12.sp,
                    color = palette.fgMuted,
                    maxLines = 1,
                )
            }
        }

        // Bottom-left floating pill dock (respects twin camera rings in bottom-right corner)
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, end = cameraSafeEndPadding, bottom = 12.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(palette.surface)
                .border(1.dp, palette.border, RoundedCornerShape(22.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverDockButton(
                icon = Icons.Rounded.Mic,
                tint = if (state.scribeActive) palette.red else palette.fgStrong,
                contentDescription = "Scribe",
                onClick = { onLaunchPackage(IslandState.PKG_SCRIBE) },
            )
            CoverDockButton(
                icon = Icons.Rounded.Terminal,
                tint = if (state.sideSystemActive) palette.green else palette.fgStrong,
                contentDescription = "SideSystem",
                onClick = { onLaunchPackage(IslandState.PKG_SIDESYSTEM) },
            )
            CoverDockButton(
                icon = Icons.Rounded.Lens,
                tint = if (state.overlayRunning) palette.green else palette.fgMuted,
                contentDescription = "Toggle Island",
                onClick = onToggleIsland,
            )
        }
    }
}

@Composable
private fun CoverDockButton(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val palette = LocalOmarchPalette.current
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(palette.surfaceHover)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun InnerControlCenter(
    nowMs: Long,
    batteryPct: Int,
    state: IslandSnapshot,
    canOverlay: Boolean,
    onToggleIsland: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestNotificationListener: () -> Unit,
    onLaunchPackage: (String) -> Unit,
    onOpenPersonalize: () -> Unit,
) {
    val palette = LocalOmarchPalette.current
    val timeText = remember(nowMs) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "OMARCHDROID SHELL",
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp,
                    color = palette.fgSubtle,
                )
                Text(
                    text = "Razr 60 Ultra",
                    fontFamily = Michelangelus,
                    fontSize = 30.sp,
                    color = palette.fgStrong,
                )
            }
            Text(
                text = "$timeText · $batteryPct%",
                style = TextStyle(
                    fontFamily = LilexMono,
                    fontSize = 12.sp,
                    fontFeatureSettings = "tnum",
                    color = palette.fgMuted,
                ),
            )
        }

        // Primary monochrome Hero CTA
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.fgStrong)
                .clickable(onClick = onToggleIsland),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (state.overlayRunning) palette.green else palette.fgMuted),
                )
                Text(
                    text = if (state.overlayRunning) "Stop Dynamic Island" else "Start Dynamic Island",
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = palette.bg,
                )
            }
        }

        // Permission status chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusPill(
                label = if (canOverlay) "Overlay Ready" else "Grant Overlay",
                active = canOverlay,
                modifier = Modifier.weight(1f),
                onClick = onRequestOverlay,
            )
            StatusPill(
                label = if (state.listenerConnected) "Listener Live" else "Grant Notifications",
                active = state.listenerConnected,
                modifier = Modifier.weight(1f),
                onClick = onRequestNotificationListener,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "ECOSYSTEM",
            fontFamily = IbmPlexSans,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
            color = palette.fgSubtle,
        )

        ActionRow(
            icon = Icons.Rounded.Mic,
            title = "Scribe",
            subtitle = if (state.scribeActive) state.scribeLabel else "Real-time transcription & translation",
            accent = if (state.scribeActive) palette.red else palette.fgMuted,
            onClick = { onLaunchPackage(IslandState.PKG_SCRIBE) },
        )
        ActionRow(
            icon = Icons.Rounded.Terminal,
            title = "SideSystem",
            subtitle = if (state.sideSystemActive) state.sideSystemLabel else "NixOS + Hyprland Linux container",
            accent = if (state.sideSystemActive) palette.green else palette.fgMuted,
            onClick = { onLaunchPackage(IslandState.PKG_SIDESYSTEM) },
        )
        ActionRow(
            icon = Icons.Rounded.Apps,
            title = "Kvaesitso Launcher",
            subtitle = "Spotlight-style search-first Home",
            accent = palette.fgMuted,
            onClick = { onLaunchPackage(IslandState.PKG_KVAESITSO) },
        )
        ActionRow(
            icon = Icons.Rounded.TextFields,
            title = "System Fonts (Personalize)",
            subtitle = "IBM Plex Sans · Michelangelus · Lilex",
            accent = palette.fgMuted,
            onClick = onOpenPersonalize,
        )
    }
}

@Composable
private fun StatusPill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalOmarchPalette.current
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.surface)
            .border(1.dp, palette.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) palette.green else palette.amber),
        )
        Text(
            text = label,
            fontFamily = IbmPlexSans,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = palette.fgStrong,
        )
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
) {
    val palette = LocalOmarchPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surface)
            .border(1.dp, palette.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = IbmPlexSans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = palette.fgStrong,
            )
            Text(
                text = subtitle,
                fontFamily = IbmPlexSans,
                fontSize = 12.sp,
                color = palette.fgMuted,
            )
        }
    }
}

private fun readBatteryPct(context: Context): Int {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
}
