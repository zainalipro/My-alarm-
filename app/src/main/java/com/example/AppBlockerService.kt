package com.example

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.AppRepository
import com.example.data.SettingsEntity
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AppBlockerService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var timerJob: Job? = null

    companion object {
        const val CHANNEL_ID = "phone_stop_alarm_channel"
        const val NOTIFICATION_ID = 1001
        
        var ringtone: Ringtone? = null
        var isRingtonePlaying = false

        fun stopRingtone() {
            try {
                if (ringtone?.isPlaying == true) {
                    ringtone?.stop()
                }
                isRingtonePlaying = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun playCompletionSound(context: Context, soundType: String) {
            stopRingtone()
            
            if (soundType == "default") {
                try {
                    val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ringtone = RingtoneManager.getRingtone(context.applicationContext, alarmUri)
                    ringtone?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            it.audioAttributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        }
                        it.play()
                        isRingtonePlaying = true
                    }
                } catch (e: Exception) {
                    Log.e("AppBlockerService", "Error playing default ringtone: ${e.message}")
                }
            } else {
                // Play custom synthesized sound via ToneGenerator (beautiful, lightweight, completely offline)
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                        isRingtonePlaying = true
                        when (soundType) {
                            "digital" -> {
                                // High-pitched double beep pattern
                                repeat(3) {
                                    if (!isRingtonePlaying) return@launch
                                    toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 250)
                                    delay(350)
                                    toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 250)
                                    delay(1200)
                                }
                            }
                            "chimes" -> {
                                // Gentle melodic arpeggio
                                repeat(2) {
                                    if (!isRingtonePlaying) return@launch
                                    toneGen.startTone(ToneGenerator.TONE_DTMF_1, 150)
                                    delay(250)
                                    toneGen.startTone(ToneGenerator.TONE_DTMF_5, 150)
                                    delay(250)
                                    toneGen.startTone(ToneGenerator.TONE_DTMF_9, 150)
                                    delay(1500)
                                }
                            }
                            "forest" -> {
                                // Soft warning pulse simulating bird call or soft chirp
                                repeat(4) {
                                    if (!isRingtonePlaying) return@launch
                                    toneGen.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 400)
                                    delay(1000)
                                }
                            }
                            "gong" -> {
                                // Long low resonant tone
                                if (isRingtonePlaying) {
                                    toneGen.startTone(ToneGenerator.TONE_SUP_DIAL, 1500)
                                    delay(1500)
                                }
                            }
                        }
                        isRingtonePlaying = false
                    } catch (e: Exception) {
                        Log.e("AppBlockerService", "Error playing custom synthesized sound: ${e.message}")
                        isRingtonePlaying = false
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AppBlockerService", "Service started.")
        
        serviceScope.launch {
            SessionManager.refreshState(this@AppBlockerService)
            
            val isSessionActive = SessionManager.isSessionActive.value
            val endTime = SessionManager.endTime.value
            
            if (isSessionActive && endTime > System.currentTimeMillis()) {
                val initialNotification = buildNotification("Lock Active", formatTimeLeft(endTime))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, initialNotification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, initialNotification)
                }
                
                // Read settings and trigger Do Not Disturb if enabled
                val repository = PhoneStopAlarmApp.instance.repository
                val settings = repository.getSettingsSync() ?: SettingsEntity()
                if (settings.isDndEnabled) {
                    enableDndMode(true)
                }
                
                startTimer(endTime)
            } else {
                stopSelf()
            }
        }
        
        return START_STICKY
    }

    private fun startTimer(endTime: Long) {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            var lastNotificationUpdateTime = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                val timeLeftMs = endTime - now
                
                if (timeLeftMs <= 0) {
                    handleSessionCompleted()
                    break
                }
                
                // Only perform work if the screen is interactive (otherwise sleep/idle)
                if (isScreenOn()) {
                    // Update notification at most once every 5 seconds to avoid system UI lag
                    if (now - lastNotificationUpdateTime >= 5000) {
                        val notification = buildNotification("Lock Active", formatTimeLeft(endTime))
                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.notify(NOTIFICATION_ID, notification)
                        lastNotificationUpdateTime = now
                    }
                    
                    // Only poll if Accessibility Service is NOT enabled (Accessibility is event-driven and zero lag)
                    if (!isAccessibilityEnabled()) {
                        checkForegroundAppAndIntercept()
                    }
                }
                
                delay(1000)
            }
        }
    }

    private fun checkForegroundAppAndIntercept() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager ?: return
            val now = System.currentTimeMillis()
            val stats = usageStatsManager.queryEvents(now - 3000, now)
            val event = android.app.usage.UsageEvents.Event()
            var foregroundPackage: String? = null
            
            while (stats.hasNextEvent()) {
                stats.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    foregroundPackage = event.packageName
                }
            }
            
            if (foregroundPackage != null && SessionManager.isAppBlocked(foregroundPackage)) {
                Log.d("AppBlockerService", "Polling detected blocked app: $foregroundPackage. Opening BlockActivity.")
                val blockIntent = Intent(this, com.example.ui.BlockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("BLOCKED_PACKAGE_NAME", foregroundPackage)
                }
                startActivity(blockIntent)
            }
        }
    }

    private suspend fun handleSessionCompleted() {
        Log.d("AppBlockerService", "Session completed! Triggering alarm.")
        val repository = PhoneStopAlarmApp.instance.repository
        
        val activeId = SessionManager.activeSessionId.value
        if (activeId != null) {
            repository.endSession(activeId, isCompleted = true)
        }
        
        // Save focus streak!
        incrementFocusStreak(repository)
        
        SessionManager.refreshState(this@AppBlockerService)
        
        val settings = repository.getSettingsSync() ?: SettingsEntity()
        
        // Restore Do Not Disturb mode if we turned it on
        if (settings.isDndEnabled) {
            enableDndMode(false)
        }
        
        showCompletionNotification()

        if (settings.alarmSoundEnabled) {
            playCompletionSound(this@AppBlockerService, settings.selectedCompletionSound)
        }
        
        if (settings.vibrationEnabled) {
            triggerVibration()
        }
        
        stopSelf()
    }

    private fun enableDndMode(enabled: Boolean) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (notificationManager.isNotificationPolicyAccessGranted) {
                    if (enabled) {
                        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                        Log.d("AppBlockerService", "DND Mode Activated.")
                    } else {
                        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                        Log.d("AppBlockerService", "DND Mode Deactivated.")
                    }
                } else {
                    Log.d("AppBlockerService", "Cannot toggle DND: Notification Policy Access NOT granted.")
                }
            }
        } catch (e: Exception) {
            Log.e("AppBlockerService", "Error toggling DND mode: ${e.message}")
        }
    }

    private suspend fun incrementFocusStreak(repository: AppRepository) {
        try {
            val settings = repository.getSettingsSync() ?: SettingsEntity()
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            
            if (settings.lastCompletedDate != todayStr) {
                val currentStreak = settings.currentStreakDays
                val newStreak = currentStreak + 1
                repository.saveSettings(settings.copy(
                    currentStreakDays = newStreak,
                    lastCompletedDate = todayStr
                ))
                Log.d("AppBlockerService", "Streak incremented from $currentStreak to $newStreak.")
            } else {
                Log.d("AppBlockerService", "Streak already updated for today.")
            }
        } catch (e: Exception) {
            Log.e("AppBlockerService", "Error saving streak: ${e.message}")
        }
    }

    private fun triggerVibration() {
        try {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 500, 200, 500, 200, 500), -1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showCompletionNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("SHOW_COMPLETION", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Phone Stop Alarm")
            .setContentText("Focus session completed successfully! 🎉")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(1002, notification)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun formatTimeLeft(endTime: Long): String {
        val diff = endTime - System.currentTimeMillis()
        if (diff <= 0) return "Completed"
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(diff) % 60
        return String.format("%02d:%02d remaining", minutes, seconds)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Active Session"
            val descriptionText = "Displays status of the active phone lock session."
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val expectedComponentName = android.content.ComponentName(this, AppBlockerAccessibilityService::class.java)
            val enabledServicesSetting = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabledServicesSetting.contains(expectedComponentName.flattenToString())
        } catch (e: Exception) {
            false
        }
    }

    private fun isScreenOn(): Boolean {
        return try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            powerManager?.isInteractive ?: true
        } catch (e: Exception) {
            true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        
        // Restore DND if needed using a non-cancelled scope
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val repository = PhoneStopAlarmApp.instance.repository
                val settings = repository.getSettingsSync() ?: SettingsEntity()
                if (settings.isDndEnabled) {
                    enableDndMode(false)
                }
            } catch (e: Exception) {
                // ignore
            } finally {
                serviceJob.cancel()
            }
        }
    }
}
