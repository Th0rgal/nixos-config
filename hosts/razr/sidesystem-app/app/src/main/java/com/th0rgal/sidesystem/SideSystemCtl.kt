package com.th0rgal.sidesystem

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.view.InputDevice
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

enum class DisplayResolution(val cliValue: String, val label: String) {
    HD_720("1280x720", "720p"),
    FHD_1080("1920x1080", "1080p"),
    QHD_1440("2560x1440", "1440p"),
    UWQHD_3440("3440x1440", "3440×1440"),
    UHD_4K("3840x2160", "4K");

    companion object {
        fun fromString(raw: String?): DisplayResolution {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return when {
                normalized.contains("720") -> HD_720
                normalized.contains("3440") || normalized.contains("ultrawide") -> UWQHD_3440
                normalized.contains("3840") || normalized.contains("2160") || normalized.contains("4k") -> UHD_4K
                normalized.contains("1440") || normalized.contains("2560") -> QHD_1440
                else -> FHD_1080
            }
        }

        fun normalizeDisplayText(raw: String?): String {
            val cleaned = raw?.trim()?.replace("@60", "")?.replace("Hz", "", ignoreCase = true).orEmpty()
            return if (cleaned.matches(Regex("^\\d{3,4}x\\d{3,4}$"))) cleaned else fromString(raw).cliValue
        }
    }
}

enum class DisplayScale(val cliValue: String, val label: String, val hint: String) {
    HALF_X("0.5", "0.5x", "Zoom Out"),
    ONE_X("1", "1x", "Native"),
    ONE_HALF_X("1.5", "1.5x", "Medium"),
    TWO_X("2", "2x", "HiDPI");

    companion object {
        fun fromAny(raw: Any?): DisplayScale {
            val str = raw?.toString()?.trim()?.lowercase().orEmpty()
            return when {
                str.startsWith("0.5") -> HALF_X
                str.startsWith("1.5") -> ONE_HALF_X
                str.startsWith("2") -> TWO_X
                else -> ONE_X
            }
        }
    }
}

enum class PowerProfile(val cliValue: String, val label: String) {
    ECO("eco", "Eco"),
    BALANCED("balanced", "Balanced"),
    FULL("full", "Full");

    companion object {
        fun fromString(raw: String?): PowerProfile {
            return when (raw?.trim()?.lowercase()) {
                "eco", "low", "powersave" -> ECO
                "full", "performance", "max" -> FULL
                else -> BALANCED
            }
        }
    }
}

enum class GpuMode(val cliValue: String, val label: String, val subtitle: String) {
    LLVMPIPE("sw", "CPU llvmpipe", "Software"),
    TURNIP("hw", "GPU Turnip", "Adreno HW");

    companion object {
        fun fromString(raw: String?): GpuMode {
            return when (raw?.trim()?.lowercase()) {
                "turnip", "hw", "gpu", "zink" -> TURNIP
                else -> LLVMPIPE
            }
        }
    }
}

data class TelemetrySample(
    val cpuPct: Int,
    val gpuPct: Int,
    val memPct: Int
)

data class SideSystemStatus(
    val containerRunning: Boolean = false,
    val desktopRunning: Boolean = false,
    val systemdState: String = "stopped",
    val uptime: String = "--",
    val autostart: Boolean = false,
    val resolution: DisplayResolution = DisplayResolution.FHD_1080,
    val displayResolutionText: String = "1920x1080",
    val scale: DisplayScale = DisplayScale.ONE_X,
    val btHidGrab: Boolean = false,
    val memUsedMb: Int = 0,
    val memTotalMb: Int = 0,
    val cpuPct: Int = 0,
    val gpuPct: Int = 0,
    val powerProfile: PowerProfile = PowerProfile.BALANCED,
    val gpuMode: GpuMode = GpuMode.LLVMPIPE,
    val networkType: String = "Offline",
    val ipAddress: String = "127.0.0.1",
    val sshdRunning: Boolean = false,
    val sshUser: String = "thomas",
    val sshPort: Int = 2222,
    val vncPort: Int = 5900,
    val lastError: String? = null,
    val rootAvailable: Boolean = true
) {
    val sshCommand: String
        get() = "ssh -p $sshPort $sshUser@$ipAddress"

    val memFraction: Float
        get() = if (memTotalMb > 0) (memUsedMb.toFloat() / memTotalMb.toFloat()).coerceIn(0f, 1f) else 0f

    val memPct: Int
        get() = (memFraction * 100f).toInt().coerceIn(0, 100)
}

