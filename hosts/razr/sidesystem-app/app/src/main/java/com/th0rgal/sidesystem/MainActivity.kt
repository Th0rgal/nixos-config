package com.th0rgal.sidesystem

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        setContent {
            SideSystemTheme {
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current
                val scope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }

                var status by remember { mutableStateOf(SideSystemStatus()) }
                var peripherals by remember { mutableStateOf<List<PeripheralDevice>>(emptyList()) }
                var hasBtPerm by remember {
                    mutableStateOf(SideSystemCtl.hasBluetoothPermission(context))
                }
                var isRefreshing by remember { mutableStateOf(false) }
                var busyAction by remember { mutableStateOf<String?>(null) }

                var showLogsSheet by remember { mutableStateOf(false) }
                var logsContent by remember { mutableStateOf("") }
                var isLoadingLogs by remember { mutableStateOf(false) }

                fun showNotice(message: String) {
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(message)
                    }
                }

                suspend fun refreshAll(showSpinner: Boolean) {
                    if (showSpinner) isRefreshing = true
                    try {
                        hasBtPerm = SideSystemCtl.hasBluetoothPermission(context)
                        peripherals = SideSystemCtl.queryPeripherals(context)
                        status = SideSystemCtl.fetchStatus(context)
                    } finally {
                        if (showSpinner) isRefreshing = false
                    }
                }

                fun runAction(
                    actionKey: String,
                    successMessage: String,
                    block: suspend () -> CommandResult
                ) {
                    if (busyAction != null) return
                    scope.launch {
                        busyAction = actionKey
                        try {
                            val res = block()
                            if (res.isSuccess) {
                                showNotice(successMessage)
                            } else {
                                showNotice("Failed: ${res.combinedError}")
                            }
                            refreshAll(showSpinner = false)
                        } finally {
                            busyAction = null
                        }
                    }
                }

                fun loadLogs() {
                    scope.launch {
                        isLoadingLogs = true
                        try {
                            val res = SideSystemCtl.fetchLogs()
                            logsContent = if (res.isSuccess) {
                                res.stdout.ifBlank { "(No log output)" }
                            } else {
                                "Failed to fetch logs:\n${res.combinedError}"
                            }
                        } finally {
                            isLoadingLogs = false
                        }
                    }
                }

                val btPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasBtPerm = granted
                    peripherals = SideSystemCtl.queryPeripherals(context)
                    if (!granted) {
                        showNotice("Bluetooth permission denied — opening Bluetooth settings")
                        openBluetoothSettings(context)
                    }
                }

                // Poll status every 5 seconds while foregrounded
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (isActive) {
                            refreshAll(showSpinner = false)
                            delay(5_000L)
                        }
                    }
                }

                val callbacks = remember {
                    DashboardCallbacks(
                        onRefresh = {
                            scope.launch { refreshAll(showSpinner = true) }
                        },
                        onOpenDesktop = {
                            if (busyAction != null) return@DashboardCallbacks
                            scope.launch {
                                busyAction = "open_desktop"
                                try {
                                    val res = SideSystemCtl.desktopStart()
                                    refreshAll(showSpinner = false)
                                    if (!res.isSuccess && !status.desktopRunning) {
                                        showNotice("Desktop start failed: ${res.combinedError}")
                                        return@launch
                                    }
                                    val launched = launchVncViewer(context, status.vncPort)
                                    if (!launched) {
                                        Toast.makeText(
                                            context,
                                            "No VNC viewer installed — install AVNC (com.gaurav.avnc)",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        showNotice("Install AVNC to open vnc://127.0.0.1:${status.vncPort}")
                                    }
                                } finally {
                                    busyAction = null
                                }
                            }
                        },
                        onContainerStart = {
                            runAction("container_start", "NixOS container started") {
                                SideSystemCtl.containerStart()
                            }
                        },
                        onContainerRestart = {
                            runAction("container_restart", "NixOS container restarted") {
                                SideSystemCtl.containerRestart()
                            }
                        },
                        onContainerStop = {
                            runAction("container_stop", "NixOS container stopped") {
                                SideSystemCtl.containerStop()
                            }
                        },
                        onDesktopStart = {
                            runAction("desktop_start", "Hyprland desktop started") {
                                SideSystemCtl.desktopStart()
                            }
                        },
                        onDesktopRestart = {
                            runAction("desktop_restart", "Hyprland desktop restarted") {
                                SideSystemCtl.desktopRestart()
                            }
                        },
                        onDesktopStop = {
                            runAction("desktop_stop", "Hyprland desktop stopped") {
                                SideSystemCtl.desktopStop()
                            }
                        },
                        onToggleAutostart = { enabled ->
                            runAction(
                                "autostart",
                                if (enabled) "Auto-start enabled" else "Auto-start disabled"
                            ) {
                                SideSystemCtl.setAutostart(enabled)
                            }
                        },
                        onApplyDisplay = { res, scale ->
                            runAction(
                                "display",
                                "Applied ${res.label} @ ${scale.label} & restarted desktop"
                            ) {
                                SideSystemCtl.applyDisplay(res, scale)
                            }
                        },
                        onSelectProfile = { profile ->
                            runAction("profile", "Power profile set to ${profile.label}") {
                                SideSystemCtl.setProfile(profile)
                            }
                        },
                        onSelectGpuMode = { mode ->
                            runAction("gpu", "Graphics mode set to ${mode.label}") {
                                SideSystemCtl.setGpuMode(mode)
                            }
                        },
                        onCopySsh = { cmd ->
                            copyToClipboard(context, "SSH Command", cmd)
                            showNotice("Copied: $cmd")
                        },
                        onToggleSshd = { enabled ->
                            runAction(
                                "sshd",
                                if (enabled) "SSH daemon enabled" else "SSH daemon stopped"
                            ) {
                                SideSystemCtl.setSshd(enabled)
                            }
                        },
                        onRequestBluetoothPermission = {
                            btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        },
                        onOpenBluetoothSettings = {
                            openBluetoothSettings(context)
                        },
                        onOpenCastSettings = {
                            openCastSettings(context)
                        },
                        onSendClipboard = {
                            val clipText = readFromClipboard(context)
                            if (clipText.isNullOrEmpty()) {
                                showNotice("Android clipboard is empty")
                            } else {
                                runAction("clip_send", "Sent clipboard to NixOS") {
                                    SideSystemCtl.setClipboard(clipText)
                                }
                            }
                        },
                        onPullClipboard = {
                            if (busyAction != null) return@DashboardCallbacks
                            scope.launch {
                                busyAction = "clip_pull"
                                try {
                                    val res = SideSystemCtl.getClipboard()
                                    if (res.isSuccess) {
                                        if (res.stdout.isNotEmpty()) {
                                            copyToClipboard(context, "NixOS Clipboard", res.stdout)
                                            showNotice("Pulled clipboard from NixOS")
                                        } else {
                                            showNotice("NixOS clipboard is empty")
                                        }
                                    } else {
                                        showNotice("Clipboard pull failed: ${res.combinedError}")
                                    }
                                } finally {
                                    busyAction = null
                                }
                            }
                        },
                        onBackupNow = {
                            runAction("backup", "Backup to Google Drive completed") {
                                SideSystemCtl.backupNow()
                            }
                        },
                        onOpenLogs = {
                            showLogsSheet = true
                            loadLogs()
                        },
                        onDismissLogs = {
                            showLogsSheet = false
                        },
                        onRefreshLogs = {
                            loadLogs()
                        },
                        onCopyLogs = { text ->
                            copyToClipboard(context, "SideSystem Logs", text)
                            showNotice("Logs copied to clipboard")
                        }
                    )
                }

                DashboardScreen(
                    status = status,
                    peripherals = peripherals,
                    hasBluetoothPermission = hasBtPerm,
                    isRefreshing = isRefreshing,
                    busyAction = busyAction,
                    showLogsSheet = showLogsSheet,
                    logsContent = logsContent,
                    isLoadingLogs = isLoadingLogs,
                    snackbarHostState = snackbarHostState,
                    callbacks = callbacks
                )
            }
        }
    }

    private fun launchVncViewer(context: Context, port: Int): Boolean {
        val uri = Uri.parse("vnc://127.0.0.1:$port")
        val avncIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.gaurav.avnc")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(avncIntent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Fall back to any installed VNC viewer
        }

        val genericIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (genericIntent.resolveActivity(context.packageManager) == null) {
            return false
        }
        return try {
            context.startActivity(
                Intent.createChooser(genericIntent, "Open NixOS Desktop")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun readFromClipboard(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    private fun openBluetoothSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Unable to open Bluetooth settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCastSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_CAST_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "Unable to open Cast / Display settings", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
