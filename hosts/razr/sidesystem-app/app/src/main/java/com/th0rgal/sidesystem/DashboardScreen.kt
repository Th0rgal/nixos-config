package com.th0rgal.sidesystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import java.util.Locale

data class DashboardCallbacks(
    val onRefresh: () -> Unit,
    val onOpenDesktop: () -> Unit,
    val onContainerStart: () -> Unit,
    val onContainerRestart: () -> Unit,
    val onContainerStop: () -> Unit,
    val onDesktopStart: () -> Unit,
    val onDesktopRestart: () -> Unit,
    val onDesktopStop: () -> Unit,
    val onToggleAutostart: (Boolean) -> Unit,
    val onApplyDisplay: (String, DisplayScale) -> Unit,
    val onToggleBtHidGrab: (Boolean) -> Unit,
    val onSelectProfile: (PowerProfile) -> Unit,
    val onSelectGpuMode: (GpuMode) -> Unit,
    val onCopySsh: (String) -> Unit,
    val onToggleSshd: (Boolean) -> Unit,
    val onRequestBluetoothPermission: () -> Unit,
    val onOpenBluetoothSettings: () -> Unit,
    val onOpenCastSettings: () -> Unit,
    val onSendClipboard: () -> Unit,
    val onPullClipboard: () -> Unit,
    val onBackupNow: () -> Unit,
    val onOpenLogs: () -> Unit,
    val onDismissLogs: () -> Unit,
    val onRefreshLogs: () -> Unit,
    val onCopyLogs: (String) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    status: SideSystemStatus,
    telemetryHistory: List<TelemetrySample>,
    telemetryIntervalMs: Long,
    telemetryPaused: Boolean,
    onTelemetryIntervalChange: (Long) -> Unit,
    onToggleTelemetryPaused: () -> Unit,
    peripherals: List<PeripheralDevice>,
    hasBluetoothPermission: Boolean,
    isRefreshing: Boolean,
    busyAction: String?,
    showLogsSheet: Boolean,
    logsContent: String,
    isLoadingLogs: Boolean,
    snackbarHostState: SnackbarHostState,
    callbacks: DashboardCallbacks
) {
    var selectedResolution by remember { mutableStateOf(status.resolution) }
    var selectedScale by remember { mutableStateOf(status.scale) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showTelemetryExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(status.resolution, status.scale) {
        selectedResolution = status.resolution
        selectedScale = status.scale
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
                    Text(
                        text = data.visuals.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = OrbFgStrong,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
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
            val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
            val cutoutBottom = cutoutPadding.calculateBottomPadding()
            val cutoutEnd = cutoutPadding.calculateEndPadding(layoutDirection)

            val isWideScreen = maxWidth >= 640.dp
            // Razr 60 Ultra full-bleed cover display is 480dp x 565dp (1080x1272 @ 360dpi)
            // with twin cameras occupying the bottom-right 220dp x 155dp corner.
            val isCoverScreen = !isWideScreen &&
                (maxHeight < 620.dp || maxWidth < 380.dp || cutoutBottom > 0.dp)
            val cameraSafeEndPadding = max(230.dp, cutoutEnd + 14.dp)

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = callbacks.onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                if (isWideScreen) {
                    WideMiracastLayout(
                        status = status,
                        telemetryHistory = telemetryHistory,
                        peripherals = peripherals,
                        hasBluetoothPermission = hasBluetoothPermission,
                        busyAction = busyAction,
                        selectedResolution = selectedResolution,
                        selectedScale = selectedScale,
                        onResolutionChange = { selectedResolution = it },
                        onScaleChange = { selectedScale = it },
                        onExpandTelemetry = { showTelemetryExpanded = true },
                        callbacks = callbacks
                    )
                } else {
                    PhoneAndCoverLayout(
                        status = status,
                        telemetryHistory = telemetryHistory,
                        busyAction = busyAction,
                        isCoverScreen = isCoverScreen,
                        cameraSafeEndPadding = cameraSafeEndPadding,
                        onOpenSettings = { showSettingsSheet = true },
                        onExpandTelemetry = { showTelemetryExpanded = true },
                        callbacks = callbacks
                    )
                }
            }

            AnimatedVisibility(
                visible = showTelemetryExpanded && status.containerRunning,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                TelemetryExpandedOverlay(
                    status = status,
                    history = telemetryHistory,
                    intervalMs = telemetryIntervalMs,
                    paused = telemetryPaused,
                    isCoverScreen = isCoverScreen,
                    cameraSafeEndPadding = cameraSafeEndPadding,
                    onIntervalChange = onTelemetryIntervalChange,
                    onTogglePaused = onToggleTelemetryPaused,
                    onDismiss = { showTelemetryExpanded = false }
                )
            }

            if (showSettingsSheet && !isWideScreen) {
                SettingsModalSheet(
                    status = status,
                    peripherals = peripherals,
                    hasBluetoothPermission = hasBluetoothPermission,
                    busyAction = busyAction,
                    selectedResolution = selectedResolution,
                    selectedScale = selectedScale,
                    onResolutionChange = { selectedResolution = it },
                    onScaleChange = { selectedScale = it },
                    onDismiss = { showSettingsSheet = false },
                    callbacks = callbacks
                )
            }
        }
    }

    if (showLogsSheet) {
        LogsModalSheet(
            logsContent = logsContent,
            isLoading = isLoadingLogs,
            onDismiss = callbacks.onDismissLogs,
            onRefresh = callbacks.onRefreshLogs,
            onCopy = { callbacks.onCopyLogs(logsContent) }
        )
    }
}

