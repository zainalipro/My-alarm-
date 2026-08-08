package com.example.data

import kotlinx.coroutines.flow.Flow

class AppRepository(private val appDao: AppDao) {
    val allSelectedApps: Flow<List<BlockedAppEntity>> = appDao.getAllSelectedAppsFlow()
    val latestSession: Flow<SessionEntity?> = appDao.getLatestSessionFlow()
    val settings: Flow<SettingsEntity?> = appDao.getSettingsFlow()

    suspend fun getBlockedAppsList(): List<BlockedAppEntity> = appDao.getBlockedAppsList()
    suspend fun getAllowedAppsList(): List<BlockedAppEntity> = appDao.getAllowedAppsList()

    suspend fun addSelectedApp(app: BlockedAppEntity) = appDao.insertSelectedApp(app)
    suspend fun addSelectedApps(apps: List<BlockedAppEntity>) = appDao.insertSelectedApps(apps)
    suspend fun removeSelectedApp(app: BlockedAppEntity) = appDao.deleteSelectedApp(app)
    suspend fun removeSelectedAppByPackage(packageName: String) = appDao.deleteSelectedAppByPackage(packageName)
    suspend fun clearSelectedApps(isBlocked: Boolean) = appDao.clearSelectedApps(isBlocked)
    suspend fun clearAllSelectedApps() = appDao.clearAllSelectedApps()

    suspend fun getLatestSessionSync(): SessionEntity? = appDao.getLatestSession()
    suspend fun startSession(session: SessionEntity): Long = appDao.insertSession(session)
    suspend fun endSession(id: Int, isCompleted: Boolean) = appDao.endSession(id, isCompleted)

    suspend fun getSettingsSync(): SettingsEntity? = appDao.getSettings()
    suspend fun saveSettings(settings: SettingsEntity) = appDao.insertSettings(settings)

    // Scheduled Alarms
    val allScheduledAlarms: Flow<List<ScheduledAlarmEntity>> = appDao.getAllScheduledAlarmsFlow()
    suspend fun getEnabledScheduledAlarms(): List<ScheduledAlarmEntity> = appDao.getEnabledScheduledAlarms()
    suspend fun addScheduledAlarm(alarm: ScheduledAlarmEntity): Long = appDao.insertScheduledAlarm(alarm)
    suspend fun removeScheduledAlarm(alarm: ScheduledAlarmEntity) = appDao.deleteScheduledAlarm(alarm)
    suspend fun removeScheduledAlarmById(id: Int) = appDao.deleteScheduledAlarmById(id)
}
