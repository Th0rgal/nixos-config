package com.th0rgal.sidesystem

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Full-screen embedded NixOS desktop viewer inside SideSystem.
 *
 * - Automatically syncs Hyprland's HEADLESS-1 output to the exact aspect ratio and
 *   Hyprland-valid scale (1.0 / 1.25) of the Razr's current screen via Lua `hyprctl eval`.
 * - Renders loopback RFB 3.8 directly onto a hardware-accelerated SurfaceView (0 Compose
 *   recomposition per frame).
 * - Uses native Compose BasicTextField + FocusRequester + LocalSoftwareKeyboardController
 *   so tapping Keys reliably opens the full normal Android keyboard (Gboard) alongside a
 *   floating sticky-modifier & nav bar (Esc, Tab, Ctrl, Opt, Cmd, arrows).
 */
class DesktopActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PORT = "vnc_port"
        const val EXTRA_THEME_MODE = "theme_mode"
        private const val RES_OWNER_FILE = "/data/local/nixos/rootfs/var/lib/sidesystem/res-owner"

        private const val XK_BACKSPACE = 0xff08
        private const val XK_TAB = 0xff09
        private const val XK_RETURN = 0xff0d
        private const val XK_ESCAPE = 0xff1b
        private const val XK_LEFT = 0xff51
        private const val XK_UP = 0xff52
        private const val XK_RIGHT = 0xff53
        private const val XK_DOWN = 0xff54
        private const val XK_CTRL_L = 0xffe3
        private const val XK_ALT_L = 0xffe9
        private const val XK_SUPER_L = 0xffeb
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rfb: RfbClient? = null
    private var desktopView: DesktopSurfaceView? = null

    private var connectionText by mutableStateOf("Connecting…")
    private var isConnected by mutableStateOf(false)
    private var overlayExpanded by mutableStateOf(false)
    private var handMouseEnabled by mutableStateOf(true)
    private var keyboardVisible by mutableStateOf(false)
    private var stickyCtrl by mutableStateOf(false)
    private var stickyAlt by mutableStateOf(false)
    private var stickySuper by mutableStateOf(false)
    private var resPriority by mutableStateOf("phone") // "phone" | "mac" | "auto"
    private var phoneScale by mutableStateOf("1.5")  // "1.0" | "1.25" | "1.5" | "2.0"
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var vncPort: Int = 5900
    private var cutoutRect by mutableStateOf<Rect?>(null)
    private var screenBounds by mutableStateOf(Rect(0, 0, 2992, 1224))

    private fun refreshCutoutMetrics() {
        val metrics = windowManager.currentWindowMetrics
        screenBounds = metrics.bounds
        cutoutRect = metrics.windowInsets.displayCutout?.boundingRects?.firstOrNull()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vncPort = intent?.getIntExtra(EXTRA_PORT, 5900) ?: 5900
        themeMode = ThemeMode.fromKey(intent?.getStringExtra(EXTRA_THEME_MODE))
        phoneScale = getSharedPreferences("sidesystem_prefs", Context.MODE_PRIVATE)
            .getString("phone_scale", "1.5") ?: "1.5"

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        refreshCutoutMetrics()

        loadResPriorityAndMaybeSync()

        val client = RfbClient(
            host = "127.0.0.1",
            port = vncPort,
            onFrame = { bmp, w, h ->
                desktopView?.submitFrame(bmp, w, h)
            },
            onState = { text, connected ->
                runOnUiThread {
                    connectionText = text
                    isConnected = connected
                }
            }
        )
        rfb = client
        client.start()

        setContent {
            SideSystemTheme(themeMode = themeMode) {
                DesktopOverlayRoot()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
        refreshCutoutMetrics()
        if (resPriority != "mac") {
            syncResolutionToCurrentScreen()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseStickyModifiers()
        rfb?.stop()
        scope.cancel()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = androidKeyToX11(keyCode, event)
        if (keysym != 0) {
            sendKeyWithStickyModifiers(keysym)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun loadResPriorityAndMaybeSync() {
        scope.launch {
            val saved = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $RES_OWNER_FILE 2>/dev/null || echo phone"))
                val txt = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                when (txt) {
                    "phone", "mac", "auto" -> txt
                    else -> "phone"
                }
            } catch (_: Exception) {
                "phone"
            }
            runOnUiThread { resPriority = saved }
            if (saved != "mac") {
                syncResolutionToCurrentScreen()
            }
        }
    }

    private fun cycleResPriority() {
        val next = when (resPriority) {
            "phone" -> "mac"
            "mac" -> "auto"
            else -> "phone"
        }
        resPriority = next
        scope.launch {
            try {
                Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "printf '%s\\n' '$next' > $RES_OWNER_FILE && chmod 666 $RES_OWNER_FILE")
                ).waitFor()
            } catch (_: Exception) {}
            if (next == "phone") {
                syncResolutionToCurrentScreen()
            }
        }
    }

    private fun cyclePhoneScale() {
        val next = when (phoneScale) {
            "1.0" -> "1.25"
            "1.25" -> "1.5"
            "1.5" -> "2.0"
            else -> "1.0"
        }
        phoneScale = next
        getSharedPreferences("sidesystem_prefs", Context.MODE_PRIVATE)
            .edit().putString("phone_scale", next).apply()
        syncResolutionToCurrentScreen()
    }

    private fun syncResolutionToCurrentScreen() {
        val bounds = windowManager.currentWindowMetrics.bounds
        val physW = bounds.width().coerceAtLeast(320)
        val physH = bounds.height().coerceAtLeast(320)

        // Pick an exact-aspect-ratio crisp resolution (~1.85M px) with dimensions divisible by 60
        // so ALL four scales (1.0, 1.25, 1.5, 2.0) divide to exact integers with 0 Hyprland warnings.
        val maxPixels = 1_850_000.0
        val currentPixels = physW.toDouble() * physH.toDouble()
        val downscale = if (currentPixels > maxPixels) {
            kotlin.math.sqrt(currentPixels / maxPixels)
        } else {
            1.0
        }
        val targetW = (((physW / downscale).roundToInt() + 30) / 60) * 60
        val targetH = (((physH / downscale).roundToInt() + 30) / 60) * 60
        val hyprScale = phoneScale

        scope.launch {
            val d = '$'
            val cmd = """
                /data/local/nixos/bin/droidspaces --name=razr run /run/current-system/sw/bin/bash -c '
                  XDG_RT=/tmp/xdg-razr
                  SIG=${d}(ls "${d}XDG_RT/hypr/" 2>/dev/null | head -n1)
                  [ -n "${d}SIG" ] || exit 0
                  runuser -u thomas -- env XDG_RUNTIME_DIR="${d}XDG_RT" HYPRLAND_INSTANCE_SIGNATURE="${d}SIG" \
                    /run/current-system/sw/bin/hyprctl eval "hl.monitor({ output = \"HEADLESS-1\", mode = \"${targetW}x${targetH}@60\", position = \"0x0\", scale = \"${hyprScale}\" })" >/dev/null 2>&1 || true
                '
            """.trimIndent()
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
            } catch (_: Exception) {}
        }
    }

    private fun sendKeyWithStickyModifiers(keysym: Int) {
        val client = rfb ?: return
        val ctrl = stickyCtrl
        val alt = stickyAlt
        val sup = stickySuper
        if (ctrl) client.sendKeyEvent(XK_CTRL_L, true)
        if (alt) client.sendKeyEvent(XK_ALT_L, true)
        if (sup) client.sendKeyEvent(XK_SUPER_L, true)
        client.sendKeyTap(keysym)
        if (sup) client.sendKeyEvent(XK_SUPER_L, false)
        if (alt) client.sendKeyEvent(XK_ALT_L, false)
        if (ctrl) client.sendKeyEvent(XK_CTRL_L, false)
        if (ctrl || alt || sup) {
            stickyCtrl = false
            stickyAlt = false
            stickySuper = false
        }
    }

    private fun releaseStickyModifiers() {
        val client = rfb ?: return
        if (stickyCtrl) client.sendKeyEvent(XK_CTRL_L, false)
        if (stickyAlt) client.sendKeyEvent(XK_ALT_L, false)
        if (stickySuper) client.sendKeyEvent(XK_SUPER_L, false)
        stickyCtrl = false
        stickyAlt = false
        stickySuper = false
    }

    private fun unicodeToX11Keysym(ch: Char): Int {
        return when (ch) {
            '\n', '\r' -> XK_RETURN
            '\t' -> XK_TAB
            '\b' -> XK_BACKSPACE
            else -> {
                val code = ch.code
                if (code in 0x20..0x7e) code else (0x01000000 or code)
            }
        }
    }

    private fun androidKeyToX11(keyCode: Int, event: KeyEvent): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> XK_BACKSPACE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> XK_RETURN
            KeyEvent.KEYCODE_TAB -> XK_TAB
            KeyEvent.KEYCODE_ESCAPE -> XK_ESCAPE
            KeyEvent.KEYCODE_DPAD_LEFT -> XK_LEFT
            KeyEvent.KEYCODE_DPAD_UP -> XK_UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> XK_RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> XK_DOWN
            else -> {
                val uc = event.unicodeChar
                if (uc > 0) unicodeToX11Keysym(uc.toChar()) else 0
            }
        }
    }

    @Composable
    private fun DesktopOverlayRoot() {
        val pillBg = OrbSurface.copy(alpha = 0.90f)
        val pillBorder = OrbBorder
        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        var imeSentinel by remember { mutableStateOf(TextFieldValue(" ", selection = TextRange(1))) }

        LaunchedEffect(keyboardVisible) {
            if (keyboardVisible) {
                delay(60)
                try {
                    focusRequester.requestFocus()
                } catch (_: Exception) {}
                keyboardController?.show()
            } else {
                keyboardController?.hide()
                hideSystemBars()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // 1. Hardware-accelerated RFB desktop surface
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    DesktopSurfaceView(
                        context = ctx,
                        isHandMouse = { handMouseEnabled },
                        onPointer = { x, y, mask -> rfb?.sendPointerEvent(x, y, mask) }
                    ).also { desktopView = it }
                }
            )

            // 2. Native Compose BasicTextField with sentinel space so Android's normal
            //    Gboard soft keyboard opens 100% reliably and delivers both text & Backspace.
            BasicTextField(
                value = imeSentinel,
                onValueChange = { next ->
                    val txt = next.text
                    when {
                        txt.isEmpty() -> {
                            sendKeyWithStickyModifiers(XK_BACKSPACE)
                        }
                        txt.length > 1 -> {
                            val typed = if (txt.startsWith(" ")) txt.substring(1) else txt
                            for (ch in typed) {
                                sendKeyWithStickyModifiers(unicodeToX11Keysym(ch))
                            }
                        }
                        txt != " " -> {
                            sendKeyWithStickyModifiers(unicodeToX11Keysym(txt[0]))
                        }
                    }
                    imeSentinel = TextFieldValue(" ", selection = TextRange(1))
                },
                singleLine = true,
                cursorBrush = SolidColor(Color.Transparent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { sendKeyWithStickyModifiers(XK_RETURN) },
                    onDone = { sendKeyWithStickyModifiers(XK_RETURN) }
                ),
                modifier = Modifier
                    .size(2.dp)
                    .alpha(0.01f)
                    .focusRequester(focusRequester)
            )

            // 3. Connection status badge while connecting/reconnecting
            AnimatedVisibility(
                visible = !isConnected,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = pillBg,
                    border = BorderStroke(1.dp, pillBorder)
                ) {
                    Text(
                        text = connectionText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OrbFgStrong,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            // 4. Dynamic-Island Camera-Hugging Overlay Pill (wraps the physical camera cutout!)
            val density = LocalDensity.current
            val cRect = cutoutRect
            val sBounds = screenBounds
            val isTopCutout = cRect != null && cRect.top <= 12 && cRect.width() < sBounds.width() / 2
            val isLeftCutout = cRect != null && !isTopCutout && cRect.left <= 12
            val isRightCutout = cRect != null && !isTopCutout && !isLeftCutout && cRect.right >= sBounds.width() - 12

            val islandBg = Color(0xFF000000)
            val islandBorder = Color.White.copy(alpha = 0.14f)
            val cutoutWdp = with(density) { (cRect?.width() ?: 120).toDp() }
            val cutoutHdp = with(density) { (cRect?.height() ?: 110).toDp() }

            val islandAlignment = when {
                isTopCutout -> Alignment.TopCenter
                isLeftCutout -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }

            Box(
                modifier = Modifier
                    .align(islandAlignment)
                    .padding(
                        top = if (isTopCutout) 4.dp else 0.dp,
                        start = if (isLeftCutout) 4.dp else 0.dp,
                        end = if (isRightCutout || (!isTopCutout && !isLeftCutout)) 4.dp else 0.dp
                    )
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (overlayExpanded) pillBg else islandBg,
                    border = BorderStroke(1.dp, if (overlayExpanded) pillBorder else islandBorder),
                    modifier = Modifier.animateContentSize()
                ) {
                    if (!overlayExpanded) {
                        if (isTopCutout) {
                            // Portrait top-center Dynamic Island wrapping the punch-hole lens
                            Row(
                                modifier = Modifier
                                    .clickable { overlayExpanded = true }
                                    .height(cutoutHdp.coerceAtLeast(34.dp))
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Spacer(modifier = Modifier.width(cutoutWdp.coerceAtLeast(34.dp)))
                                Icon(
                                    imageVector = Icons.Rounded.ExpandMore,
                                    contentDescription = "Open controls",
                                    tint = Color.White.copy(alpha = 0.78f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else if (isLeftCutout) {
                            // Landscape-left Dynamic Island wrapping the left camera punch-hole
                            Row(
                                modifier = Modifier
                                    .clickable { overlayExpanded = true }
                                    .height(cutoutHdp.coerceAtLeast(38.dp))
                                    .width((cutoutWdp + 22.dp).coerceAtLeast(58.dp))
                                    .padding(end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = "Open controls",
                                    tint = Color.White.copy(alpha = 0.78f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            // Landscape-right Dynamic Island wrapping the right camera punch-hole
                            Row(
                                modifier = Modifier
                                    .clickable { overlayExpanded = true }
                                    .height(cutoutHdp.coerceAtLeast(38.dp))
                                    .width((cutoutWdp + 22.dp).coerceAtLeast(58.dp))
                                    .padding(start = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronLeft,
                                    contentDescription = "Open controls",
                                    tint = Color.White.copy(alpha = 0.78f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else if (isTopCutout) {
                        // Expanded horizontal rail for portrait top camera
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            OverlayRailButton(Icons.Rounded.ExpandMore, "Hide", false) { overlayExpanded = false }
                            OverlayRailButton(Icons.Rounded.Keyboard, "Keys", keyboardVisible) { keyboardVisible = !keyboardVisible }
                            OverlayRailButton(
                                if (handMouseEnabled) Icons.Rounded.Mouse else Icons.Rounded.TouchApp,
                                if (handMouseEnabled) "Mouse" else "Touch",
                                handMouseEnabled
                            ) { handMouseEnabled = !handMouseEnabled }
                            OverlayRailButton(
                                when (resPriority) {
                                    "phone" -> Icons.Rounded.PhoneAndroid
                                    "mac" -> Icons.Rounded.DesktopWindows
                                    else -> Icons.Rounded.Sync
                                },
                                when (resPriority) {
                                    "phone" -> "Phone"
                                    "mac" -> "Mac"
                                    else -> "Auto"
                                },
                                resPriority == "phone"
                            ) { cycleResPriority() }
                            OverlayRailButton(Icons.Rounded.ZoomIn, "${phoneScale}x", phoneScale != "1.0") {
                                cyclePhoneScale()
                            }
                            OverlayRailButton(Icons.Rounded.Refresh, "Fit", false) {
                                syncResolutionToCurrentScreen()
                                rfb?.requestFullRefresh()
                            }
                            OverlayRailButton(Icons.Rounded.Close, "Exit", false, tintOverride = OrbRed) { finish() }
                        }
                    } else {
                        // Expanded vertical rail shifted cleanly inward from the camera lens
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(
                                start = if (isLeftCutout) (cutoutWdp - 4.dp).coerceAtLeast(0.dp) else 4.dp,
                                end = if (isRightCutout) (cutoutWdp - 4.dp).coerceAtLeast(0.dp) else 4.dp,
                                top = 6.dp,
                                bottom = 6.dp
                            )
                        ) {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                OverlayRailButton(
                                    if (isLeftCutout) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight,
                                    "Hide",
                                    false
                                ) { overlayExpanded = false }
                                OverlayRailButton(Icons.Rounded.Keyboard, "Keys", keyboardVisible) { keyboardVisible = !keyboardVisible }
                                OverlayRailButton(
                                    if (handMouseEnabled) Icons.Rounded.Mouse else Icons.Rounded.TouchApp,
                                    if (handMouseEnabled) "Mouse" else "Touch",
                                    handMouseEnabled
                                ) { handMouseEnabled = !handMouseEnabled }
                                OverlayRailButton(
                                    when (resPriority) {
                                        "phone" -> Icons.Rounded.PhoneAndroid
                                        "mac" -> Icons.Rounded.DesktopWindows
                                        else -> Icons.Rounded.Sync
                                    },
                                    when (resPriority) {
                                        "phone" -> "Phone"
                                        "mac" -> "Mac"
                                        else -> "Auto"
                                    },
                                    resPriority == "phone"
                                ) { cycleResPriority() }
                                OverlayRailButton(Icons.Rounded.ZoomIn, "${phoneScale}x", phoneScale != "1.0") {
                                    cyclePhoneScale()
                                }
                                OverlayRailButton(Icons.Rounded.Refresh, "Fit", false) {
                                    syncResolutionToCurrentScreen()
                                    rfb?.requestFullRefresh()
                                }
                                OverlayRailButton(Icons.Rounded.Close, "Exit", false, tintOverride = OrbRed) { finish() }
                            }
                        }
                    }
                }
            }

            // 5. Floating modifier & nav shortcut bar sitting cleanly above the Android soft keyboard
            AnimatedVisibility(
                visible = keyboardVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = pillBg,
                    border = BorderStroke(1.dp, pillBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ModifierKeyChip("Esc", false) { sendKeyWithStickyModifiers(XK_ESCAPE) }
                        ModifierKeyChip("Tab", false) { sendKeyWithStickyModifiers(XK_TAB) }
                        ModifierKeyChip("Ctrl", stickyCtrl) { stickyCtrl = !stickyCtrl }
                        ModifierKeyChip("Opt", stickyAlt) { stickyAlt = !stickyAlt }
                        ModifierKeyChip("Cmd", stickySuper) { stickySuper = !stickySuper }
                        ModifierKeyChip("Space", false) { sendKeyWithStickyModifiers(0x20) }
                        ModifierKeyChip("Enter", false) { sendKeyWithStickyModifiers(XK_RETURN) }
                        ModifierKeyChip("←", false) { sendKeyWithStickyModifiers(XK_LEFT) }
                        ModifierKeyChip("↓", false) { sendKeyWithStickyModifiers(XK_DOWN) }
                        ModifierKeyChip("↑", false) { sendKeyWithStickyModifiers(XK_UP) }
                        ModifierKeyChip("→", false) { sendKeyWithStickyModifiers(XK_RIGHT) }
                    }
                }
            }
        }
    }

    @Composable
    private fun OverlayRailButton(
        icon: ImageVector,
        label: String,
        active: Boolean,
        tintOverride: Color? = null,
        onClick: () -> Unit
    ) {
        val bg = if (active) OrbBlue.copy(alpha = 0.22f) else Color.Transparent
        val fg = tintOverride ?: if (active) OrbBlue else OrbFgStrong
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .clickable(onClick = onClick)
                .padding(horizontal = 7.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = fg,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
                color = fg
            )
        }
    }

    @Composable
    private fun ModifierKeyChip(label: String, active: Boolean, onClick: () -> Unit) {
        val bg = if (active) OrbBlue.copy(alpha = 0.25f) else OrbSurfaceHover
        val fg = if (active) OrbBlue else OrbFgStrong
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = bg,
            border = BorderStroke(1.dp, if (active) OrbBlue else OrbBorder),
            modifier = Modifier.clickable(onClick = onClick)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = fg,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * Hardware-accelerated SurfaceView rendering RFB bitmaps with 0 allocations per frame
 * and accurate Trackpad ("Hand-as-Mouse") + Direct Touch input modes.
 */
private class DesktopSurfaceView(
    context: Context,
    private val isHandMouse: () -> Boolean,
    private val onPointer: (Int, Int, Int) -> Unit
) : SurfaceView(context), SurfaceHolder.Callback {

    private var surfaceReady = false
    @Volatile private var latestBitmap: Bitmap? = null
    @Volatile private var fbW: Int = 1
    @Volatile private var fbH: Int = 1

    private val srcRect = Rect()
    private val dstRect = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Virtual pointer state in framebuffer coordinates
    private var cursorX: Float = 400f
    private var cursorY: Float = 300f

    // Gesture tracking state
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var maxPointers = 0
    private var moved = false
    private var scrollAccumY = 0f
    private var draggingButton = false
    private var lastTapTime = 0L

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        drawLatest()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        drawLatest()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    fun submitFrame(bmp: Bitmap, w: Int, h: Int) {
        latestBitmap = bmp
        if (w != fbW || h != fbH) {
            fbW = w.coerceAtLeast(1)
            fbH = h.coerceAtLeast(1)
            cursorX = (fbW * 0.5f).coerceIn(0f, fbW - 1f)
            cursorY = (fbH * 0.5f).coerceIn(0f, fbH - 1f)
        }
        drawLatest()
    }

    private fun computeFitRect(viewW: Float, viewH: Float) {
        val scale = minOf(viewW / fbW.toFloat(), viewH / fbH.toFloat())
        val drawW = fbW * scale
        val drawH = fbH * scale
        val left = (viewW - drawW) * 0.5f
        val top = (viewH - drawH) * 0.5f
        dstRect.set(left, top, left + drawW, top + drawH)
    }

    @Synchronized
    private fun drawLatest() {
        if (!surfaceReady) return
        val bmp = latestBitmap ?: return
        val canvas: Canvas = try {
            holder.lockHardwareCanvas()
        } catch (_: Exception) {
            try { holder.lockCanvas() } catch (_: Exception) { return }
        } ?: return
        try {
            canvas.drawColor(0xFF000000.toInt())
            val vw = canvas.width.toFloat()
            val vh = canvas.height.toFloat()
            computeFitRect(vw, vh)
            srcRect.set(0, 0, fbW, fbH)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
        } finally {
            try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
        }
    }

    private fun viewToFbX(vx: Float): Float {
        if (dstRect.width() <= 1f) return cursorX
        return (((vx - dstRect.left) / dstRect.width()) * fbW).coerceIn(0f, fbW - 1f)
    }

    private fun viewToFbY(vy: Float): Float {
        if (dstRect.height() <= 1f) return cursorY
        return (((vy - dstRect.top) / dstRect.height()) * fbH).coerceIn(0f, fbH - 1f)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val trackpad = isHandMouse()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                lastX = ev.x; lastY = ev.y
                downTime = ev.eventTime
                maxPointers = 1
                moved = false
                scrollAccumY = 0f
                if (trackpad) {
                    if (ev.eventTime - lastTapTime < 260L) {
                        draggingButton = true
                        onPointer(cursorX.roundToInt(), cursorY.roundToInt(), 1)
                    }
                } else {
                    cursorX = viewToFbX(ev.x)
                    cursorY = viewToFbY(ev.y)
                    // Always warp pointer first before pressing button 1 so Hyprland clicks exact pixel
                    onPointer(cursorX.roundToInt(), cursorY.roundToInt(), 0)
                    onPointer(cursorX.roundToInt(), cursorY.roundToInt(), 1)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, ev.pointerCount)
                if (ev.pointerCount == 2) {
                    lastY = (ev.getY(0) + ev.getY(1)) * 0.5f
                    if (!trackpad) {
                        onPointer(cursorX.roundToInt(), cursorY.roundToInt(), 0)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 2) {
                    val midY = (ev.getY(0) + ev.getY(1)) * 0.5f
                    val dy = midY - lastY
                    lastY = midY
                    if (abs(dy) > 2f) moved = true
                    scrollAccumY += dy
                    val step = 28f
                    while (abs(scrollAccumY) >= step) {
                        val btn = if (scrollAccumY > 0) 8 else 16
                        onPointer(cursorX.roundToInt(), cursorY.roundToInt(), btn)
                        onPointer(cursorX.roundToInt(), cursorY.roundToInt(), 0)
                        scrollAccumY += if (scrollAccumY > 0) -step else step
                    }
                } else if (trackpad) {
                    val rawDx = ev.x - lastX
                    val rawDy = ev.y - lastY
                    lastX = ev.x; lastY = ev.y
                    if (hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble()) > 10.0) moved = true
                    // Convert physical screen delta to framebuffer space with smooth natural gain (1.0x..1.65x)
                    val sx = if (dstRect.width() > 1f) fbW.toFloat() / dstRect.width() else 1f
                    val sy = if (dstRect.height() > 1f) fbH.toFloat() / dstRect.height() else 1f
                    val fdx = rawDx * sx
                    val fdy = rawDy * sy
                    val speed = hypot(fdx.toDouble(), fdy.toDouble()).toFloat()
                    val gain = 1.0f + ((speed - 4f).coerceAtLeast(0f) / 36f).coerceAtMost(0.65f)
                    cursorX = (cursorX + fdx * gain).coerceIn(0f, fbW - 1f)
                    cursorY = (cursorY + fdy * gain).coerceIn(0f, fbH - 1f)
                    onPointer(cursorX.roundToInt(), cursorY.roundToInt(), if (draggingButton) 1 else 0)
                } else {
                    cursorX = viewToFbX(ev.x)
                    cursorY = viewToFbY(ev.y)
                    onPointer(cursorX.roundToInt(), cursorY.roundToInt(), 1)
                }
            }
            MotionEvent.ACTION_UP -> {
                val dur = ev.eventTime - downTime
                if (trackpad) {
                    if (draggingButton) {
                        draggingButton = false
                        onPointer(cursorX.roundToInt(), cursorY.roundToInt(), 0)
                    } else if (!moved && dur < 280L) {
                        val btn = if (maxPointers >= 2) 4 else 1
                        onPointer(cursorX.roundToInt(), cursorY.roundToInt(), btn)
                        onPointer(cursorX.roundToInt(), cursorY.roundToInt(), 0)
                        if (maxPointers == 1) lastTapTime = ev.eventTime
                    }
                } else {
                    if (!moved && maxPointers >= 2 && dur < 320L) {
                        onPointer(cursorX.roundToInt(), cursorY.roundToInt(), 4)
                        onPointer(cursorX.roundToInt(), cursorY.roundToInt(), 0)
                    } else {
                        onPointer(cursorX.roundToInt(), cursorY.roundToInt(), 0)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                draggingButton = false
                onPointer(cursorX.roundToInt(), cursorY.roundToInt(), 0)
            }
        }
        return true
    }
}