@Composable
private fun PhoneAndCoverLayout(
    status: SideSystemStatus,
    telemetryHistory: List<TelemetrySample>,
    busyAction: String?,
    isCoverScreen: Boolean,
    cameraSafeEndPadding: Dp,
    onOpenSettings: () -> Unit,
    onExpandTelemetry: () -> Unit,
    callbacks: DashboardCallbacks
) {
    val horizontalPad = if (isCoverScreen) 14.dp else 20.dp
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrbBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = horizontalPad)
                .padding(
                    top = if (isCoverScreen) 8.dp else 14.dp,
                    bottom = if (isCoverScreen) 74.dp else 92.dp
                ),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MinimalTopBar(
                    status = status,
                    busyAction = busyAction,
                    showSettingsButton = true,
                    onOpenLogs = callbacks.onOpenLogs,
                    onOpenSettings = onOpenSettings,
                    onRefresh = callbacks.onRefresh
                )

                if (!status.lastError.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(if (isCoverScreen) 8.dp else 14.dp))
                    ErrorBanner(error = status.lastError)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (isCoverScreen) 10.dp else 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CenterHero(
                    status = status,
                    busyAction = busyAction,
                    compact = isCoverScreen,
                    callbacks = callbacks
                )

                AnimatedVisibility(visible = status.containerRunning) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(if (isCoverScreen) 10.dp else 22.dp))
                        TelemetryGraphCard(
                            status = status,
                            history = telemetryHistory,
                            compact = isCoverScreen,
                            onClick = onExpandTelemetry
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(1.dp))
        }

        if (isCoverScreen) {
            // Left-aligned floating pill dock that tucks cleanly to the left of the
            // Motorola Razr 60 Ultra's twin bottom-right camera rings while the right
            // side stays pure #141414 obsidian.
            CoverBottomDock(
                status = status,
                busyAction = busyAction,
                callbacks = callbacks,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = cameraSafeEndPadding, bottom = 10.dp)
            )
        } else {
            StandardBottomDock(
                status = status,
                busyAction = busyAction,
                onOpenDisplaySettings = onOpenSettings,
                callbacks = callbacks,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            )
        }
    }
}

@Composable
private fun WideMiracastLayout(
    status: SideSystemStatus,
    telemetryHistory: List<TelemetrySample>,
    peripherals: List<PeripheralDevice>,
    hasBluetoothPermission: Boolean,
    busyAction: String?,
    selectedResolution: DisplayResolution,
    selectedScale: DisplayScale,
    onResolutionChange: (DisplayResolution) -> Unit,
    onScaleChange: (DisplayScale) -> Unit,
    onExpandTelemetry: () -> Unit,
    callbacks: DashboardCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OrbBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 16.dp)
    ) {
        MinimalTopBar(
            status = status,
            busyAction = busyAction,
            showSettingsButton = false,
            onOpenLogs = callbacks.onOpenLogs,
            onOpenSettings = {},
            onRefresh = callbacks.onRefresh
        )

        if (!status.lastError.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            ErrorBanner(error = status.lastError)
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left pane: Calm Hero + Live Telemetry + Centered Dock
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Column(
                    modifier = Modifier.widthIn(max = 420.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CenterHero(
                        status = status,
                        busyAction = busyAction,
                        compact = false,
                        callbacks = callbacks
                    )
                    AnimatedVisibility(visible = status.containerRunning) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.height(20.dp))
                            TelemetryGraphCard(
                                status = status,
                                history = telemetryHistory,
                                compact = false,
                                onClick = onExpandTelemetry
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                StandardBottomDock(
                    status = status,
                    busyAction = busyAction,
                    onOpenDisplaySettings = callbacks.onRefresh,
                    callbacks = callbacks
                )
            }

            // Right pane: Inline Settings Panel
            Surface(
                color = OrbSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, OrbBorder),
                modifier = Modifier
                    .weight(1.05f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    SettingsPanelContent(
                        status = status,
                        peripherals = peripherals,
                        hasBluetoothPermission = hasBluetoothPermission,
                        busyAction = busyAction,
                        selectedResolution = selectedResolution,
                        selectedScale = selectedScale,
                        onResolutionChange = onResolutionChange,
                        onScaleChange = onScaleChange,
                        callbacks = callbacks
                    )
                }
            }
        }
    }
}

@Composable
private fun MinimalTopBar(
    status: SideSystemStatus,
    busyAction: String?,
    showSettingsButton: Boolean,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    val dotColor = when {
        status.containerRunning && status.desktopRunning -> OrbGreen
        status.containerRunning -> OrbAmber
        else -> OrbFgSubtle
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = "SideSystem",
                style = MaterialTheme.typography.titleMedium,
                color = OrbFgStrong,
                maxLines = 1
            )
            NetworkBadge(networkType = status.networkType)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (busyAction != null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(15.dp),
                    strokeWidth = 1.8.dp,
                    color = OrbFgMuted
                )
            }
            GhostIconButton(
                icon = Icons.Rounded.Terminal,
                contentDescription = "Logs",
                onClick = onOpenLogs
            )
            if (showSettingsButton) {
                GhostIconButton(
                    icon = Icons.Rounded.Tune,
                    contentDescription = "Settings",
                    onClick = onOpenSettings
                )
            } else {
                GhostIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    onClick = onRefresh
                )
            }
        }
    }
}

@Composable
private fun NetworkBadge(networkType: String) {
    val label = compactNetworkLabel(networkType)
    val isOffline = label.equals("Offline", ignoreCase = true)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(OrbSurface)
            .border(1.dp, OrbBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isOffline) OrbFgSubtle else OrbFgMuted,
            maxLines = 1
        )
    }
}

