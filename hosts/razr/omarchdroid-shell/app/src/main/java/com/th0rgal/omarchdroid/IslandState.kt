package com.th0rgal.omarchdroid

import android.app.PendingIntent
import android.media.session.MediaController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class IslandMedia(
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val packageName: String,
    val controller: MediaController?,
)

data class IslandNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val contentIntent: PendingIntent?,
    val timestampMs: Long,
)

data class IslandSnapshot(
    val overlayRunning: Boolean = false,
    val listenerConnected: Boolean = false,
    val scribeActive: Boolean = false,
    val scribeLabel: String = "",
    val sideSystemActive: Boolean = false,
    val sideSystemLabel: String = "",
    val media: IslandMedia? = null,
    val latestNotification: IslandNotification? = null,
)

object IslandState {
    const val PKG_SCRIBE = "com.th0rgal.orbvoice"
    const val PKG_SIDESYSTEM = "com.th0rgal.sidesystem"
    const val PKG_KVAESITSO = "de.mm20.launcher2.release"

    private val _snapshot = MutableStateFlow(IslandSnapshot())
    val snapshot: StateFlow<IslandSnapshot> = _snapshot.asStateFlow()

    fun setOverlayRunning(running: Boolean) {
        _snapshot.update { it.copy(overlayRunning = running) }
    }

    fun setListenerConnected(connected: Boolean) {
        _snapshot.update { it.copy(listenerConnected = connected) }
    }

    fun updateAppStatuses(
        scribeActive: Boolean,
        scribeLabel: String,
        sideSystemActive: Boolean,
        sideSystemLabel: String,
    ) {
        _snapshot.update {
            it.copy(
                scribeActive = scribeActive,
                scribeLabel = scribeLabel,
                sideSystemActive = sideSystemActive,
                sideSystemLabel = sideSystemLabel,
            )
        }
    }

    fun updateMedia(media: IslandMedia?) {
        _snapshot.update { it.copy(media = media) }
    }

    fun updateLatestNotification(notification: IslandNotification?) {
        _snapshot.update { it.copy(latestNotification = notification) }
    }

    fun clearNotification() {
        _snapshot.update { it.copy(latestNotification = null) }
    }
}
