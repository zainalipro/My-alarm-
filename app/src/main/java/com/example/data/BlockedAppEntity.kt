package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isBlocked: Boolean, // true = BLOCKED list, false = ALLOWED list
    val lastSelectedTime: Long = System.currentTimeMillis()
)