@Composable
private fun GhostIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(OrbSurfaceHover)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = OrbFgMuted,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun CenterHero(
    status: SideSystemStatus,
    busyAction: String?,
    compact: Boolean,
    callbacks: DashboardCallbacks
) {
    val headline = when {
        status.containerRunning && status.desktopRunning -> "NixOS · Hyprland"
        status.containerRunning -> "NixOS Ready"
        else -> "Container Stopped"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = headline,
            style = if (compact) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.headlineMedium
            },
            color = OrbFgStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = formatMetadataLine(status),
            style = MaterialTheme.typography.labelSmall,
            color = OrbFgMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(if (compact) 18.dp else 28.dp))

        // Crisp high-contrast primary CTA
        Button(
            onClick = callbacks.onOpenDesktop,
            enabled = busyAction == null,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = OrbFgStrong,
                contentColor = OrbBg,
                disabledContainerColor = OrbFgStrong.copy(alpha = 0.35f),
                disabledContentColor = OrbBg
            ),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            if (busyAction == "open_desktop") {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = OrbBg
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Opening Desktop...",
                    style = MaterialTheme.typography.labelLarge
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.DesktopWindows,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Open Desktop",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Minimal 3-pill hairline row directly beneath
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HairlineActionPill(
                label = "Restart",
                icon = Icons.Rounded.RestartAlt,
                iconTint = OrbFgMuted,
                enabled = busyAction == null,
                onClick = callbacks.onContainerRestart,
                modifier = Modifier.weight(1f)
            )
            if (status.containerRunning) {
                HairlineActionPill(
                    label = "Stop",
                    icon = Icons.Rounded.Stop,
                    iconTint = OrbRed,
                    enabled = busyAction == null,
                    onClick = callbacks.onContainerStop,
                    modifier = Modifier.weight(1f)
                )
            } else {
                HairlineActionPill(
                    label = "Start",
                    icon = Icons.Rounded.PlayArrow,
                    iconTint = OrbGreen,
                    enabled = busyAction == null,
                    onClick = callbacks.onContainerStart,
                    modifier = Modifier.weight(1f)
                )
            }
            HairlineActionPill(
                label = if (busyAction == "backup") "Saving..." else "Backup",
                icon = Icons.Rounded.CloudUpload,
                iconTint = OrbFgMuted,
                enabled = busyAction == null,
                onClick = callbacks.onBackupNow,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HairlineActionPill(
    label: String,
    icon: ImageVector,
    iconTint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = OrbSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, OrbBorder),
        modifier = modifier
            .height(40.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(15.dp)
            )
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

@Composable
private fun CoverBottomDock(
    status: SideSystemStatus,
    busyAction: String?,
    callbacks: DashboardCallbacks,
    modifier: Modifier = Modifier
) {
    Surface(
        color = OrbSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, OrbBorder),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            DockIconLabelPill(
                icon = Icons.Rounded.PowerSettingsNew,
                label = "Power",
                iconTint = if (status.containerRunning) OrbRed else OrbGreen,
                showStatusDot = false,
                enabled = busyAction == null,
                onClick = {
                    if (status.containerRunning) {
                        callbacks.onContainerStop()
                    } else {
                        callbacks.onContainerStart()
                    }
                }
            )
            DockDivider()
            DockIconLabelPill(
                icon = Icons.Rounded.ContentPaste,
                label = "Clip",
                iconTint = OrbFgMuted,
                showStatusDot = false,
                enabled = busyAction == null,
                onClick = callbacks.onSendClipboard
            )
            DockDivider()
            DockIconLabelPill(
                icon = Icons.Rounded.Terminal,
                label = "SSH",
                iconTint = OrbFgMuted,
                showStatusDot = status.sshdRunning,
                enabled = true,
                onClick = { callbacks.onCopySsh(status.sshCommand) }
            )
        }
    }
}

@Composable
private fun StandardBottomDock(
    status: SideSystemStatus,
    busyAction: String?,
    onOpenDisplaySettings: () -> Unit,
    callbacks: DashboardCallbacks,
    modifier: Modifier = Modifier
) {
    val profileShort = when (status.powerProfile) {
        PowerProfile.ECO -> "Eco"
        PowerProfile.BALANCED -> "Bal"
        PowerProfile.FULL -> "Full"
    }
    val nextProfile = when (status.powerProfile) {
        PowerProfile.ECO -> PowerProfile.BALANCED
        PowerProfile.BALANCED -> PowerProfile.FULL
        PowerProfile.FULL -> PowerProfile.ECO
    }

    Surface(
        color = OrbSurface,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, OrbBorder),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            DockTextPill(
                label = "${status.resolution.label} ${status.scale.label}",
                monospace = true,
                enabled = true,
                onClick = onOpenDisplaySettings
            )
            DockDivider()
            DockTextPill(
                label = profileShort,
                monospace = false,
                enabled = busyAction == null,
                onClick = { callbacks.onSelectProfile(nextProfile) }
            )
            DockDivider()
            DockIconLabelPill(
                icon = Icons.Rounded.ContentPaste,
                label = "Clip",
                iconTint = OrbFgMuted,
                showStatusDot = false,
                enabled = busyAction == null,
                onClick = callbacks.onSendClipboard
            )
            DockDivider()
            DockIconLabelPill(
                icon = Icons.Rounded.Terminal,
                label = "SSH",
                iconTint = OrbFgMuted,
                showStatusDot = status.sshdRunning,
                enabled = true,
                onClick = { callbacks.onCopySsh(status.sshCommand) }
            )
        }
    }
}

