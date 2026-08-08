package com.example

import android.content.Context
import com.example.data.BlockedAppEntity
import com.example.data.SessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionManager {
    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _endTime = MutableStateFlow(0L)
    val endTime: StateFlow<Long> = _endTime.asStateFlow()

    private val _startTime = MutableStateFlow(0L)
    val startTime: StateFlow<Long> = _startTime.asStateFlow()

    private val _durationMinutes = MutableStateFlow(0)
    val durationMinutes: StateFlow<Int> = _durationMinutes.asStateFlow()

    private val _blockedPackages = MutableStateFlow<Set<String>>(emptySet())
    val blockedPackages: StateFlow<Set<String>> = _blockedPackages.asStateFlow()

    private val _allowedPackages = MutableStateFlow<Set<String>>(emptySet())
    val allowedPackages: StateFlow<Set<String>> = _allowedPackages.asStateFlow()

    private val _activeSessionId = MutableStateFlow<Int?>(null)
    val activeSessionId: StateFlow<Int?> = _activeSessionId.asStateFlow()

    suspend fun refreshState(context: Context) {
        val repository = PhoneStopAlarmApp.instance.repository
        val latest = repository.getLatestSessionSync()
        
        if (latest != null && latest.isActive && latest.endTime > System.currentTimeMillis()) {
            _isSessionActive.value = true
            _endTime.value = latest.endTime
            _startTime.value = latest.startTime
            _durationMinutes.value = latest.durationMinutes
            _activeSessionId.value = latest.id

            val blocked = repository.getBlockedAppsList().map { it.packageName }.toSet()
            val allowed = repository.getAllowedAppsList().map { it.packageName }.toSet()
            _blockedPackages.value = blocked
            _allowedPackages.value = allowed
        } else {
            if (latest != null && latest.isActive) {
                // Session expired while app was dead
                repository.endSession(latest.id, isCompleted = true)
            }
            _isSessionActive.value = false
            _endTime.value = 0L
            _startTime.value = 0L
            _durationMinutes.value = 0
            _activeSessionId.value = null
            _blockedPackages.value = emptySet()
            _allowedPackages.value = emptySet()
        }
    }

    fun isAppBlocked(packageName: String): Boolean {
        if (isEssentialSystemPackage(packageName)) return false
        if (packageName == PhoneStopAlarmApp.instance.packageName) return false
        if (_allowedPackages.value.contains(packageName)) return false
        return _isSessionActive.value && _blockedPackages.value.contains(packageName)
    }

    private fun isEssentialSystemPackage(packageName: String): Boolean {
        return when (packageName) {
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.providers.telephony",
            "com.android.server.telecom",
            "com.android.emergency" -> true
            else -> false
        }
    }
}
