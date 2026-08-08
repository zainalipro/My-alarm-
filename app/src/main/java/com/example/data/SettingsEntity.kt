package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val defaultDurationMinutes: Int = 15,
    val alarmSoundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val themeMode: String = "system", // "light", "dark", "system"
    val isPremium: Boolean = false,
    val isDndEnabled: Boolean = false, // Auto-trigger real Do Not Disturb
    val streakCommitMode: Boolean = false, // Disables emergency bypass hold
    val selectedCompletionSound: String = "default", // "default", "digital", "chimes", "forest", "gong"
    val userEmail: String? = null, // Store Google Login email if signed in
    val currentStreakDays: Int = 0,
    val lastCompletedDate: String? = null // e.g., "2026-08-08" to prevent double-incrementing streak on the same day
)