@Composable
private fun DockIconLabelPill(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    showStatusDot: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (showStatusDot) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(OrbGreen)
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = label,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            color = OrbFgStrong,
            maxLines = 1
        )
    }
}

@Composable
private fun DockTextPill(
    label: String,
    monospace: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            color = OrbFgStrong,
            maxLines = 1,
            style = LocalTextStyle.current.copy(
                fontFeatureSettings = if (monospace) "tnum" else null
            )
        )
    }
}

@Composable
private fun DockDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(16.dp)
            .background(OrbBorder)
    )
}

@Composable
private fun ErrorBanner(error: String?) {
    AnimatedVisibility(visible = !error.isNullOrBlank()) {
        Surface(
            color = OrbSurface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, OrbAmber.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = OrbAmber,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = OrbFgMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsModalSheet(
    status: SideSystemStatus,
    peripherals: List<PeripheralDevice>,
    hasBluetoothPermission: Boolean,
    busyAction: String?,
    selectedResolution: DisplayResolution,
    selectedScale: DisplayScale,
    onResolutionChange: (DisplayResolution) -> Unit,
    onScaleChange: (DisplayScale) -> Unit,
    onDismiss: () -> Unit,
    callbacks: DashboardCallbacks
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OrbSurface,
        contentColor = OrbFgStrong,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(34.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(OrbBorder)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleMedium,
                    color = OrbFgStrong
                )
                GhostIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = "Close Settings",
                    onClick = onDismiss
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            SettingsPanelContent(
                status = status,
                peripherals = peripherals,
                hasBluetoothPermission = hasBluetoothPermission,
                busyAction = busyAction,
                selectedResolution = selectedResolution,
                selectedScale = selectedScale,
                onResolutionChange = onResolutionChange,
                onScaleChange = onScaleChange,
                callbacks = callbacks
            )
        }
    }
}

@Composable
private fun SettingsPanelContent(
    status: SideSystemStatus,
    peripherals: List<PeripheralDevice>,
    hasBluetoothPermission: Boolean,
    busyAction: String?,
    selectedResolution: DisplayResolution,
    selectedScale: DisplayScale,
    onResolutionChange: (DisplayResolution) -> Unit,
    onScaleChange: (DisplayScale) -> Unit,
    callbacks: DashboardCallbacks
) {
    var customResText by remember(status.displayResolutionText) {
        mutableStateOf(status.displayResolutionText)
    }
    LaunchedEffect(selectedResolution) {
        if (DisplayResolution.fromString(customResText) != selectedResolution) {
            customResText = selectedResolution.cliValue
        }
    }
    val effectiveResText = customResText.trim().ifEmpty { selectedResolution.cliValue }
    val hasPendingDisplayChange =
        effectiveResText != status.displayResolutionText || selectedScale != status.scale

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // 1. Display
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("DISPLAY")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DisplayResolution.entries.forEach { preset ->
                    val isSelected = effectiveResText.equals(preset.cliValue, ignoreCase = true)
                    Surface(
                        color = if (isSelected) OrbFgStrong else OrbBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (isSelected) OrbFgStrong else OrbBorder),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = busyAction == null) {
                                onResolutionChange(preset)
                                customResText = preset.cliValue
                            }
                    ) {
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = if (isSelected) OrbBg else OrbFgMuted,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                        )
                    }
                }
            }
            OutlinedTextField(
                value = customResText,
                onValueChange = { customResText = it },
                singleLine = true,
                enabled = busyAction == null,
                placeholder = {
                    Text(
                        text = "Any WxH (e.g. 3440x1440, 2560x1600)",
                        style = MaterialTheme.typography.bodySmall,
                        color = OrbFgSubtle
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = OrbFgStrong
                ),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OrbFgMuted,
                    unfocusedBorderColor = OrbBorder,
                    focusedContainerColor = OrbBg,
                    unfocusedContainerColor = OrbBg,
                    cursorColor = OrbFgStrong
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OrbSegmentedRow(
                    items = DisplayScale.entries,
                    selected = selectedScale,
                    labelOf = { it.label },
                    enabled = busyAction == null,
                    onSelect = onScaleChange,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = if (hasPendingDisplayChange) OrbFgStrong else OrbSurfaceHover,
                    shape = RoundedCornerShape(10.dp),
                    border = if (hasPendingDisplayChange) null else BorderStroke(1.dp, OrbBorder),
                    modifier = Modifier
                        .height(38.dp)
                        .alpha(if (busyAction == null) 1f else 0.45f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = busyAction == null) {
                            callbacks.onApplyDisplay(effectiveResText, selectedScale)
                        }
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (busyAction == "display") "Applying..." else "Apply",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasPendingDisplayChange) OrbBg else OrbFgStrong
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = OrbBorder)

        // 2. Performance
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("PERFORMANCE")
            OrbSegmentedRow(
                items = PowerProfile.entries,
                selected = status.powerProfile,
                labelOf = { it.label },
                enabled = busyAction == null,
                onSelect = callbacks.onSelectProfile
            )
        }

        HorizontalDivider(color = OrbBorder)

        // 3. Toggles
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("SYSTEM")

            OrbToggleRow(
                title = "Auto-start on boot",
                subtitle = "Start container automatically via Magisk",
                checked = status.autostart,
                enabled = busyAction == null,
                onCheckedChange = callbacks.onToggleAutostart
            )

            OrbToggleRow(
                title = "SSH daemon (:${status.sshPort})",
                subtitle = status.sshCommand,
                subtitleMonospace = true,
                onSubtitleClick = { callbacks.onCopySsh(status.sshCommand) },
                checked = status.sshdRunning,
                enabled = busyAction == null,
                onCheckedChange = callbacks.onToggleSshd
            )

            OrbToggleRow(
                title = "GPU Turnip (experimental)",
                subtitle = if (status.gpuMode == GpuMode.TURNIP) {
                    "Adreno Vulkan hardware acceleration"
                } else {
                    "CPU llvmpipe software renderer"
                },
                checked = status.gpuMode == GpuMode.TURNIP,
                enabled = busyAction == null,
                onCheckedChange = { enabled ->
                    callbacks.onSelectGpuMode(if (enabled) GpuMode.TURNIP else GpuMode.LLVMPIPE)
                }
            )
        }

        HorizontalDivider(color = OrbBorder)

        // 4. Peripherals & Shortcuts
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("PERIPHERALS & SHORTCUTS")

            OrbToggleRow(
                title = "Route BT keyboard/mouse → NixOS",
                subtitle = if (status.btHidGrab) {
                    "Active — Bluetooth HID grabbed exclusively for NixOS"
                } else {
                    "Off — Bluetooth HID controls Android"
                },
                checked = status.btHidGrab,
                enabled = busyAction == null,
                onCheckedChange = callbacks.onToggleBtHidGrab
            )

            if (!hasBluetoothPermission) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(OrbBg)
                        .border(1.dp, OrbBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Allow Bluetooth to list paired devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = OrbFgMuted,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Grant",
                        style = MaterialTheme.typography.labelMedium,
                        color = OrbBlue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = callbacks.onRequestBluetoothPermission)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (peripherals.isEmpty()) {
                Text(
                    text = "No external keyboards or mice connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = OrbFgSubtle
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    peripherals.forEach { device ->
                        val icon = when (device.kind) {
                            PeripheralKind.KEYBOARD -> Icons.Rounded.Keyboard
                            PeripheralKind.MOUSE -> Icons.Rounded.Mouse
                            PeripheralKind.OTHER -> Icons.Rounded.DevicesOther
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(OrbBg)
                                .border(1.dp, OrbBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = OrbFgMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OrbFgStrong,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = device.source,
                                style = MaterialTheme.typography.labelSmall,
                                color = OrbFgSubtle
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HairlineActionPill(
                    label = "Bluetooth",
                    icon = Icons.Rounded.Bluetooth,
                    iconTint = OrbFgMuted,
                    enabled = true,
                    onClick = callbacks.onOpenBluetoothSettings,
                    modifier = Modifier.weight(1f)
                )
                HairlineActionPill(
                    label = "Smart Connect",
                    icon = Icons.Rounded.Cast,
                    iconTint = OrbFgMuted,
                    enabled = true,
                    onClick = callbacks.onOpenCastSettings,
                    modifier = Modifier.weight(1f)
                )
            }

            HairlineActionPill(
                label = if (busyAction == "clip_pull") "Pulling Clipboard..." else "Pull NixOS Clipboard",
                icon = Icons.Rounded.Download,
                iconTint = OrbFgMuted,
                enabled = busyAction == null,
                onClick = callbacks.onPullClipboard,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = OrbFgSubtle
    )
}

@Composable
private fun <T> OrbSegmentedRow(
    items: List<T>,
    selected: T,
    labelOf: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(OrbBg)
            .border(1.dp, OrbBorder, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isSelected) OrbSurfaceHover else Color.Transparent)
                    .then(
                        if (isSelected) {
                            Modifier.border(1.dp, OrbBorder, RoundedCornerShape(7.dp))
                        } else {
                            Modifier
                        }
                    )
                    .alpha(if (enabled) 1f else 0.5f)
                    .clickable(enabled = enabled) { onSelect(item) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = labelOf(item),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) OrbFgStrong else OrbFgMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun OrbToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitleMonospace: Boolean = false,
    onSubtitleClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
                .then(
                    if (onSubtitleClick != null) {
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onSubtitleClick)
                    } else {
                        Modifier
                    }
                )
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = OrbFgStrong
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = if (subtitleMonospace) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = if (subtitleMonospace) OrbBlue else OrbFgSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OrbBg,
                checkedTrackColor = OrbFgStrong,
                checkedBorderColor = OrbFgStrong,
                uncheckedThumbColor = OrbFgMuted,
                uncheckedTrackColor = OrbBg,
                uncheckedBorderColor = OrbBorder,
                disabledCheckedThumbColor = OrbBg,
                disabledCheckedTrackColor = OrbFgStrong.copy(alpha = 0.35f),
                disabledUncheckedThumbColor = OrbFgSubtle,
                disabledUncheckedTrackColor = OrbBg
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsModalSheet(
    logsContent: String,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onCopy: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OrbSurface,
        contentColor = OrbFgStrong,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(34.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(OrbBorder)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = OrbFgMuted,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = "Logs",
                        style = MaterialTheme.typography.titleMedium,
                        color = OrbFgStrong
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostIconButton(
                        icon = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy Logs",
                        onClick = onCopy
                    )
                    GhostIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "Refresh Logs",
                        onClick = onRefresh
                    )
                    GhostIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = "Close",
                        onClick = onDismiss
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(OrbBg)
                    .border(1.dp, OrbBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(22.dp),
                        strokeWidth = 2.dp,
                        color = OrbFgMuted
                    )
                } else {
                    val vScroll = rememberScrollState()
                    val hScroll = rememberScrollState()
                    Text(
                        text = logsContent.ifBlank { "(No log output)" },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        color = OrbFgStrong,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(vScroll)
                            .horizontalScroll(hScroll)
                    )
                }
            }
        }
    }
}

