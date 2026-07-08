package com.voicecontrol.app.wake

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WakeListenerService : Service() {

    companion object {
        const val CHANNEL_ID = "wake_listener_channel"
        const val NOTIFICATION_ID = 42

        @Volatile private var instance: WakeListenerService? = null

        fun resumeAfterCommand() {
            instance?.resumeWakeListening()
        }
    }

    private var engine: WakeWordEngine? = null
    private var isGreeting = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForegroundCompat()
        startWakeEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        engine?.release()
        engine = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun startWakeEngine() {
        isGreeting = false
        val models = listOf(WakeWordModel("Hello World", "hello_world.onnx", threshold = 0.5f))
        engine = WakeWordEngine(context = this, models = models, detectionMode = DetectionMode.SINGLE_BEST)
        scope.launch {
            try {
                engine?.detections?.collect {
                    onWakeDetected()
                }
            } catch (_: Exception) {}
        }
        engine?.start()
    }

    private fun onWakeDetected() {
        engine?.stop()
        isGreeting = true
        playBeep()
        WakeEventBus.emitWake()
    }

    private fun resumeWakeListening() {
        isGreeting = false
        scope.launch {
            delay(500L)
            if (!isGreeting) {
                engine?.start()
            }
        }
    }

    private fun playBeep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            mainHandler.postDelayed({ tone.release() }, 300)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Wake Word Listener", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Listening for wake word" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Max is listening")
            .setContentText("Say the wake word")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
