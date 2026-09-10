package com.devassistant.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.devassistant.app.ai.AgentOutcome
import com.devassistant.app.ai.AiAgent
import com.devassistant.app.data.ActivityLogStore
import com.devassistant.app.data.SettingsStore
import com.devassistant.app.voice.SpeechRecognizerManager
import com.devassistant.app.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "dev_wake_word"
private const val NOTIFICATION_ID = 1
private const val WAKE_PHRASE = "hey dev"

/**
 * Foreground service implementing: wake word -> command capture -> AI agent loop -> spoken
 * response, then returns to listening for the wake word again.
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var recognizer: SpeechRecognizerManager
    private lateinit var tts: TextToSpeechManager
    private lateinit var settings: SettingsStore
    private var listeningForWakeWord = false

    override fun onCreate() {
        super.onCreate()
        recognizer = SpeechRecognizerManager(this)
        tts = TextToSpeechManager(this)
        settings = SettingsStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Listening for \"Hey DEV\"…"))
        listenForWakeWord()
        return START_STICKY
    }

    private fun listenForWakeWord() {
        if (listeningForWakeWord) return
        listeningForWakeWord = true
        updateNotification("Listening for \"Hey DEV\"…")

        recognizer.startListening(
            onPartial = { partial ->
                if (partial.lowercase().contains(WAKE_PHRASE)) {
                    listeningForWakeWord = false
                    recognizer.stop()
                    onWakeWordDetected()
                }
            },
            onFinal = { finalText ->
                if (finalText.lowercase().contains(WAKE_PHRASE)) {
                    listeningForWakeWord = false
                    onWakeWordDetected()
                } else {
                    listeningForWakeWord = false
                    listenForWakeWord() // restart the loop
                }
            },
            onError = {
                listeningForWakeWord = false
                listenForWakeWord() // restart on error/timeout
            }
        )
    }

    private fun onWakeWordDetected() {
        updateNotification("Listening for your command…")
        tts.speak("Yes?")
        recognizer.startListening(
            onFinal = { command -> handleCommand(command) },
            onError = { listenForWakeWord() }
        )
    }

    private fun handleCommand(command: String) {
        if (command.isBlank()) {
            listenForWakeWord()
            return
        }
        updateNotification("Working on: $command")
        val logEntry = ActivityLogStore.start(command)

        scope.launch {
            val apiKey = settings.apiKey.first()
            if (apiKey.isBlank()) {
                tts.speak("Please set your Anthropic API key in DEV settings first.")
                listenForWakeWord()
                return@launch
            }
            val agent = AiAgent(this@WakeWordService, apiKey)
            agent.run(command, logEntry) { outcome ->
                when (outcome) {
                    is AgentOutcome.Speak -> tts.speak(outcome.text)
                    is AgentOutcome.Failed -> tts.speak("I couldn't finish that. ${outcome.reason}")
                    is AgentOutcome.NeedsUser -> tts.speak(outcome.question)
                    is AgentOutcome.NeedsConfirmation -> tts.speak(outcome.description)
                }
                updateNotification("Listening for \"Hey DEV\"…")
                listenForWakeWord()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.stop()
        tts.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "DEV voice assistant", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DEV")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