private fun compactNetworkLabel(raw: String): String {
    val trimmed = raw.trim()
    return when {
        trimmed.isEmpty() -> "Offline"
        trimmed.contains("wi-fi", ignoreCase = true) ||
            trimmed.contains("wifi", ignoreCase = true) -> "Wi-Fi"
        trimmed.contains("5g", ignoreCase = true) &&
            !trimmed.contains("4g", ignoreCase = true) -> "5G"
        trimmed.contains("4g", ignoreCase = true) ||
            trimmed.contains("cell", ignoreCase = true) -> "4G"
        trimmed.contains("eth", ignoreCase = true) -> "Eth"
        else -> trimmed
    }
}

private fun formatMetadataLine(status: SideSystemStatus): String {
    val memPart = if (status.memTotalMb > 0) {
        val usedGb = status.memUsedMb / 1024.0
        val totalGb = status.memTotalMb / 1024.0
        val usedStr = String.format(Locale.US, "%.1f", usedGb)
        val totalStr = if (totalGb >= 10.0) {
            String.format(Locale.US, "%.0f", totalGb)
        } else {
            String.format(Locale.US, "%.1f", totalGb)
        }
        "$usedStr / $totalStr GB"
    } else {
        "-- GB"
    }
    val displayPart = "${status.resolution.label} ${status.scale.label}"
    val uptimePart = status.uptime.ifBlank { "--" }
    return "$memPart  ·  $displayPart  ·  $uptimePart"
}

