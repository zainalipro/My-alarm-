package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_alarms")
data class ScheduledAlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val hour: Int,
    val minute: Int,
    val durationMinutes: Int = 15,
    val isEnabled: Boolean = true,
    val useUtc: Boolean = false, // If true, matches UTC time, else local time
    val daysOfWeek: String = "1,2,3,4,5,6,7", // comma-separated days, 1=Sunday, 7=Saturday
    val label: String = "Focus Alarm"
)
