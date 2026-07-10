package com.voicecontrol.app.device

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import androidx.media.session.MediaControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object MusicController {
    private var controller: MediaControllerCompat? = null

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val activeSessions = msm.activeSessions
            if (activeSessions != null && activeSessions.isNotEmpty()) {
                controller = MediaControllerCompat(context, activeSessions[0].sessionToken)
            }
        }
    }

    fun playPause(context: Context): String {
        init(context)
        return try {
            controller?.transportControls?.playPause()
            "Music toggled."
        } catch (e: Exception) {
            "No active music session."
        }
    }

    fun play(context: Context): String {
        init(context)
        return try {
            controller?.transportControls?.play()
            "Music playing."
        } catch (e: Exception) {
            "No active music session."
        }
    }

    fun pause(context: Context): String {
        init(context)
        return try {
            controller?.transportControls?.pause()
            "Music paused."
        } catch (e: Exception) {
            "No active music session."
        }
    }

    fun next(context: Context): String {
        init(context)
        return try {
            controller?.transportControls?.skipToNext()
            "Next track."
        } catch (e: Exception) {
            "No active music session."
        }
    }

    fun previous(context: Context): String {
        init(context)
        return try {
            controller?.transportControls?.skipToPrevious()
            "Previous track."
        } catch (e: Exception) {
            "No active music session."
        }
    }

    fun getCurrentTrack(context: Context): String {
        init(context)
        return try {
            val meta = controller?.metadata
            val title = meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
            val artist = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"
            "Playing: $title — $artist"
        } catch (e: Exception) {
            "No track info."
        }
    }
}