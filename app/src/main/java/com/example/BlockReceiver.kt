package com.example

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class BlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d("BlockReceiver", "Received broadcast: $action")

        val goAsync = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val repository = PhoneStopAlarmApp.instance.repository
                
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> {
                        SessionManager.refreshState(context)
                        if (SessionManager.isSessionActive.value) {
                            Log.d("BlockReceiver", "Session active post-reboot. Launching AppBlockerService.")
                            startBlockerService(context)
                        }
                        // Reschedule all active alarms after reboot
                        rescheduleAllAlarms(context)
                    }
                    "com.example.ACTION_START_SCHEDULED_LOCK" -> {
                        val durationMinutes = intent.getIntExtra("DURATION_MINUTES", 15)
                        val alarmId = intent.getIntExtra("ALARM_ID", -1)
                        Log.d("BlockReceiver", "Triggered Scheduled Lock: alarmId=$alarmId, duration=$durationMinutes min")
                        
                        // Check if session is already active
                        SessionManager.refreshState(context)
                        if (!SessionManager.isSessionActive.value) {
                            val startTime = System.currentTimeMillis()
                            val endTime = startTime + (durationMinutes * 60 * 1000)
                            
                            val session = SessionEntity(
                                startTime = startTime,
                                endTime = endTime,
                                durationMinutes = durationMinutes,
                                isActive = true,
                                isCompleted = false
                            )
                            repository.startSession(session)
                            SessionManager.refreshState(context)
                            
                            // Schedule Session End Alarm
                            scheduleAlarmSessionEnd(context, endTime)
                            
                            // Start Service
                            startBlockerService(context)
                        }
                        
                        // Reschedule this alarm for tomorrow if enabled
                        rescheduleSpecificAlarm(context, alarmId)
                    }
                    "com.example.ACTION_SESSION_END" -> {
                        Log.d("BlockReceiver", "Session end action triggered.")
                        startBlockerService(context)
                    }
                }
            } catch (e: Exception) {
                Log.e("BlockReceiver", "Error processing broadcast: ${e.message}")
            } finally {
                goAsync.finish()
            }
        }
    }

    private fun startBlockerService(context: Context) {
        val serviceIntent = Intent(context, AppBlockerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun scheduleAlarmSessionEnd(context: Context, triggerTime: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, BlockReceiver::class.java).apply {
            action = "com.example.ACTION_SESSION_END"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 2001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        scheduleAlarmCompat(context, alarmManager, triggerTime, pendingIntent)
    }

    private fun scheduleAlarmCompat(context: Context, alarmManager: AlarmManager, triggerTime: Long, pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: SecurityException) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        } catch (e: Exception) {
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private suspend fun rescheduleAllAlarms(context: Context) {
        try {
            val repository = PhoneStopAlarmApp.instance.repository
            val enabledAlarms = repository.getEnabledScheduledAlarms()
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            
            for (alarm in enabledAlarms) {
                val calendar = Calendar.getInstance()
                if (alarm.useUtc) {
                    calendar.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                calendar.set(Calendar.HOUR_OF_DAY, alarm.hour)
                calendar.set(Calendar.MINUTE, alarm.minute)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                
                if (calendar.timeInMillis <= System.currentTimeMillis()) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
                
                val intent = Intent(context, BlockReceiver::class.java).apply {
                    action = "com.example.ACTION_START_SCHEDULED_LOCK"
                    putExtra("DURATION_MINUTES", alarm.durationMinutes)
                    putExtra("ALARM_ID", alarm.id)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 3000 + alarm.id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                
                scheduleAlarmCompat(context, alarmManager, calendar.timeInMillis, pendingIntent)
            }
        } catch (e: Exception) {
            Log.e("BlockReceiver", "Error rescheduling: ${e.message}")
        }
    }

    private suspend fun rescheduleSpecificAlarm(context: Context, alarmId: Int) {
        if (alarmId == -1) return
        try {
            val repository = PhoneStopAlarmApp.instance.repository
            val alarms = repository.getEnabledScheduledAlarms()
            val alarm = alarms.find { it.id == alarmId } ?: return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            
            val calendar = Calendar.getInstance()
            if (alarm.useUtc) {
                calendar.timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            calendar.set(Calendar.HOUR_OF_DAY, alarm.hour)
            calendar.set(Calendar.MINUTE, alarm.minute)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            
            // Set for tomorrow since this alarm just fired today
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            
            val intent = Intent(context, BlockReceiver::class.java).apply {
                action = "com.example.ACTION_START_SCHEDULED_LOCK"
                putExtra("DURATION_MINUTES", alarm.durationMinutes)
                putExtra("ALARM_ID", alarm.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 3000 + alarm.id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            scheduleAlarmCompat(context, alarmManager, calendar.timeInMillis, pendingIntent)
            Log.d("BlockReceiver", "Rescheduled alarmId=$alarmId to fire tomorrow: ${calendar.time}")
        } catch (e: Exception) {
            Log.e("BlockReceiver", "Error rescheduling alarmId=$alarmId: ${e.message}")
        }
    }
}
