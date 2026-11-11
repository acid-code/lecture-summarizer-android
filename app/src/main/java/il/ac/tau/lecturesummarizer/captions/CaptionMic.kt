package il.ac.tau.lecturesummarizer.captions

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 16 kHz mono PCM capture for Zipformer.
 * Uses VOICE_RECOGNITION so it can often run alongside [il.ac.tau.lecturesummarizer.recording.RecorderService]'s MediaRecorder.
 */
class CaptionMic(
    private val onPcm: (FloatArray) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val min = AudioRecord.getMinBufferSize(
            CaptionModels.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(CaptionModels.SAMPLE_RATE / 5 * 2)
        // Read ~100 ms chunks so Zipformer can emit live partials often.
        val readSamples = (CaptionModels.SAMPLE_RATE / 10).coerceAtLeast(320)
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            CaptionModels.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            min.coerceAtLeast(readSamples * 2),
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            running.set(false)
            error("AudioRecord init failed")
        }
        record = ar
        ar.startRecording()
        thread = Thread({
            val shortBuf = ShortArray(readSamples)
            try {
                while (running.get()) {
                    val n = ar.read(shortBuf, 0, shortBuf.size)
                    if (n <= 0) continue
                    val floats = FloatArray(n) { i -> shortBuf[i] / 32768f }
                    onPcm(floats)
                }
            } catch (e: Exception) {
                Log.w(TAG, "mic loop", e)
            }
        }, "caption-mic").also { it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.join(1000)
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    override fun close() = stop()

    companion object {
        private const val TAG = "CaptionMic"
    }
}