@Composable
private fun TelemetryGraphCard(
    status: SideSystemStatus,
    history: List<TelemetrySample>,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val samples = remember(history, status.cpuPct, status.gpuPct, status.memPct) {
        if (history.isNotEmpty()) history.takeLast(36)
        else listOf(TelemetrySample(status.cpuPct, status.gpuPct, status.memPct))
    }
    val ramReadout = if (compact || status.memTotalMb <= 0) {
        "${status.memPct}%"
    } else {
        val usedGb = String.format(Locale.US, "%.1f", status.memUsedMb / 1024.0)
        val totalGb = String.format(Locale.US, "%.0f", status.memTotalMb / 1024.0)
        "$usedGb/$totalGb GB · ${status.memPct}%"
    }
    val shape = RoundedCornerShape(if (compact) 12.dp else 14.dp)

    Surface(
        color = OrbSurface,
        shape = shape,
        border = BorderStroke(1.dp, OrbBorder),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 12.dp else 14.dp,
                    vertical = if (compact) 8.dp else 12.dp
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(OrbBlue)
                    )
                    Text(
                        text = "CPU",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = if (compact) 10.sp else 11.sp,
                            letterSpacing = 0.6.sp
                        ),
                        color = OrbFgMuted
                    )
                    Text(
                        text = "${status.cpuPct}%",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            fontSize = if (compact) 11.sp else 12.sp
                        ),
                        color = OrbFgStrong
                    )
                    Spacer(modifier = Modifier.width(if (compact) 4.dp else 8.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(OrbAmber)
                    )
                    Text(
                        text = "GPU",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = if (compact) 10.sp else 11.sp,
                            letterSpacing = 0.6.sp
                        ),
                        color = OrbFgMuted
                    )
                    Text(
                        text = "${status.gpuPct}%",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            fontSize = if (compact) 11.sp else 12.sp
                        ),
                        color = OrbFgStrong
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(OrbGreen)
                    )
                    Text(
                        text = "RAM",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = if (compact) 10.sp else 11.sp,
                            letterSpacing = 0.6.sp
                        ),
                        color = OrbFgMuted
                    )
                    Text(
                        text = ramReadout,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            fontSize = if (compact) 11.sp else 12.sp
                        ),
                        color = OrbFgStrong
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Rounded.OpenInFull,
                        contentDescription = "Expand resources graph",
                        tint = OrbFgSubtle,
                        modifier = Modifier.size(if (compact) 11.dp else 12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (compact) 6.dp else 10.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 42.dp else 78.dp)
            ) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 7f), 0f)
                val gridLines = if (compact) listOf(0.5f) else listOf(0.25f, 0.5f, 0.75f)
                for (frac in gridLines) {
                    val y = size.height * frac
                    drawLine(
                        color = OrbBorder.copy(alpha = 0.7f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash
                    )
                }

                val ramPoints = samples.map { it.memPct.coerceIn(0, 100) / 100f }
                val gpuPoints = samples.map { it.gpuPct.coerceIn(0, 100) / 100f }
                val cpuPoints = samples.map { it.cpuPct.coerceIn(0, 100) / 100f }

                drawSmoothAreaSeries(
                    values = ramPoints,
                    maxSlots = 36,
                    color = OrbGreen,
                    fillAlpha = 0.14f,
                    strokeWidthPx = (if (compact) 1.5f else 1.75f).dp.toPx()
                )
                drawSmoothAreaSeries(
                    values = gpuPoints,
                    maxSlots = 36,
                    color = OrbAmber,
                    fillAlpha = 0.18f,
                    strokeWidthPx = (if (compact) 1.5f else 1.75f).dp.toPx()
                )
                drawSmoothAreaSeries(
                    values = cpuPoints,
                    maxSlots = 36,
                    color = OrbBlue,
                    fillAlpha = 0.22f,
                    strokeWidthPx = (if (compact) 1.5f else 1.75f).dp.toPx()
                )
            }
        }
    }
}

