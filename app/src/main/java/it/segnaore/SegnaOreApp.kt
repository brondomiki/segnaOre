package it.segnaore

import android.app.Application

class SegnaOreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HoursRepository.init(this)
        Notifications.createChannel(this)
    }
}
