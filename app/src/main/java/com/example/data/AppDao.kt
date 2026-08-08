package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // App Selections
    @Query("SELECT * FROM blocked_apps ORDER BY lastSelectedTime DESC")
    fun getAllSelectedAppsFlow(): Flow<List<BlockedAppEntity>>

    @Query("SELECT * FROM blocked_apps WHERE isBlocked = 1")
    suspend fun getBlockedAppsList(): List<BlockedAppEntity>

    @Query("SELECT * FROM blocked_apps WHERE isBlocked = 0")
    suspend fun getAllowedAppsList(): List<BlockedAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSelectedApp(app: BlockedAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSelectedApps(apps: List<BlockedAppEntity>)

    @Delete
    suspend fun deleteSelectedApp(app: BlockedAppEntity)

    @Query("DELETE FROM blocked_apps WHERE packageName = :packageName")
    suspend fun deleteSelectedAppByPackage(packageName: String)

    @Query("DELETE FROM blocked_apps WHERE isBlocked = :isBlocked")
    suspend fun clearSelectedApps(isBlocked: Boolean)

    @Query("DELETE FROM blocked_apps")
    suspend fun clearAllSelectedApps()

    // Sessions
    @Query("SELECT * FROM lock_sessions ORDER BY id DESC LIMIT 1")
    fun getLatestSessionFlow(): Flow<SessionEntity?>

    @Query("SELECT * FROM lock_sessions ORDER BY id DESC LIMIT 1")
    suspend fun getLatestSession(): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity): Long

    @Query("UPDATE lock_sessions SET isActive = 0, isCompleted = :isCompleted WHERE id = :id")
    suspend fun endSession(id: Int, isCompleted: Boolean)

    // Settings
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<SettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettings(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: SettingsEntity)

    // Scheduled Alarms
    @Query("SELECT * FROM scheduled_alarms ORDER BY hour ASC, minute ASC")
    fun getAllScheduledAlarmsFlow(): Flow<List<ScheduledAlarmEntity>>

    @Query("SELECT * FROM scheduled_alarms WHERE isEnabled = 1")
    suspend fun getEnabledScheduledAlarms(): List<ScheduledAlarmEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScheduledAlarm(alarm: ScheduledAlarmEntity): Long

    @Delete
    suspend fun deleteScheduledAlarm(alarm: ScheduledAlarmEntity)

    @Query("DELETE FROM scheduled_alarms WHERE id = :id")
    suspend fun deleteScheduledAlarmById(id: Int)
}