private fun DrawScope.drawSmoothAreaSeries(
    values: List<Float>,
    maxSlots: Int,
    color: Color,
    fillAlpha: Float,
    strokeWidthPx: Float
) {
    if (values.isEmpty() || size.width <= 0f || size.height <= 0f) return

    val topPad = 4.dp.toPx()
    val bottomPad = 2.dp.toPx()
    val usableHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)
    val slotCount = (maxSlots - 1).coerceAtLeast(1)
    val stepX = size.width / slotCount.toFloat()

    // Right-align samples so live readings flow in smoothly from the right edge
    val padded = if (values.size == 1) listOf(values[0], values[0]) else values
    val startIndex = (maxSlots - padded.size).coerceAtLeast(0)

    val coords = padded.mapIndexed { idx, v ->
        val x = ((startIndex + idx).toFloat() * stepX).coerceIn(0f, size.width)
        val y = topPad + (1f - v.coerceIn(0f, 1f)) * usableHeight
        Offset(x, y)
    }

    val linePath = Path().apply {
        moveTo(coords.first().x, coords.first().y)
        for (i in 1 until coords.size) {
            val prev = coords[i - 1]
            val curr = coords[i]
            val midX = (prev.x + curr.x) * 0.5f
            cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
        }
    }

    val fillPath = Path().apply {
        addPath(linePath)
        lineTo(coords.last().x, size.height)
        lineTo(coords.first().x, size.height)
        close()
    }

    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = fillAlpha), Color.Transparent),
            startY = topPad,
            endY = size.height
        )
    )

    drawPath(
        path = linePath,
        color = color,
        style = Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )

    val tip = coords.last()
    drawCircle(
        color = color.copy(alpha = 0.28f),
        radius = 5.5.dp.toPx(),
        center = tip
    )
    drawCircle(
        color = color,
        radius = 2.75.dp.toPx(),
        center = tip
    )
}

private enum class TelemetrySeriesFilter(val label: String) {
    ALL("All"),
    CPU("CPU"),
    GPU("GPU"),
    RAM("RAM")
}

