package com.th0rgal.sidesystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val onApplyDisplay: (DisplayResolution, DisplayScale) -> Unit,
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

    LaunchedEffect(status.resolution, status.scale) {
        selectedResolution = status.resolution
        selectedScale = status.scale
    }

    Scaffold(
        containerColor = StarshipColors.Background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(StarshipColors.Background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(innerPadding)
        ) {
            val isCoverScreen = maxWidth < 400.dp
            val isWideScreen = maxWidth >= 640.dp

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = callbacks.onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    isCoverScreen -> CoverScreenLayout(
                        status = status,
                        busyAction = busyAction,
                        callbacks = callbacks
                    )

                    isWideScreen -> WideMonitorLayout(
                        status = status,
                        peripherals = peripherals,
                        hasBluetoothPermission = hasBluetoothPermission,
                        busyAction = busyAction,
                        selectedResolution = selectedResolution,
                        selectedScale = selectedScale,
                        onResolutionChange = { selectedResolution = it },
                        onScaleChange = { selectedScale = it },
                        callbacks = callbacks
                    )

                    else -> MainPhoneLayout(
                        status = status,
                        peripherals = peripherals,
                        hasBluetoothPermission = hasBluetoothPermission,
                        busyAction = busyAction,
                        selectedResolution = selectedResolution,
                        selectedScale = selectedScale,
                        onResolutionChange = { selectedResolution = it },
                        onScaleChange = { selectedScale = it },
                        callbacks = callbacks
                    )
                }
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
private fun CoverScreenLayout(
    status: SideSystemStatus,
    busyAction: String?,
    callbacks: DashboardCallbacks
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Compact header with dual status pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "SideSystem",
                style = MaterialTheme.typography.titleMedium,
                color = StarshipColors.TextPrimary
            )
            IconButton(
                onClick = callbacks.onRefresh,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    tint = StarshipColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Compact Status Pill row (NixOS + Hyprland)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatusPill(
                label = if (status.containerRunning) "NixOS • Up" else "NixOS • Off",
                color = if (status.containerRunning) StarshipColors.Running else StarshipColors.Stopped,
                bgColor = if (status.containerRunning) StarshipColors.RunningDim else StarshipColors.StoppedDim,
                modifier = Modifier.weight(1f)
            )
            StatusPill(
                label = if (status.desktopRunning) "Hyprland • Live" else "Hyprland • Off",
                color = if (status.desktopRunning) StarshipColors.Accent else StarshipColors.TextSecondary,
                bgColor = if (status.desktopRunning) StarshipColors.AccentDim else StarshipColors.SurfaceElevated,
                modifier = Modifier.weight(1f)
            )
        }

        ErrorBanner(status.lastError)

        // Prominent Open Desktop button
        PrimaryDesktopButton(
            isBusy = busyAction == "open_desktop",
            enabled = busyAction == null,
            label = "Open Desktop",
            onClick = callbacks.onOpenDesktop,
            heightDp = 52
        )

        // Quick Start / Restart / Stop controls
        StarshipCard(contentPadding = PaddingValues(10.dp)) {
            Text(
                text = "QUICK CONTROLS",
                style = MaterialTheme.typography.labelMedium,
                color = StarshipColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompactActionButton(
                    icon = Icons.Rounded.PlayArrow,
                    label = "Start",
                    tint = StarshipColors.Running,
                    enabled = busyAction == null,
                    onClick = callbacks.onContainerStart,
                    modifier = Modifier.weight(1f)
                )
                CompactActionButton(
                    icon = Icons.Rounded.RestartAlt,
                    label = "Restart",
                    tint = StarshipColors.Accent,
                    enabled = busyAction == null,
                    onClick = callbacks.onContainerRestart,
                    modifier = Modifier.weight(1f)
                )
                CompactActionButton(
                    icon = Icons.Rounded.Stop,
                    label = "Stop",
                    tint = StarshipColors.Stopped,
                    enabled = busyAction == null,
                    onClick = callbacks.onContainerStop,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MainPhoneLayout(
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
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TopAppBarRow(
            busyAction = busyAction,
            onRefresh = callbacks.onRefresh,
            onOpenLogs = callbacks.onOpenLogs
        )
        ErrorBanner(status.lastError)
        HeroCard(
            status = status,
            busyAction = busyAction,
            onOpenDesktop = callbacks.onOpenDesktop
        )
        LifecycleCard(
            status = status,
            busyAction = busyAction,
            callbacks = callbacks
        )
        DisplayPresetCard(
            selectedResolution = selectedResolution,
            selectedScale = selectedScale,
            onResolutionChange = onResolutionChange,
            onScaleChange = onScaleChange,
            busyAction = busyAction,
            onApply = { callbacks.onApplyDisplay(selectedResolution, selectedScale) }
        )
        ResourcesCard(
            status = status,
            busyAction = busyAction,
            onSelectProfile = callbacks.onSelectProfile,
            onSelectGpuMode = callbacks.onSelectGpuMode
        )
        NetworkCard(
            status = status,
            busyAction = busyAction,
            onCopySsh = callbacks.onCopySsh,
            onToggleSshd = callbacks.onToggleSshd
        )
        PeripheralsCard(
            peripherals = peripherals,
            hasBluetoothPermission = hasBluetoothPermission,
            onRequestBluetoothPermission = callbacks.onRequestBluetoothPermission,
            onOpenBluetoothSettings = callbacks.onOpenBluetoothSettings,
            onOpenCastSettings = callbacks.onOpenCastSettings
        )
        ClipboardAndBackupCard(
            busyAction = busyAction,
            onSendClipboard = callbacks.onSendClipboard,
            onPullClipboard = callbacks.onPullClipboard,
            onBackupNow = callbacks.onBackupNow,
            onOpenLogs = callbacks.onOpenLogs
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun WideMonitorLayout(
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
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TopAppBarRow(
            busyAction = busyAction,
            onRefresh = callbacks.onRefresh,
            onOpenLogs = callbacks.onOpenLogs
        )
        ErrorBanner(status.lastError)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Left Column: Hero + Open Desktop + Lifecycle + Display + Resources
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HeroCard(
                    status = status,
                    busyAction = busyAction,
                    onOpenDesktop = callbacks.onOpenDesktop
                )
                LifecycleCard(
                    status = status,
                    busyAction = busyAction,
                    callbacks = callbacks
                )
                DisplayPresetCard(
                    selectedResolution = selectedResolution,
                    selectedScale = selectedScale,
                    onResolutionChange = onResolutionChange,
                    onScaleChange = onScaleChange,
                    busyAction = busyAction,
                    onApply = { callbacks.onApplyDisplay(selectedResolution, selectedScale) }
                )
            }

            // Right Column: Telemetry/Resources + Network/4G/SSH + Bluetooth Peripherals + Clipboard/Backup/Logs
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ResourcesCard(
                    status = status,
                    busyAction = busyAction,
                    onSelectProfile = callbacks.onSelectProfile,
                    onSelectGpuMode = callbacks.onSelectGpuMode
                )
                NetworkCard(
                    status = status,
                    busyAction = busyAction,
                    onCopySsh = callbacks.onCopySsh,
                    onToggleSshd = callbacks.onToggleSshd
                )
                PeripheralsCard(
                    peripherals = peripherals,
                    hasBluetoothPermission = hasBluetoothPermission,
                    onRequestBluetoothPermission = callbacks.onRequestBluetoothPermission,
                    onOpenBluetoothSettings = callbacks.onOpenBluetoothSettings,
                    onOpenCastSettings = callbacks.onOpenCastSettings
                )
                ClipboardAndBackupCard(
                    busyAction = busyAction,
                    onSendClipboard = callbacks.onSendClipboard,
                    onPullClipboard = callbacks.onPullClipboard,
                    onBackupNow = callbacks.onBackupNow,
                    onOpenLogs = callbacks.onOpenLogs
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun TopAppBarRow(
    busyAction: String?,
    onRefresh: () -> Unit,
    onOpenLogs: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(StarshipColors.AccentDim)
                    .border(1.dp, StarshipColors.Accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.DesktopWindows,
                    contentDescription = null,
                    tint = StarshipColors.Accent,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "SideSystem",
                    style = MaterialTheme.typography.headlineSmall,
                    color = StarshipColors.TextPrimary
                )
                Text(
                    text = "NixOS • Hyprland Control Center",
                    style = MaterialTheme.typography.bodySmall,
                    color = StarshipColors.TextSecondary
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (busyAction != null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                    color = StarshipColors.Accent
                )
            }
            IconButton(onClick = onOpenLogs) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = "View Logs",
                    tint = StarshipColors.TextSecondary
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh Status",
                    tint = StarshipColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: String?) {
    AnimatedVisibility(visible = !error.isNullOrBlank()) {
        Surface(
            color = StarshipColors.DegradedDim,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, StarshipColors.Degraded.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = StarshipColors.Degraded,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = StarshipColors.Degraded,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    status: SideSystemStatus,
    busyAction: String?,
    onOpenDesktop: () -> Unit
) {
    StarshipCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "SYSTEM STATUS",
                style = MaterialTheme.typography.labelMedium,
                color = StarshipColors.TextSecondary
            )
            Text(
                text = "Uptime ${status.uptime}",
                style = MaterialTheme.typography.labelSmall,
                color = StarshipColors.TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusPill(
                label = if (status.containerRunning) "Container: Running" else "Container: Stopped",
                color = if (status.containerRunning) StarshipColors.Running else StarshipColors.Stopped,
                bgColor = if (status.containerRunning) StarshipColors.RunningDim else StarshipColors.StoppedDim
            )
            StatusPill(
                label = if (status.desktopRunning) "Hyprland: Live" else "Hyprland: Off",
                color = if (status.desktopRunning) StarshipColors.Accent else StarshipColors.TextSecondary,
                bgColor = if (status.desktopRunning) StarshipColors.AccentDim else StarshipColors.SurfaceElevated
            )
            val systemdColor = when (status.systemdState.lowercase()) {
                "running" -> StarshipColors.Running
                "degraded", "starting" -> StarshipColors.Degraded
                else -> StarshipColors.TextSecondary
            }
            val systemdBg = when (status.systemdState.lowercase()) {
                "running" -> StarshipColors.RunningDim
                "degraded", "starting" -> StarshipColors.DegradedDim
                else -> StarshipColors.SurfaceElevated
            }
            StatusPill(
                label = "systemd: ${status.systemdState}",
                color = systemdColor,
                bgColor = systemdBg
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        PrimaryDesktopButton(
            isBusy = busyAction == "open_desktop",
            enabled = busyAction == null,
            label = "Open NixOS Desktop",
            onClick = onOpenDesktop,
            heightDp = 50
        )
    }
}

@Composable
private fun PrimaryDesktopButton(
    isBusy: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    heightDp: Int
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StarshipColors.Accent,
            contentColor = Color(0xFF061523),
            disabledContainerColor = StarshipColors.Accent.copy(alpha = 0.35f),
            disabledContentColor = Color(0xFF061523)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF061523)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Launching Desktop...", style = MaterialTheme.typography.labelLarge)
        } else {
            Icon(
                imageVector = Icons.Rounded.DesktopWindows,
                contentDescription = null,
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LifecycleCard(
    status: SideSystemStatus,
    busyAction: String?,
    callbacks: DashboardCallbacks
) {
    StarshipCard {
        CardHeader(icon = Icons.Rounded.PowerSettingsNew, title = "Lifecycle Controls")
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "NixOS Container",
            style = MaterialTheme.typography.titleSmall,
            color = StarshipColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompactActionButton(
                icon = Icons.Rounded.PlayArrow,
                label = "Start",
                tint = StarshipColors.Running,
                enabled = busyAction == null,
                onClick = callbacks.onContainerStart,
                modifier = Modifier.weight(1f)
            )
            CompactActionButton(
                icon = Icons.Rounded.RestartAlt,
                label = "Restart",
                tint = StarshipColors.Accent,
                enabled = busyAction == null,
                onClick = callbacks.onContainerRestart,
                modifier = Modifier.weight(1f)
            )
            CompactActionButton(
                icon = Icons.Rounded.Stop,
                label = "Stop",
                tint = StarshipColors.Stopped,
                enabled = busyAction == null,
                onClick = callbacks.onContainerStop,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Hyprland Desktop",
            style = MaterialTheme.typography.titleSmall,
            color = StarshipColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompactActionButton(
                icon = Icons.Rounded.PlayArrow,
                label = "Start",
                tint = StarshipColors.Running,
                enabled = busyAction == null,
                onClick = callbacks.onDesktopStart,
                modifier = Modifier.weight(1f)
            )
            CompactActionButton(
                icon = Icons.Rounded.RestartAlt,
                label = "Restart",
                tint = StarshipColors.Accent,
                enabled = busyAction == null,
                onClick = callbacks.onDesktopRestart,
                modifier = Modifier.weight(1f)
            )
            CompactActionButton(
                icon = Icons.Rounded.Stop,
                label = "Stop",
                tint = StarshipColors.Stopped,
                enabled = busyAction == null,
                onClick = callbacks.onDesktopStop,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = StarshipColors.BorderSubtle)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto-start on boot",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StarshipColors.TextPrimary
                )
                Text(
                    text = "Boot NixOS container automatically via Magisk",
                    style = MaterialTheme.typography.bodySmall,
                    color = StarshipColors.TextSecondary
                )
            }
            Switch(
                checked = status.autostart,
                enabled = busyAction == null,
                onCheckedChange = callbacks.onToggleAutostart,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF061523),
                    checkedTrackColor = StarshipColors.Accent,
                    uncheckedThumbColor = StarshipColors.TextSecondary,
                    uncheckedTrackColor = StarshipColors.SurfaceElevated,
                    uncheckedBorderColor = StarshipColors.Border
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayPresetCard(
    selectedResolution: DisplayResolution,
    selectedScale: DisplayScale,
    onResolutionChange: (DisplayResolution) -> Unit,
    onScaleChange: (DisplayScale) -> Unit,
    busyAction: String?,
    onApply: () -> Unit
) {
    StarshipCard {
        CardHeader(icon = Icons.Rounded.DisplaySettings, title = "Display Preset")
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Resolution",
            style = MaterialTheme.typography.titleSmall,
            color = StarshipColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            DisplayResolution.entries.forEachIndexed { index, res ->
                SegmentedButton(
                    selected = selectedResolution == res,
                    onClick = { onResolutionChange(res) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = DisplayResolution.entries.size
                    ),
                    colors = starshipSegmentedColors()
                ) {
                    Text(res.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Display Scale",
            style = MaterialTheme.typography.titleSmall,
            color = StarshipColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            DisplayScale.entries.forEachIndexed { index, scale ->
                SegmentedButton(
                    selected = selectedScale == scale,
                    onClick = { onScaleChange(scale) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = DisplayScale.entries.size
                    ),
                    colors = starshipSegmentedColors()
                ) {
                    Text(
                        text = "${scale.label} • ${scale.hint}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onApply,
            enabled = busyAction == null,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, StarshipColors.Accent.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = StarshipColors.AccentDim.copy(alpha = 0.55f),
                contentColor = StarshipColors.Accent
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Rounded.RestartAlt,
                contentDescription = null,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Apply & Restart Desktop",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResourcesCard(
    status: SideSystemStatus,
    busyAction: String?,
    onSelectProfile: (PowerProfile) -> Unit,
    onSelectGpuMode: (GpuMode) -> Unit
) {
    StarshipCard {
        CardHeader(icon = Icons.Rounded.Memory, title = "Resources & Performance")
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Memory Usage",
                style = MaterialTheme.typography.titleSmall,
                color = StarshipColors.TextSecondary
            )
            Text(
                text = if (status.memTotalMb > 0) {
                    "${status.memUsedMb} / ${status.memTotalMb} MB"
                } else {
                    "-- / -- MB"
                },
                style = MaterialTheme.typography.labelSmall,
                color = StarshipColors.TextPrimary
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        val barColor = when {
            status.memFraction >= 0.85f -> StarshipColors.Stopped
            status.memFraction >= 0.70f -> StarshipColors.Degraded
            else -> StarshipColors.Accent
        }
        LinearProgressIndicator(
            progress = { status.memFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = barColor,
            trackColor = StarshipColors.SurfaceElevated
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Power Profile",
            style = MaterialTheme.typography.titleSmall,
            color = StarshipColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            PowerProfile.entries.forEachIndexed { index, profile ->
                SegmentedButton(
                    selected = status.powerProfile == profile,
                    enabled = busyAction == null,
                    onClick = { onSelectProfile(profile) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = PowerProfile.entries.size
                    ),
                    colors = starshipSegmentedColors()
                ) {
                    Text(profile.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Graphics Renderer",
            style = MaterialTheme.typography.titleSmall,
            color = StarshipColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            GpuMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = status.gpuMode == mode,
                    enabled = busyAction == null,
                    onClick = { onSelectGpuMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = GpuMode.entries.size
                    ),
                    colors = starshipSegmentedColors()
                ) {
                    Text(mode.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun NetworkCard(
    status: SideSystemStatus,
    busyAction: String?,
    onCopySsh: (String) -> Unit,
    onToggleSshd: (Boolean) -> Unit
) {
    StarshipCard {
        val isWifi = status.networkType.contains("wi-fi", ignoreCase = true) ||
            status.networkType.contains("wifi", ignoreCase = true)
        val isCell = status.networkType.contains("4g", ignoreCase = true) ||
            status.networkType.contains("5g", ignoreCase = true) ||
            status.networkType.contains("cell", ignoreCase = true)
        val netIcon = when {
            isWifi -> Icons.Rounded.Wifi
            isCell -> Icons.Rounded.SignalCellularAlt
            else -> Icons.Rounded.WifiOff
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CardHeader(icon = netIcon, title = "Network & Remote Access")
            StatusPill(
                label = "${status.networkType} • ${status.ipAddress}",
                color = if (isWifi || isCell) StarshipColors.Running else StarshipColors.TextSecondary,
                bgColor = if (isWifi || isCell) StarshipColors.RunningDim else StarshipColors.SurfaceElevated
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Copyable SSH command box
        Surface(
            color = StarshipColors.Background,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, StarshipColors.Border),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCopySsh(status.sshCommand) }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = status.sshCommand,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color = StarshipColors.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy SSH command",
                    tint = StarshipColors.TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SSH Daemon (sshd)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StarshipColors.TextPrimary
                )
                Text(
                    text = "Port ${status.sshPort} • ${if (status.sshdRunning) "Listening" else "Stopped"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StarshipColors.TextSecondary
                )
            }
            Switch(
                checked = status.sshdRunning,
                enabled = busyAction == null,
                onCheckedChange = onToggleSshd,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF061523),
                    checkedTrackColor = StarshipColors.Running,
                    uncheckedThumbColor = StarshipColors.TextSecondary,
                    uncheckedTrackColor = StarshipColors.SurfaceElevated,
                    uncheckedBorderColor = StarshipColors.Border
                )
            )
        }
    }
}

@Composable
private fun PeripheralsCard(
    peripherals: List<PeripheralDevice>,
    hasBluetoothPermission: Boolean,
    onRequestBluetoothPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenCastSettings: () -> Unit
) {
    StarshipCard {
        CardHeader(icon = Icons.Rounded.Bluetooth, title = "Bluetooth Peripherals & Input")
        Spacer(modifier = Modifier.height(10.dp))

        if (!hasBluetoothPermission) {
            Surface(
                color = StarshipColors.SurfaceElevated,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, StarshipColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Grant Bluetooth access to list paired BT keyboards & mice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StarshipColors.TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onRequestBluetoothPermission) {
                        Text("Grant", color = StarshipColors.Accent)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (peripherals.isEmpty()) {
            Text(
                text = "No external keyboards or mice detected.",
                style = MaterialTheme.typography.bodySmall,
                color = StarshipColors.TextMuted
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
                            .clip(RoundedCornerShape(8.dp))
                            .background(StarshipColors.SurfaceElevated)
                            .border(1.dp, StarshipColors.BorderSubtle, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
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
                                tint = StarshipColors.Accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = device.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = StarshipColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = device.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = StarshipColors.Running
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenBluetoothSettings,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, StarshipColors.Border),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = StarshipColors.SurfaceElevated,
                    contentColor = StarshipColors.TextPrimary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bluetooth,
                    contentDescription = null,
                    tint = StarshipColors.Accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Bluetooth",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }

            OutlinedButton(
                onClick = onOpenCastSettings,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, StarshipColors.Border),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = StarshipColors.SurfaceElevated,
                    contentColor = StarshipColors.TextPrimary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Cast,
                    contentDescription = null,
                    tint = StarshipColors.Accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Smart Connect",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ClipboardAndBackupCard(
    busyAction: String?,
    onSendClipboard: () -> Unit,
    onPullClipboard: () -> Unit,
    onBackupNow: () -> Unit,
    onOpenLogs: () -> Unit
) {
    StarshipCard {
        CardHeader(icon = Icons.Rounded.CloudUpload, title = "Clipboard, Backup & Logs")
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onSendClipboard,
                enabled = busyAction == null,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, StarshipColors.Border),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = StarshipColors.SurfaceElevated,
                    contentColor = StarshipColors.TextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Upload,
                    contentDescription = null,
                    tint = StarshipColors.Accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Send → NixOS",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onPullClipboard,
                enabled = busyAction == null,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, StarshipColors.Border),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = StarshipColors.SurfaceElevated,
                    contentColor = StarshipColors.TextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    tint = StarshipColors.Running,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Pull ← NixOS",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBackupNow,
                enabled = busyAction == null,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, StarshipColors.Accent.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = StarshipColors.AccentDim.copy(alpha = 0.4f),
                    contentColor = StarshipColors.Accent
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (busyAction == "backup") "Backing up..." else "Backup Now",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }

            OutlinedButton(
                onClick = onOpenLogs,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, StarshipColors.Border),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = StarshipColors.SurfaceElevated,
                    contentColor = StarshipColors.TextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = StarshipColors.TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "View Logs",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
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
        containerColor = StarshipColors.SurfaceCard,
        contentColor = StarshipColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = StarshipColors.Accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SideSystem Logs",
                        style = MaterialTheme.typography.titleMedium,
                        color = StarshipColors.TextPrimary
                    )
                }
                Row {
                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "Copy Logs",
                            tint = StarshipColors.TextSecondary
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh Logs",
                            tint = StarshipColors.TextSecondary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = StarshipColors.TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(StarshipColors.Background)
                    .border(1.dp, StarshipColors.Border, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = StarshipColors.Accent
                    )
                } else {
                    val vScroll = rememberScrollState()
                    val hScroll = rememberScrollState()
                    Text(
                        text = logsContent.ifBlank { "(No log output)" },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        color = StarshipColors.TextPrimary,
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

@Composable
private fun StarshipCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = StarshipColors.SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, StarshipColors.Border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content
        )
    }
}

@Composable
private fun CardHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = StarshipColors.Accent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = StarshipColors.TextPrimary
        )
    }
}

@Composable
private fun StatusPill(
    label: String,
    color: Color,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bgColor)
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, StarshipColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = StarshipColors.SurfaceElevated,
            contentColor = StarshipColors.TextPrimary
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun starshipSegmentedColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = StarshipColors.AccentDim,
    activeContentColor = StarshipColors.Accent,
    activeBorderColor = StarshipColors.Accent.copy(alpha = 0.65f),
    inactiveContainerColor = StarshipColors.SurfaceElevated,
    inactiveContentColor = StarshipColors.TextSecondary,
    inactiveBorderColor = StarshipColors.Border
)
