package il.ac.tau.lecturesummarizer.recording

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.MainActivity
import il.ac.tau.lecturesummarizer.data.LectureState
import il.ac.tau.lecturesummarizer.data.Marker
import il.ac.tau.lecturesummarizer.data.Segment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class RecorderUiState(
    val lectureId: String? = null,
    val recording: Boolean = false,
    val elapsedMs: Long = 0,
    val segmentSeq: Int = 0,
    val markerCount: Int = 0,
    /** Service alive, current segment finalized, mic released (class break). */
    val paused: Boolean = false,
)

/**
 * Foreground service (type=microphone) that records a lecture part (45–50 min) with screen off.
 *
 * Safety: audio is rotated into ~10 min AAC/.m4a segment files. Each finished segment is
 * persisted to the Repository immediately, so a crash / OOM kill loses at most the
 * current segment instead of the whole lecture.
 */
class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var segmentStartWall = 0L
    private var segmentStartElapsed = 0L
    private var startElapsed = 0L
    private var seq = 0
    private var lectureId: String? = null
    private var ticker: Job? = null
    private var lastNotif = 0L
    private var title = ""
    private var wakeLock: PowerManager.WakeLock? = null

    private val repo get() = (application as LectureApp).repo

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_LECTURE)?.let { start(it) }
            ACTION_MARKER -> addMarker()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private var paused = false

    private fun start(id: String) {
        if (paused && id == lectureId) return resume()
        if (recorder != null || paused) return
        lectureId = id
        val lecture = repo.lecture(id) ?: return stopSelf()
        seq = lecture.segments.maxOfOrNull { it.seq + 1 } ?: 0
        // Resume elapsed across restarts of the same lecture so marker timestamps stay monotonic.
        val prior = lecture.segments.sumOf { it.durationMs }
        startElapsed = SystemClock.elapsedRealtime() - prior

        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(lecture.title.also { title = it }, prior),
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        )
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LectureSummarizer:rec")
            .apply { acquire(4 * 60 * 60 * 1000L) }
        repo.updateLecture(id) { it.copy(state = LectureState.RECORDING) }
        startSegment()

        ticker = scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (paused) { delay(500); continue }
                _state.value = _state.value.copy(elapsedMs = now - startElapsed, segmentSeq = seq)
                if (now - segmentStartElapsed >= SEGMENT_MS) rotateSegment()
                if (now - lastNotif >= 1000) { lastNotif = now; updateNotification(now - startElapsed) }
                delay(500)
            }
        }
    }

    private fun startSegment() {
        val id = lectureId ?: return
        val f = File(repo.recordingsDir(id), "seg_%03d.m4a".format(seq))
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16_000)      // speech: 16 kHz mono is what ASR wants anyway
        r.setAudioEncodingBitRate(32_000)   // ~14 MB/hour
        r.setOutputFile(f.absolutePath)
        r.prepare(); r.start()
        recorder = r; currentFile = f
        segmentStartWall = System.currentTimeMillis()
        segmentStartElapsed = SystemClock.elapsedRealtime()
        _state.value = RecorderUiState(id, true, SystemClock.elapsedRealtime() - startElapsed, seq,
            repo.lecture(id)?.markers?.size ?: 0)
    }

    /** Finalize the current file (moov atom written on stop) and record it in the repo. */
    private fun finishSegment() {
        val r = recorder ?: return
        val f = currentFile
        runCatching { r.stop() }   // throws if stopped right after start; file then unusable
        r.release(); recorder = null
        val id = lectureId ?: return
        if (f != null && f.length() > 0) {
            val seg = Segment(seq, f.absolutePath, segmentStartWall, SystemClock.elapsedRealtime() - segmentStartElapsed)
            repo.updateLecture(id) { it.copy(segments = it.segments + seg) }
            seq++
        } else f?.delete()
    }

    private fun rotateSegment() { finishSegment(); startSegment() }

    private fun addMarker() {
        val id = lectureId ?: return
        if (recorder == null) return
        val t = (SystemClock.elapsedRealtime() - startElapsed) / 1000
        repo.updateLecture(id) { it.copy(markers = it.markers + Marker(t)) }
        _state.value = _state.value.copy(markerCount = _state.value.markerCount + 1)
    }

    /** Break: finalize the current segment (moov written) and release the mic; break time isn't counted. */
    private fun pause() {
        if (paused || recorder == null) return
        finishSegment()
        paused = true
        _state.value = _state.value.copy(recording = true, paused = true,
            elapsedMs = lectureId?.let { repo.lecture(it) }?.segments?.sumOf { it.durationMs } ?: _state.value.elapsedMs)
        updateNotification(_state.value.elapsedMs)
    }

    /** Continue the same lecture as a new segment; elapsed resumes from the sum of saved segments. */
    private fun resume() {
        if (!paused) return
        val id = lectureId ?: return
        val prior = repo.lecture(id)?.segments?.sumOf { it.durationMs } ?: 0
        startElapsed = SystemClock.elapsedRealtime() - prior
        paused = false
        runCatching { startSegment() }.onFailure { paused = true; return }
        updateNotification(prior)
    }

    private fun stopAll() {
        paused = false
        ticker?.cancel()
        finishSegment()
        lectureId?.let { id -> repo.updateLecture(id) { it.copy(state = LectureState.RECORDED) } }
        _state.value = _state.value.copy(recording = false, paused = false)
        wakeLock?.let { if (it.isHeld) it.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null || paused) stopAll()
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(elapsedMs: Long) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(title, elapsedMs))
        }
    }

    private fun fmt(ms: Long): String { val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60) }

    private fun buildNotification(title: String, elapsedMs: Long = 0): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, RecorderService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        if (paused) return NotificationCompat.Builder(this, LectureApp.CHANNEL_RECORDING)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("Paused: $title")
            .setContentText("⏸ ${fmt(elapsedMs)} recorded · break time not recorded · tap Resume")
            .setSubText(fmt(elapsedMs))
            .setShowWhen(false)
            .setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Resume", action(ACTION_RESUME, 3))
            .addAction(0, "Stop", action(ACTION_STOP, 2))
            .build()
        return NotificationCompat.Builder(this, LectureApp.CHANNEL_RECORDING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording: $title")
            .setContentText("● ${fmt(elapsedMs)} elapsed · ${_state.value.markerCount} markers · tap 'Next slide' to mark")
            .setSubText(fmt(elapsedMs))
            .setWhen(System.currentTimeMillis() - elapsedMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Next slide", action(ACTION_MARKER, 1))
            .addAction(0, "Pause", action(ACTION_PAUSE, 4))
            .addAction(0, "Stop", action(ACTION_STOP, 2))
            .build()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_MARKER = "marker"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val EXTRA_LECTURE = "lecture"
        private const val NOTIF_ID = 42
        const val SEGMENT_MS = 10 * 60 * 1000L

        private val _state = MutableStateFlow(RecorderUiState())
        val state: StateFlow<RecorderUiState> = _state.asStateFlow()

        fun start(ctx: Context, lectureId: String) = ctx.startForegroundService(
            Intent(ctx, RecorderService::class.java).setAction(ACTION_START).putExtra(EXTRA_LECTURE, lectureId))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        fun pause(ctx: Context) = ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_PAUSE))
        fun resume(ctx: Context) = ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_RESUME))
        fun marker(ctx: Context) = ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_MARKER))
    }
}
