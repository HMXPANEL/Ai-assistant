package com.voicecontrol.app.wake

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that continuously listens for the "Hey Max" wake phrase
 * using a restart-loop SpeechRecognizer (hi-IN locale). On detection, it
 * stops itself, plays a beep, and hands off to ChatViewModel via
 * WakeEventBus — it does NOT run its own command-listening session, to avoid
 * two SpeechRecognizer instances competing for the microphone.
 */
class WakeListenerService : Service() {

    companion object {
        const val CHANNEL_ID = "wake_listener_channel"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.voicecontrol.app.wake.STOP"

        @Volatile private var instance: WakeListenerService? = null

        /** Call after the command flow (capture + response) fully finishes. */
        fun resumeAfterCommand() {
            instance?.resumeWakeListening()
        }
    }

    private var recognizer: SpeechRecognizer? = null
    private var isGreeting = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var restartJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForegroundCompat()
        startWakeLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        recognizer?.destroy()
        recognizer = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun startWakeLoop() {
        isGreeting = false
        startRecognitionSession()
    }

    private fun startRecognitionSession() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = matches?.firstOrNull() ?: ""
                    if (!isGreeting && WakePhraseMatcher.containsWakePhrase(transcript)) {
                        onWakeDetected()
                    } else {
                        restartIfIdle()
                    }
                }
                override fun onError(error: Int) { restartIfIdle() }
                override fun onEndOfSpeech() {}
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer?.startListening(intent)
    }

    private fun restartIfIdle() {
        if (isGreeting) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(300L)
            if (!isGreeting) startRecognitionSession()
        }
    }

    private fun onWakeDetected() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        isGreeting = true
        playBeep()
        WakeEventBus.emitWake()
        // Handoff ends here. ChatViewModel takes over: speaks the greeting via
        // the shared AgentTtsManager, then runs its existing command-capture
        // + processing pipeline, then calls WakeListenerService.resumeAfterCommand().
    }

    private fun resumeWakeListening() {
        isGreeting = false
        scope.launch {
            delay(500L)
            if (!isGreeting) startRecognitionSession()
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
            ).apply { description = "Listening for 'Hey Max'" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Max is listening")
            .setContentText("Say 'Hey Max' to wake me up")
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
