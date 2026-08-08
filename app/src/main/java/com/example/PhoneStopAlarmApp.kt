package com.example

import android.app.Application
import com.example.data.AppDatabase
import com.example.data.AppRepository

class PhoneStopAlarmApp : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { AppRepository(database.appDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: PhoneStopAlarmApp
            private set
    }
}