@Composable
private fun TelemetryExpandedOverlay(
    status: SideSystemStatus,
    history: List<TelemetrySample>,
    intervalMs: Long,
    paused: Boolean,
    isCoverScreen: Boolean,
    cameraSafeEndPadding: Dp,
    onIntervalChange: (Long) -> Unit,
    onTogglePaused: () -> Unit,
    onDismiss: () -> Unit
) {
    var seriesFilter by remember { mutableStateOf(TelemetrySeriesFilter.ALL) }
    var showControlsOverlay by remember { mutableStateOf(true) }

    val samples = remember(history, status.cpuPct, status.gpuPct, status.memPct) {
        if (history.isNotEmpty()) history.takeLast(60)
        else listOf(TelemetrySample(status.cpuPct, status.gpuPct, status.memPct))
    }

    val cpuAvg = remember(samples) {
        if (samples.isEmpty()) 0 else samples.map { it.cpuPct }.average().toInt()
    }
    val cpuPeak = remember(samples) {
        samples.maxOfOrNull { it.cpuPct } ?: 0
    }
    val gpuAvg = remember(samples) {
        if (samples.isEmpty()) 0 else samples.map { it.gpuPct }.average().toInt()
    }
    val gpuPeak = remember(samples) {
        samples.maxOfOrNull { it.gpuPct } ?: 0
    }
    val ramFreeGb = remember(status.memUsedMb, status.memTotalMb) {
        if (status.memTotalMb > 0) {
            val freeMb = (status.memTotalMb - status.memUsedMb).coerceAtLeast(0)
            String.format(Locale.US, "%.1f GB", freeMb / 1024.0)
        } else {
            "--"
        }
    }
    val ramUsedStr = remember(status.memUsedMb, status.memTotalMb) {
        if (status.memTotalMb > 0) {
            String.format(
                Locale.US,
                "%.1f / %.0f GB",
                status.memUsedMb / 1024.0,
                status.memTotalMb / 1024.0
            )
        } else {
            "-- GB"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrbBg)
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) {
                showControlsOverlay = !showControlsOverlay
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(
                    start = if (isCoverScreen) 14.dp else 20.dp,
                    end = if (isCoverScreen) 14.dp else 20.dp,
                    top = if (isCoverScreen) 10.dp else 16.dp,
                    bottom = if (isCoverScreen) 14.dp else 20.dp
                )
        ) {
            // Minimal Orb Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (paused) OrbAmber else OrbGreen)
                    )
                    Text(
                        text = "Resources",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = if (isCoverScreen) 15.sp else 17.sp
                        ),
                        color = OrbFgStrong
                    )
                    if (paused) {
                        Text(
                            text = "PAUSED",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            ),
                            color = OrbAmber
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(OrbSurfaceHover)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close resources view",
                        tint = OrbFgMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (isCoverScreen) 8.dp else 16.dp))

            // Hero Numeric Readouts
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (isCoverScreen) 10.dp else 14.dp)
            ) {
                if (seriesFilter == TelemetrySeriesFilter.ALL || seriesFilter == TelemetrySeriesFilter.CPU) {
                    Surface(
                        color = OrbSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, OrbBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = if (isCoverScreen) 10.dp else 14.dp,
                                vertical = if (isCoverScreen) 8.dp else 10.dp
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(OrbBlue)
                                )
                                Text(
                                    text = "CPU",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OrbFgMuted
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "${status.cpuPct}%",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = if (isCoverScreen) 20.sp else 26.sp
                                ),
                                color = OrbFgStrong
                            )
                            Text(
                                text = "avg $cpuAvg% · peak $cpuPeak%",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                color = OrbFgSubtle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (seriesFilter == TelemetrySeriesFilter.ALL || seriesFilter == TelemetrySeriesFilter.GPU) {
                    Surface(
                        color = OrbSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, OrbBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = if (isCoverScreen) 10.dp else 14.dp,
                                vertical = if (isCoverScreen) 8.dp else 10.dp
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(OrbAmber)
                                )
                                Text(
                                    text = "GPU",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OrbFgMuted
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "${status.gpuPct}%",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = if (isCoverScreen) 20.sp else 26.sp
                                ),
                                color = OrbFgStrong
                            )
                            Text(
                                text = "avg $gpuAvg% · peak $gpuPeak%",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                color = OrbFgSubtle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (seriesFilter == TelemetrySeriesFilter.ALL || seriesFilter == TelemetrySeriesFilter.RAM) {
                    Surface(
                        color = OrbSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, OrbBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = if (isCoverScreen) 10.dp else 14.dp,
                                vertical = if (isCoverScreen) 8.dp else 10.dp
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(OrbGreen)
                                )
                                Text(
                                    text = "MEMORY",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OrbFgMuted
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "${status.memPct}%",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = if (isCoverScreen) 20.sp else 26.sp
                                ),
                                color = OrbFgStrong
                            )
                            Text(
                                text = "$ramUsedStr · $ramFreeGb free",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                color = OrbFgSubtle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isCoverScreen) 10.dp else 16.dp))

            // Main Interactive Expanded Canvas Graph
            Surface(
                color = OrbSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, OrbBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = 10.dp,
                            end = 12.dp,
                            top = 12.dp,
                            bottom = 12.dp
                        )
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Subtle Y-Axis Scale Labels
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(end = 8.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            val labels = if (isCoverScreen) listOf("100", "50", "0") else listOf("100%", "75%", "50%", "25%", "0%")
                            labels.forEach { lbl ->
                                Text(
                                    text = lbl,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp
                                    ),
                                    color = OrbFgSubtle
                                )
                            }
                        }

                        Canvas(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
                            val gridFracs = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
                            for (frac in gridFracs) {
                                val y = 4.dp.toPx() + (size.height - 6.dp.toPx()) * frac
                                drawLine(
                                    color = OrbBorder.copy(alpha = if (frac == 0.5f) 0.9f else 0.55f),
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 1.dp.toPx(),
                                    pathEffect = if (frac == 0f || frac == 1f) null else dash
                                )
                            }

                            if (seriesFilter == TelemetrySeriesFilter.ALL || seriesFilter == TelemetrySeriesFilter.RAM) {
                                drawSmoothAreaSeries(
                                    values = samples.map { it.memPct.coerceIn(0, 100) / 100f },
                                    maxSlots = 60,
                                    color = OrbGreen,
                                    fillAlpha = 0.16f,
                                    strokeWidthPx = 2.dp.toPx()
                                )
                            }
                            if (seriesFilter == TelemetrySeriesFilter.ALL || seriesFilter == TelemetrySeriesFilter.GPU) {
                                drawSmoothAreaSeries(
                                    values = samples.map { it.gpuPct.coerceIn(0, 100) / 100f },
                                    maxSlots = 60,
                                    color = OrbAmber,
                                    fillAlpha = 0.22f,
                                    strokeWidthPx = 2.dp.toPx()
                                )
                            }
                            if (seriesFilter == TelemetrySeriesFilter.ALL || seriesFilter == TelemetrySeriesFilter.CPU) {
                                drawSmoothAreaSeries(
                                    values = samples.map { it.cpuPct.coerceIn(0, 100) / 100f },
                                    maxSlots = 60,
                                    color = OrbBlue,
                                    fillAlpha = 0.26f,
                                    strokeWidthPx = 2.dp.toPx()
                                )
                            }
                        }
                    }
                }
            }

            // Bottom clearance for camera cutout on Razr cover screen, or clean metadata footer on main screen
            if (isCoverScreen) {
                Spacer(modifier = Modifier.height(68.dp))
            } else {
                Spacer(modifier = Modifier.height(70.dp))
            }
        }

        // Subtle Translucent Floating Options Overlay Pill (tap graph to show/hide)
        AnimatedVisibility(
            visible = showControlsOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(if (isCoverScreen) Alignment.BottomStart else Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    start = if (isCoverScreen) 12.dp else 16.dp,
                    end = if (isCoverScreen) cameraSafeEndPadding else 16.dp,
                    bottom = if (isCoverScreen) 14.dp else 20.dp
                )
        ) {
            Surface(
                color = OrbSurface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, OrbBorder)
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TelemetrySeriesFilter.entries.forEach { option ->
                        val selected = seriesFilter == option
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) OrbFgStrong else Color.Transparent)
                                .clickable { seriesFilter = option }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    fontSize = 11.sp
                                ),
                                color = if (selected) OrbBg else OrbFgMuted
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(16.dp)
                            .background(OrbBorder)
                    )

                    val intervals = listOf(1_000L to "1s", 2_500L to "2.5s", 5_000L to "5s")
                    intervals.forEach { (ms, label) ->
                        val selected = intervalMs == ms
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) OrbSurfaceHover else Color.Transparent)
                                .clickable { onIntervalChange(ms) }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                color = if (selected) OrbFgStrong else OrbFgSubtle
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(16.dp)
                            .background(OrbBorder)
                    )

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (paused) OrbAmber.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable(onClick = onTogglePaused),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = if (paused) "Resume sampling" else "Pause sampling",
                            tint = if (paused) OrbAmber else OrbFgMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
