package md.thomas.sidesystem

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-Termux loopback PCM audio & microphone bridge for SideSystem NixOS containers.
 *
 * - 127.0.0.1:4714 (Playback): accepts 48 kHz 16-bit stereo signed-LE PCM from NixOS
 *   and plays directly through Android's low-latency AudioTrack.
 * - 127.0.0.1:4715 (Microphone): streams 16 kHz 16-bit mono signed-LE PCM from
 *   Android AudioRecord on demand (mic is only opened while a NixOS client is connected).
 */
class SideSystemAudioService : Service() {

    companion object {
        const val PLAYBACK_PORT = 4714
        const val MIC_PORT = 4715
        private const val CHANNEL_ID = "sidesystem_audio_bridge"
        private const val NOTIF_ID = 4714

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, SideSystemAudioService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SideSystemAudioService::class.java))
        }
    }

    private val running = AtomicBoolean(false)
    private var playbackServer: ServerSocket? = null
    private var micServer: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            isRunning = true
            startForegroundCompat()
            Thread(::runPlaybackLoop, "sidesystem-audio-play").start()
            Thread(::runMicLoop, "sidesystem-audio-mic").start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        isRunning = false
        try { playbackServer?.close() } catch (_: Exception) {}
        try { micServer?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "SideSystem Audio Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Loopback speaker and microphone bridge for NixOS"
            }
            nm.createNotificationChannel(ch)
        }
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SideSystem Audio Bridge")
            .setContentText("Loopback speaker (:4714) & mic (:4715) active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= 34) {
            val types = if (hasMic) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            startForeground(NOTIF_ID, notif, types)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun runPlaybackLoop() {
        try {
            val srv = ServerSocket(PLAYBACK_PORT, 4, InetAddress.getByName("127.0.0.1"))
            playbackServer = srv
            while (running.get()) {
                val sock = try { srv.accept() } catch (_: Exception) { break }
                sock.use { client ->
                    playStream(client.getInputStream())
                }
            }
        } catch (_: Exception) {
        } finally {
            try { playbackServer?.close() } catch (_: Exception) {}
        }
    }

    private fun playStream(input: InputStream) {
        val sampleRate = 48000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val buf = ByteArray(minBuf)
        try {
            track.play()
            while (running.get()) {
                val n = input.read(buf)
                if (n <= 0) break
                track.write(buf, 0, n)
            }
        } catch (_: Exception) {
        } finally {
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
    }

    private fun runMicLoop() {
        try {
            val srv = ServerSocket(MIC_PORT, 4, InetAddress.getByName("127.0.0.1"))
            micServer = srv
            while (running.get()) {
                val sock = try { srv.accept() } catch (_: Exception) { break }
                sock.use { client ->
                    streamMic(client.getOutputStream())
                }
            }
        } catch (_: Exception) {
        } finally {
            try { micServer?.close() } catch (_: Exception) {}
        }
    }

    private fun streamMic(output: OutputStream) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return
        }
        val buf = ByteArray(minBuf)
        try {
            recorder.startRecording()
            while (running.get()) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) break
                output.write(buf, 0, n)
            }
        } catch (_: Exception) {
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
        }
    }
}
