package md.thomas.omarchdroid

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class IslandNotificationListener : NotificationListenerService() {

    private var mediaManager: MediaSessionManager? = null
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        syncMedia(controllers)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        IslandState.setListenerConnected(true)
        syncAllNotifications()
        setupMediaSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        IslandState.setListenerConnected(false)
        try {
            mediaManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Throwable) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        syncAllNotifications()
        if (sbn == null) return
        if (sbn.packageName == packageName || sbn.packageName == "android") return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val appLabel = try {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Throwable) {
            sbn.packageName.substringAfterLast('.')
        }

        IslandState.updateLatestNotification(
            IslandNotification(
                key = sbn.key,
                packageName = sbn.packageName,
                appLabel = appLabel,
                title = title,
                text = text,
                contentIntent = n.contentIntent,
                timestampMs = System.currentTimeMillis(),
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        syncAllNotifications()
        if (sbn != null && IslandState.snapshot.value.latestNotification?.key == sbn.key) {
            IslandState.clearNotification()
        }
    }

    private fun syncAllNotifications() {
        val active = try {
            activeNotifications ?: emptyArray()
        } catch (_: Throwable) {
            emptyArray()
        }

        var scribeActive = false
        var scribeLabel = ""
        var sideActive = false
        var sideLabel = ""

        for (sbn in active) {
            val extras = sbn.notification?.extras ?: continue
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            when (sbn.packageName) {
                IslandState.PKG_SCRIBE -> {
                    scribeActive = true
                    scribeLabel = text.ifEmpty { title.ifEmpty { "Recording" } }
                }
                IslandState.PKG_SIDESYSTEM -> {
                    sideActive = true
                    sideLabel = text.ifEmpty { title.ifEmpty { "Container live" } }
                }
            }
        }

        IslandState.updateAppStatuses(
            scribeActive = scribeActive,
            scribeLabel = scribeLabel,
            sideSystemActive = sideActive,
            sideSystemLabel = sideLabel,
        )
    }

    private fun setupMediaSessions() {
        try {
            val mm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            mediaManager = mm
            val component = ComponentName(this, IslandNotificationListener::class.java)
            mm.addOnActiveSessionsChangedListener(sessionsListener, component)
            syncMedia(mm.getActiveSessions(component))
        } catch (_: Throwable) {
        }
    }

    private fun syncMedia(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            IslandState.updateMedia(null)
            return
        }
        val active = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        if (active == null) {
            IslandState.updateMedia(null)
            return
        }

        val meta = active.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val playing = active.playbackState?.state == PlaybackState.STATE_PLAYING

        if (title.isEmpty() && !playing) {
            IslandState.updateMedia(null)
            return
        }

        IslandState.updateMedia(
            IslandMedia(
                title = title.ifEmpty { "Media" },
                artist = artist,
                isPlaying = playing,
                packageName = active.packageName,
                controller = active,
            )
        )
    }
}
