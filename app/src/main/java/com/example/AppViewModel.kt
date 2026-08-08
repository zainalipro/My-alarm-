package com.example

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BlockedAppEntity
import com.example.data.SessionEntity
import com.example.data.SettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LaunchableAppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as PhoneStopAlarmApp).repository
    private val context = application.applicationContext

    // App state flows
    private val _installedApps = MutableStateFlow<List<LaunchableAppInfo>>(emptyList())
    val installedApps: StateFlow<List<LaunchableAppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Database updates combined into StateFlows
    val selectedAppsFromDb: StateFlow<List<BlockedAppEntity>> = repository.allSelectedApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val latestSession: StateFlow<SessionEntity?> = repository.latestSession
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val settings: StateFlow<SettingsEntity> = repository.settings
        .map { it ?: SettingsEntity() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsEntity())

    // UI state for picker
    private val _recentApps = MutableStateFlow<List<BlockedAppEntity>>(emptyList())
    val recentApps: StateFlow<List<BlockedAppEntity>> = _recentApps.asStateFlow()

    val allScheduledAlarms: StateFlow<List<com.example.data.ScheduledAlarmEntity>> = repository.allScheduledAlarms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadInstalledApps()
        viewModelScope.launch {
            SessionManager.refreshState(context)
            // Load recently selected
            selectedAppsFromDb.collect { selected ->
                _recentApps.value = selected.sortedByDescending { it.lastSelectedTime }.take(5)
            }
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            val apps = withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
                    resolveInfos.map { resolveInfo ->
                        val label = resolveInfo.loadLabel(pm).toString()
                        val packageName = resolveInfo.activityInfo.packageName
                        val isSystem = (resolveInfo.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        LaunchableAppInfo(packageName, label, isSystem)
                    }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            _installedApps.value = apps
            _isLoadingApps.value = false
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // Block / Allow Actions
    fun toggleAppBlock(packageName: String, label: String, shouldBlock: Boolean) {
        viewModelScope.launch {
            if (shouldBlock) {
                // If it is currently on ALLOWED list, remove or override it
                repository.addSelectedApp(
                    BlockedAppEntity(packageName, label, isBlocked = true, lastSelectedTime = System.currentTimeMillis())
                )
            } else {
                repository.removeSelectedAppByPackage(packageName)
            }
            SessionManager.refreshState(context)
        }
    }

    fun toggleAppAllow(packageName: String, label: String, shouldAllow: Boolean) {
        viewModelScope.launch {
            if (shouldAllow) {
                repository.addSelectedApp(
                    BlockedAppEntity(packageName, label, isBlocked = false, lastSelectedTime = System.currentTimeMillis())
                )
            } else {
                repository.removeSelectedAppByPackage(packageName)
            }
            SessionManager.refreshState(context)
        }
    }

    fun selectAllApps(isBlocked: Boolean, appsToSelect: List<LaunchableAppInfo>) {
        viewModelScope.launch {
            val entities = appsToSelect.map { app ->
                BlockedAppEntity(app.packageName, app.label, isBlocked = isBlocked, lastSelectedTime = System.currentTimeMillis())
            }
            repository.addSelectedApps(entities)
            SessionManager.refreshState(context)
        }
    }

    fun clearApps(isBlocked: Boolean) {
        viewModelScope.launch {
            repository.clearSelectedApps(isBlocked)
            SessionManager.refreshState(context)
        }
    }

    // Session Management
    fun startLockSession(durationMinutes: Int) {
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val endTime = startTime + (durationMinutes * 60 * 1000)
            
            val session = SessionEntity(
                startTime = startTime,
                endTime = endTime,
                durationMinutes = durationMinutes,
                isActive = true,
                isCompleted = false
            )
            
            val sessionId = repository.startSession(session).toInt()
            SessionManager.refreshState(context)
            
            // Set up precise AlarmManager backup trigger
            scheduleAlarmSessionEnd(endTime)

            // Start foreground countdown service
            val serviceIntent = Intent(context, AppBlockerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    fun forceEndSession() {
        viewModelScope.launch {
            val activeId = SessionManager.activeSessionId.value
            if (activeId != null) {
                repository.endSession(activeId, isCompleted = false)
            }
            
            // Reset focus streak if they force end the session!
            val currentSettings = repository.getSettingsSync() ?: SettingsEntity()
            repository.saveSettings(currentSettings.copy(currentStreakDays = 0))
            
            SessionManager.refreshState(context)
            
            // Cancel exact alarm
            cancelAlarmSessionEnd()

            // Stop service
            context.stopService(Intent(context, AppBlockerService::class.java))
            AppBlockerService.stopRingtone()
        }
    }

    private fun scheduleAlarmSessionEnd(triggerTime: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, BlockReceiver::class.java).apply {
            action = "com.example.ACTION_SESSION_END"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 2001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
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

    private fun cancelAlarmSessionEnd() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, BlockReceiver::class.java).apply {
            action = "com.example.ACTION_SESSION_END"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 2001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    // Settings actions
    fun updateSettings(
        defaultDurationMinutes: Int? = null,
        alarmSoundEnabled: Boolean? = null,
        vibrationEnabled: Boolean? = null,
        notificationsEnabled: Boolean? = null,
        themeMode: String? = null,
        isPremium: Boolean? = null,
        isDndEnabled: Boolean? = null,
        streakCommitMode: Boolean? = null,
        selectedCompletionSound: String? = null,
        userEmail: String? = null,
        currentStreakDays: Int? = null,
        lastCompletedDate: String? = null
    ) {
        viewModelScope.launch {
            val current = repository.getSettingsSync() ?: SettingsEntity()
            val updated = current.copy(
                defaultDurationMinutes = defaultDurationMinutes ?: current.defaultDurationMinutes,
                alarmSoundEnabled = alarmSoundEnabled ?: current.alarmSoundEnabled,
                vibrationEnabled = vibrationEnabled ?: current.vibrationEnabled,
                notificationsEnabled = notificationsEnabled ?: current.notificationsEnabled,
                themeMode = themeMode ?: current.themeMode,
                isPremium = isPremium ?: current.isPremium,
                isDndEnabled = isDndEnabled ?: current.isDndEnabled,
                streakCommitMode = streakCommitMode ?: current.streakCommitMode,
                selectedCompletionSound = selectedCompletionSound ?: current.selectedCompletionSound,
                userEmail = if (userEmail == "LOGOUT_TOKEN") null else (userEmail ?: current.userEmail),
                currentStreakDays = currentStreakDays ?: current.currentStreakDays,
                lastCompletedDate = lastCompletedDate ?: current.lastCompletedDate
            )
            repository.saveSettings(updated)
        }
    }

    fun resetAppSettings() {
        viewModelScope.launch {
            // Cancel all active scheduled alarms
            val alarms = repository.getEnabledScheduledAlarms()
            for (alarm in alarms) {
                cancelSystemAlarm(alarm)
            }
            repository.saveSettings(SettingsEntity())
            repository.clearAllSelectedApps()
            forceEndSession()
            SessionManager.refreshState(context)
        }
    }

    // Scheduled Alarms Actions
    fun addScheduledAlarm(hour: Int, minute: Int, durationMinutes: Int, useUtc: Boolean) {
        viewModelScope.launch {
            val alarm = com.example.data.ScheduledAlarmEntity(
                hour = hour,
                minute = minute,
                durationMinutes = durationMinutes,
                useUtc = useUtc,
                isEnabled = true
            )
            val newId = repository.addScheduledAlarm(alarm).toInt()
            scheduleSystemAlarm(alarm.copy(id = newId))
        }
    }

    fun toggleScheduledAlarm(alarm: com.example.data.ScheduledAlarmEntity, isEnabled: Boolean) {
        viewModelScope.launch {
            val updated = alarm.copy(isEnabled = isEnabled)
            repository.addScheduledAlarm(updated)
            if (isEnabled) {
                scheduleSystemAlarm(updated)
            } else {
                cancelSystemAlarm(updated)
            }
        }
    }

    fun deleteScheduledAlarm(alarm: com.example.data.ScheduledAlarmEntity) {
        viewModelScope.launch {
            repository.removeScheduledAlarm(alarm)
            cancelSystemAlarm(alarm)
        }
    }

    private fun scheduleSystemAlarm(alarm: com.example.data.ScheduledAlarmEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val calendar = java.util.Calendar.getInstance()
        if (alarm.useUtc) {
            calendar.timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        calendar.set(java.util.Calendar.HOUR_OF_DAY, alarm.hour)
        calendar.set(java.util.Calendar.MINUTE, alarm.minute)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
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
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }
            Log.d("AppViewModel", "Scheduled system alarm: id=${alarm.id}, time=${calendar.time}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelSystemAlarm(alarm: com.example.data.ScheduledAlarmEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, BlockReceiver::class.java).apply {
            action = "com.example.ACTION_START_SCHEDULED_LOCK"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 3000 + alarm.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d("AppViewModel", "Canceled system alarm: id=${alarm.id}")
    }

    // Google Sign In / Sync Simulated Operations
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    fun triggerBackupSync() {
        viewModelScope.launch {
            _isSyncing.value = true
            delay(2000) // Simulate cloud sync network request delay
            _isSyncing.value = false
        }
    }

    // Checking Permissions helper
    fun hasUsageAccessPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return true
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    fun hasOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(context)
    }

    fun hasAccessibilityPermission(): Boolean {
        val expectedComponentName = android.content.ComponentName(context, AppBlockerAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }
}