data class PeripheralDevice(
    val name: String,
    val kind: PeripheralKind,
    val source: String
)

enum class PeripheralKind {
    KEYBOARD,
    MOUSE,
    OTHER
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
    val combinedError: String
        get() = stderr.ifBlank { stdout }.ifBlank { "Exit code $exitCode" }.trim()
}

object SideSystemCtl {
    private const val CTL_BIN = "/data/local/nixos/bin/sidesystem-ctl"

    private suspend fun runCtl(
        vararg args: String,
        timeoutSeconds: Long = 20L
    ): CommandResult = withContext(Dispatchers.IO) {
        val quotedArgs = args.joinToString(" ") { shellQuote(it) }
        val command = if (quotedArgs.isEmpty()) CTL_BIN else "$CTL_BIN $quotedArgs"
        try {
            val process = ProcessBuilder("su", "-c", command).start()
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val outThread = Thread {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (stdoutBuilder.isNotEmpty()) stdoutBuilder.append('\n')
                        stdoutBuilder.append(line)
                    }
                }
            }
            val errThread = Thread {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (stderrBuilder.isNotEmpty()) stderrBuilder.append('\n')
                        stderrBuilder.append(line)
                    }
                }
            }
            outThread.start()
            errThread.start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                outThread.join(500)
                errThread.join(500)
                return@withContext CommandResult(
                    exitCode = 124,
                    stdout = stdoutBuilder.toString(),
                    stderr = "Command timed out after ${timeoutSeconds}s"
                )
            }

            outThread.join(1000)
            errThread.join(1000)
            CommandResult(
                exitCode = process.exitValue(),
                stdout = stdoutBuilder.toString().trim(),
                stderr = stderrBuilder.toString().trim()
            )
        } catch (e: Exception) {
            CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Failed to invoke su"
            )
        }
    }

    private fun shellQuote(arg: String): String {
        return "'" + arg.replace("'", "'\"'\"'") + "'"
    }

    suspend fun fetchStatus(context: Context): SideSystemStatus {
        val (fallbackNet, fallbackIp) = readAndroidNetworkInfo(context)
        val result = runCtl("status", "--json", timeoutSeconds = 12L)
        if (!result.isSuccess) {
            val isRootErr = result.combinedError.contains("su", ignoreCase = true) ||
                result.combinedError.contains("permission denied", ignoreCase = true) ||
                result.exitCode == -1
            return SideSystemStatus(
                networkType = fallbackNet,
                ipAddress = fallbackIp,
                lastError = result.combinedError,
                rootAvailable = !isRootErr
            )
        }
        return parseStatusJson(result.stdout, fallbackNet, fallbackIp)
    }

    suspend fun containerStart(): CommandResult = runCtl("container", "start", timeoutSeconds = 45L)
    suspend fun containerRestart(): CommandResult = runCtl("container", "restart", timeoutSeconds = 60L)
    suspend fun containerStop(): CommandResult = runCtl("container", "stop", timeoutSeconds = 30L)

    suspend fun desktopStart(): CommandResult = runCtl("desktop", "start", timeoutSeconds = 45L)
    suspend fun desktopRestart(): CommandResult = runCtl("desktop", "restart", timeoutSeconds = 45L)
    suspend fun desktopStop(): CommandResult = runCtl("desktop", "stop", timeoutSeconds = 30L)

    suspend fun setAutostart(enabled: Boolean): CommandResult =
        runCtl("autostart", if (enabled) "on" else "off")

    suspend fun applyDisplay(resolutionSpec: String, scale: DisplayScale): CommandResult =
        runCtl("desktop", "start", resolutionSpec.trim(), scale.cliValue, timeoutSeconds = 45L)

    suspend fun setBtHidGrab(enabled: Boolean): CommandResult =
        runCtl("bt-hid", if (enabled) "on" else "off", timeoutSeconds = 15L)

    suspend fun setProfile(profile: PowerProfile): CommandResult =
        runCtl("profile", profile.cliValue)

    suspend fun setGpuMode(mode: GpuMode): CommandResult =
        runCtl("gpu", mode.cliValue)

    suspend fun setSshd(enabled: Boolean): CommandResult =
        runCtl("sshd", if (enabled) "on" else "off")

    suspend fun setClipboard(text: String): CommandResult {
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return runCtl("clipboard", "set", encoded)
    }

    suspend fun getClipboard(): CommandResult = runCtl("clipboard", "get")

    suspend fun backupNow(): CommandResult = runCtl("backup", "now", timeoutSeconds = 120L)

    suspend fun fetchLogs(): CommandResult = runCtl("logs", timeoutSeconds = 15L)

    private fun parseStatusJson(
        raw: String,
        fallbackNet: String,
        fallbackIp: String
    ): SideSystemStatus {
        return try {
            val jsonStart = raw.indexOf('{')
            val jsonText = if (jsonStart >= 0) raw.substring(jsonStart) else raw
            val obj = JSONObject(jsonText)

            val containerObj = obj.optJSONObject("container")
            val desktopObj = obj.optJSONObject("desktop") ?: obj.optJSONObject("hyprland")
            val networkObj = obj.optJSONObject("network")

            val containerRunning = when {
                obj.has("container_running") -> obj.optBoolean("container_running")
                containerObj != null -> containerObj.optBoolean("running")
                obj.has("container") -> {
                    val v = obj.opt("container")
                    v == true || v.toString().equals("running", ignoreCase = true)
                }
                else -> false
            }

            val desktopRunning = when {
                obj.has("hyprland_running") -> obj.optBoolean("hyprland_running")
                obj.has("desktop_running") -> obj.optBoolean("desktop_running")
                desktopObj != null -> desktopObj.optBoolean("running")
                obj.has("hyprland") -> {
                    val v = obj.opt("hyprland")
                    v == true || v.toString().equals("live", ignoreCase = true) ||
                        v.toString().equals("running", ignoreCase = true)
                }
                else -> false
            }

            val systemdState = obj.optString(
                "systemd_state",
                obj.optString("systemd", if (containerRunning) "running" else "stopped")
            ).ifBlank { if (containerRunning) "running" else "stopped" }

            val uptime = obj.optString("uptime", "--").ifBlank { "--" }
            val autostart = obj.optBoolean("autostart", false)

            val rawDisplay = obj.optString("resolution", obj.optString("display_resolution", obj.optString("display_mode", "1920x1080")))
            val resolution = DisplayResolution.fromString(rawDisplay)
            val displayResolutionText = DisplayResolution.normalizeDisplayText(rawDisplay)
            val scale = DisplayScale.fromAny(
                obj.opt("scale") ?: obj.opt("display_scale") ?: 1
            )
            val btHidGrab = obj.optBoolean("bt_hid_grab", false)

            val memUsed = obj.optInt("mem_used_mb", 0)
            val memTotal = obj.optInt("mem_total_mb", 0)
            val cpuPct = obj.optInt("cpu_pct", 0).coerceIn(0, 100)
            val gpuPct = obj.optInt("gpu_pct", 0).coerceIn(0, 100)

            val profile = PowerProfile.fromString(
                obj.optString("profile", obj.optString("power_profile", "balanced"))
            )
            val gpuMode = GpuMode.fromString(
                obj.optString("gpu_mode", obj.optString("gpu", "llvmpipe"))
            )

            val netType = obj.optString(
                "network_type",
                obj.optString("link_type", networkObj?.optString("type").orEmpty())
            ).ifBlank { fallbackNet }

            val ip = obj.optString(
                "ip",
                obj.optString("ip_address", networkObj?.optString("ip").orEmpty())
            ).ifBlank { fallbackIp }

            val sshdRunning = when {
                obj.has("sshd_running") -> obj.optBoolean("sshd_running")
                obj.has("sshd") -> {
                    val v = obj.opt("sshd")
                    v == true || v.toString().equals("on", ignoreCase = true) ||
                        v.toString().equals("running", ignoreCase = true)
                }
                else -> false
            }

            val sshUser = obj.optString("ssh_user", "thomas").ifBlank { "thomas" }
            val sshPort = obj.optInt("ssh_port", 2222).let { if (it > 0) it else 2222 }
            val vncPort = obj.optInt("vnc_port", 5900).let { if (it > 0) it else 5900 }

            SideSystemStatus(
                containerRunning = containerRunning,
                desktopRunning = desktopRunning,
                systemdState = systemdState,
                uptime = uptime,
                autostart = autostart,
                resolution = resolution,
                displayResolutionText = displayResolutionText,
                scale = scale,
                btHidGrab = btHidGrab,
                memUsedMb = memUsed,
                memTotalMb = memTotal,
                cpuPct = cpuPct,
                gpuPct = gpuPct,
                powerProfile = profile,
                gpuMode = gpuMode,
                networkType = netType,
                ipAddress = ip,
                sshdRunning = sshdRunning,
                sshUser = sshUser,
                sshPort = sshPort,
                vncPort = vncPort,
                lastError = null,
                rootAvailable = true
            )
        } catch (e: Exception) {
            SideSystemStatus(
                networkType = fallbackNet,
                ipAddress = fallbackIp,
                lastError = "Invalid status JSON: ${e.message}"
            )
        }
    }

    fun readAndroidNetworkInfo(context: Context): Pair<String, String> {
        var netLabel = "Offline"
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            netLabel = when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "4G / 5G"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Connected"
            }
        } catch (_: Exception) {
            // Fall back to interface scan
        }

        val ip = findLocalIpv4() ?: "127.0.0.1"
        return netLabel to ip
    }

    private fun findLocalIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            var fallback: String? = null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses.toList()
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                            return host
                        }
                        if (fallback == null) fallback = host
                    }
                }
            }
            fallback
        } catch (_: Exception) {
            null
        }
    }

    fun hasBluetoothPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun queryPeripherals(context: Context): List<PeripheralDevice> {
        val result = LinkedHashMap<String, PeripheralDevice>()

        // 1. Query Android InputManager for external keyboards and mice (no BT permission required)
        try {
            val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            im?.inputDeviceIds?.forEach { id ->
                val dev = im.getInputDevice(id) ?: return@forEach
                if (!dev.isExternal || dev.isVirtual) return@forEach
                val sources = dev.sources
                val isMouse = (sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                    (sources and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
                val isKeyboard = (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
                    dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                if (isMouse || isKeyboard) {
                    val name = dev.name.orEmpty().trim()
                    if (name.isNotEmpty()) {
                        val kind = if (isMouse) PeripheralKind.MOUSE else PeripheralKind.KEYBOARD
                        result[name.lowercase()] = PeripheralDevice(
                            name = name,
                            kind = kind,
                            source = "HID"
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Safe fallback
        }

        // 2. Query connected Bluetooth HID peripherals when BLUETOOTH_CONNECT is granted
        if (hasBluetoothPermission(context)) {
            try {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val bonded = bm?.adapter?.bondedDevices.orEmpty()
                for (device in bonded) {
                    if (!isBluetoothDeviceConnected(device)) continue
                    val major = device.bluetoothClass?.majorDeviceClass
                    if (major != BluetoothClass.Device.Major.PERIPHERAL) continue
                    val name = device.name?.trim().orEmpty().ifBlank { device.address }
                    val minor = device.bluetoothClass?.deviceClass ?: 0
                    val kind = when {
                        (minor and 0x0540) == 0x0540 -> PeripheralKind.KEYBOARD
                        (minor and 0x0580) == 0x0580 -> PeripheralKind.MOUSE
                        name.contains("mouse", ignoreCase = true) ||
                            name.contains("master", ignoreCase = true) -> PeripheralKind.MOUSE
                        name.contains("key", ignoreCase = true) -> PeripheralKind.KEYBOARD
                        else -> PeripheralKind.OTHER
                    }
                    result[name.lowercase()] = PeripheralDevice(
                        name = name,
                        kind = kind,
                        source = "Bluetooth"
                    )
                }
            } catch (_: Exception) {
                // Safe fallback
            }
        }

        return result.values.toList()
    }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("isConnected")
            (method.invoke(device) as? Boolean) == true
        } catch (_: Exception) {
            false
        }
    }
}
