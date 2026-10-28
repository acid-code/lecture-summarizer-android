package il.ac.tau.lecturesummarizer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import il.ac.tau.lecturesummarizer.data.Repository
import il.ac.tau.lecturesummarizer.data.Settings

class LectureApp : Application() {
    lateinit var repo: Repository
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        repo = Repository(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPLOAD, "Uploads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_UPLOAD = "upload"
    }
}
