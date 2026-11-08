package il.ac.tau.lecturesummarizer

import kotlinx.coroutines.launch
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
    lateinit var unread: il.ac.tau.lecturesummarizer.data.UnreadStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        repo = Repository(this)
        // Repair old calendar placeholders with source proof, asynchronously and only from selected calendars.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO).launchCalendarCleanup(this)
        unread = il.ac.tau.lecturesummarizer.data.UnreadStore(this)
        unread.retain(repo.lectures.value.map { it.id }.toSet())
        repo.onSummaryReady = unread::markUnread
        settings.deviceId // create the stable per-install id once
        il.ac.tau.lecturesummarizer.autosync.AutoSyncWorker.schedule(this)
        il.ac.tau.lecturesummarizer.data.ApiProvider.claimIds = { repo.courses.value.mapNotNull { it.remoteId } }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPLOAD, getString(il.ac.tau.lecturesummarizer.R.string.uploads_channel), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_JOBS, getString(il.ac.tau.lecturesummarizer.R.string.summary_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_UPLOAD = "upload"
        const val CHANNEL_JOBS = "jobs"
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCalendarCleanup(app:LectureApp) {
    launch {
            runCatching {
                if(il.ac.tau.lecturesummarizer.calendar.CalendarResolver.hasPermission(app)) {
                    val store=il.ac.tau.lecturesummarizer.calendar.CalendarRuleStore(app)
                    val events=il.ac.tau.lecturesummarizer.calendar.CalendarRuleProvider.read(app,store.sources()).events
                    il.ac.tau.lecturesummarizer.calendar.CalendarDraftCleanup.run(app,app.repo,il.ac.tau.lecturesummarizer.calendar.CalendarDraftCleanup.proofs(store.rules(),events))
                }
            }
    }
}
