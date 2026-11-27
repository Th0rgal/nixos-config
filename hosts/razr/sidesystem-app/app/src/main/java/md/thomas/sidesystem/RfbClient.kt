package md.thomas.sidesystem

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Inflater

/**
 * Lightweight, zero-copy loopback RFB 3.8 client for SideSystem's embedded NixOS desktop.
 *
 * Connects to wayvnc on 127.0.0.1:<port> with TCP_NODELAY and 32-bit BGRA/ARGB_8888 pixel
 * format matching Android Bitmap.Config.ARGB_8888 1:1. Supports Raw, CopyRect, Zlib, and
 * DesktopSize (-223) / ExtendedDesktopSize (-308) pseudo-encodings for seamless rotation
 * and Razr fold/unfold resizing.
 */
class RfbClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5900,
    private val onFrame: (Bitmap, Int, Int) -> Unit,
    private val onState: (String, Boolean) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private val writeLock = Any()
    private var worker: Thread? = null

    @Volatile var fbWidth: Int = 0
        private set
    @Volatile var fbHeight: Int = 0
        private set

    private var bitmap: Bitmap? = null
    private var pixels: IntArray = IntArray(0)
    private var rawBuf: ByteArray = ByteArray(0)
    private var zlibOut: ByteArray = ByteArray(0)
    private var copyScratch: IntArray = IntArray(0)
    private val inflater = Inflater()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ runLoop() }, "SideSystem-RFB").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        synchronized(writeLock) { out = null }
        worker?.interrupt()
        worker = null
    }

    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val o = out ?: return
        val cx = x.coerceIn(0, (fbWidth - 1).coerceAtLeast(0))
        val cy = y.coerceIn(0, (fbHeight - 1).coerceAtLeast(0))
        synchronized(writeLock) {
            try {
                o.writeByte(5) // PointerEvent
                o.writeByte(buttonMask and 0xff)
                o.writeShort(cx)
                o.writeShort(cy)
                o.flush()
            } catch (_: Exception) {}
        }
    }

    fun sendKeyEvent(keysym: Int, down: Boolean) {
        val o = out ?: return
        synchronized(writeLock) {
            try {
                o.writeByte(4) // KeyEvent
                o.writeByte(if (down) 1 else 0)
                o.writeShort(0) // padding
                o.writeInt(keysym)
                o.flush()
            } catch (_: Exception) {}
        }
    }

    fun sendKeyTap(keysym: Int) {
        sendKeyEvent(keysym, true)
        sendKeyEvent(keysym, false)
    }

    fun requestFullRefresh() {
        val o = out ?: return
        val w = fbWidth
        val h = fbHeight
        if (w <= 0 || h <= 0) return
        synchronized(writeLock) {
            try {
                sendFbUpdateRequest(o, false, 0, 0, w, h)
                o.flush()
            } catch (_: Exception) {}
        }
    }

    private fun runLoop() {
        while (running.get()) {
            try {
                onState("Connecting to Hyprland…", false)
                val s = Socket()
                s.tcpNoDelay = true
                s.receiveBufferSize = 512 * 1024
                s.connect(InetSocketAddress(host, port), 3000)
                socket = s

                val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
                val outStream = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 16 * 1024))
                synchronized(writeLock) { out = outStream }

                // 1. ProtocolVersion (12 bytes)
                val ver = ByteArray(12)
                inp.readFully(ver)
                outStream.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
                outStream.flush()

                // 2. Security handshake (None = 1 on loopback)
                val numSec = inp.readUnsignedByte()
                if (numSec == 0) {
                    val errLen = inp.readInt()
                    val errBytes = ByteArray(errLen.coerceAtMost(512))
                    inp.readFully(errBytes)
                    throw IllegalStateException("RFB security error: ${String(errBytes)}")
                }
                val secTypes = ByteArray(numSec)
                inp.readFully(secTypes)
                if (!secTypes.contains(1.toByte())) {
                    throw IllegalStateException("RFB server requires unsupported auth")
                }
                outStream.writeByte(1) // SecurityType = None
                outStream.flush()
                val secResult = inp.readInt()
                if (secResult != 0) throw IllegalStateException("RFB auth failed ($secResult)")

                // 3. ClientInit (shared = 1)
                outStream.writeByte(1)
                outStream.flush()

                // 4. ServerInit
                val initW = inp.readUnsignedShort()
                val initH = inp.readUnsignedShort()
                val serverPf = ByteArray(16)
                inp.readFully(serverPf)
                val nameLen = inp.readInt()
                if (nameLen > 0) {
                    val nameBytes = ByteArray(nameLen.coerceAtMost(256))
                    inp.readFully(nameBytes)
                    if (nameLen > nameBytes.size) inp.skipBytes(nameLen - nameBytes.size)
                }

                allocateFramebuffer(initW, initH)
                inflater.reset()

                // 5. SetPixelFormat: 32bpp LE, R=16, G=8, B=0 (native Android ARGB_8888)
                synchronized(writeLock) {
                    outStream.writeByte(0) // SetPixelFormat
                    outStream.writeByte(0); outStream.writeByte(0); outStream.writeByte(0)
                    outStream.writeByte(32) // bpp
                    outStream.writeByte(24) // depth
                    outStream.writeByte(0)  // bigEndian = false (LE)
                    outStream.writeByte(1)  // trueColor = true
                    outStream.writeShort(255) // redMax
                    outStream.writeShort(255) // greenMax
                    outStream.writeShort(255) // blueMax
                    outStream.writeByte(16) // redShift
                    outStream.writeByte(8)  // greenShift
                    outStream.writeByte(0)  // blueShift
                    outStream.writeByte(0); outStream.writeByte(0); outStream.writeByte(0)

                    // 6. SetEncodings: CopyRect(1), Raw(0), DesktopSize(-223), ExtendedDesktopSize(-308), Zlib(6)
                    // On 127.0.0.1 loopback, Raw + CopyRect avoids CPU deflate/inflate overhead completely!
                    val encs = intArrayOf(1, 0, -223, -308, 6)
                    outStream.writeByte(2) // SetEncodings
                    outStream.writeByte(0)
                    outStream.writeShort(encs.size)
                    for (e in encs) outStream.writeInt(e)

                    // Initial full framebuffer request
                    sendFbUpdateRequest(outStream, false, 0, 0, fbWidth, fbHeight)
                    outStream.flush()
                }

                onState("Connected (${fbWidth}×${fbHeight})", true)

                // 7. Main message loop
                while (running.get()) {
                    val msgType = inp.readUnsignedByte()
                    when (msgType) {
                        0 -> { // FramebufferUpdate
                            inp.readUnsignedByte() // padding
                            val numRects = inp.readUnsignedShort()
                            var resized = false
                            for (i in 0 until numRects) {
                                val rx = inp.readUnsignedShort()
                                val ry = inp.readUnsignedShort()
                                val rw = inp.readUnsignedShort()
                                val rh = inp.readUnsignedShort()
                                val enc = inp.readInt()
                                when (enc) {
                                    0 -> readRawRect(inp, rx, ry, rw, rh)
                                    1 -> readCopyRect(inp, rx, ry, rw, rh)
                                    6 -> readZlibRect(inp, rx, ry, rw, rh)
                                    -223 -> { // DesktopSize
                                        allocateFramebuffer(rw, rh)
                                        resized = true
                                        onState("Connected (${fbWidth}×${fbHeight})", true)
                                    }
                                    -308 -> { // ExtendedDesktopSize
                                        val numScreens = inp.readUnsignedByte()
                                        inp.skipBytes(3)
                                        inp.skipBytes(numScreens * 16)
                                        allocateFramebuffer(rw, rh)
                                        resized = true
                                        onState("Connected (${fbWidth}×${fbHeight})", true)
                                    }
                                    else -> throw IllegalStateException("Unsupported RFB encoding: $enc")
                                }
                            }
                            val bmp = bitmap
                            if (bmp != null && fbWidth > 0 && fbHeight > 0) {
                                bmp.setPixels(pixels, 0, fbWidth, 0, 0, fbWidth, fbHeight)
                                onFrame(bmp, fbWidth, fbHeight)
                            }
                            synchronized(writeLock) {
                                sendFbUpdateRequest(outStream, !resized, 0, 0, fbWidth, fbHeight)
                                outStream.flush()
                            }
                        }
                        1 -> { // SetColorMapEntries
                            inp.skipBytes(3)
                            val count = inp.readUnsignedShort()
                            inp.skipBytes(count * 6)
                        }
                        2 -> { /* Bell */ }
                        3 -> { // ServerCutText
                            inp.skipBytes(3)
                            val len = inp.readInt()
                            if (len > 0) inp.skipBytes(len)
                        }
                        else -> throw IllegalStateException("Unknown RFB message type: $msgType")
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    onState("Reconnecting… (${e.message ?: "disconnected"})", false)
                    try { socket?.close() } catch (_: Exception) {}
                    try { Thread.sleep(600) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    private fun allocateFramebuffer(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == fbWidth && h == fbHeight && bitmap != null) return
        fbWidth = w
        fbHeight = h
        pixels = IntArray(w * h)
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    private fun ensureRawBuf(bytes: Int) {
        if (rawBuf.size < bytes) rawBuf = ByteArray(bytes)
    }

    private fun readRawRect(inp: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val totalBytes = w * h * 4
        ensureRawBuf(totalBytes)
        inp.readFully(rawBuf, 0, totalBytes)
        blitBgraBytes(rawBuf, x, y, w, h)
    }

    private fun readZlibRect(inp: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val compLen = inp.readInt()
        ensureRawBuf(compLen)
        inp.readFully(rawBuf, 0, compLen)
        val expected = w * h * 4
        if (zlibOut.size < expected) zlibOut = ByteArray(expected)
        inflater.setInput(rawBuf, 0, compLen)
        var off = 0
        while (off < expected) {
            val n = inflater.inflate(zlibOut, off, expected - off)
            if (n <= 0) break
            off += n
        }
        blitBgraBytes(zlibOut, x, y, w, h)
    }

    private fun blitBgraBytes(src: ByteArray, x: Int, y: Int, w: Int, h: Int) {
        val stride = fbWidth
        val fbH = fbHeight
        var s = 0
        for (row in 0 until h) {
            val dy = y + row
            if (dy >= fbH) break
            var d = dy * stride + x
            val maxCols = (stride - x).coerceAtMost(w)
            for (col in 0 until maxCols) {
                val b = src[s].toInt() and 0xff
                val g = src[s + 1].toInt() and 0xff
                val r = src[s + 2].toInt() and 0xff
                pixels[d++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                s += 4
            }
            if (maxCols < w) s += (w - maxCols) * 4
        }
    }

    private fun readCopyRect(inp: DataInputStream, dstX: Int, dstY: Int, w: Int, h: Int) {
        val srcX = inp.readUnsignedShort()
        val srcY = inp.readUnsignedShort()
        val stride = fbWidth
        val needed = w * h
        if (copyScratch.size < needed) copyScratch = IntArray(needed)
        for (row in 0 until h) {
            val sy = srcY + row
            if (sy >= fbHeight) break
            System.arraycopy(pixels, sy * stride + srcX, copyScratch, row * w, w.coerceAtMost(stride - srcX))
        }
        for (row in 0 until h) {
            val dy = dstY + row
            if (dy >= fbHeight) break
            System.arraycopy(copyScratch, row * w, pixels, dy * stride + dstX, w.coerceAtMost(stride - dstX))
        }
    }

    private fun sendFbUpdateRequest(
        o: DataOutputStream,
        incremental: Boolean,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        o.writeByte(3)
        o.writeByte(if (incremental) 1 else 0)
        o.writeShort(x)
        o.writeShort(y)
        o.writeShort(w)
        o.writeShort(h)
    }
}
