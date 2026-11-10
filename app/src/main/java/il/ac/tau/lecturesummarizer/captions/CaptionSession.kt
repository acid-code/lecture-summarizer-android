package il.ac.tau.lecturesummarizer.captions

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.LayoutDirection
import il.ac.tau.lecturesummarizer.data.Lecture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

enum class CaptionRunState { Off, Starting, Running, Paused, Stopped }

data class CaptionUiLine(
    val id: Long,
    val raw: String,
    val text: String,
    val cleaned: Boolean,
    val t0: Double,
    val t1: Double,
    val direction: LayoutDirection,
)

data class CaptionUiState(
    val run: CaptionRunState = CaptionRunState.Off,
    val lines: List<CaptionUiLine> = emptyList(),
    val status: String = "",
    val cleanupActive: Boolean = false,
    val needsAgreement: Boolean = false,
    val needsDownload: Boolean = false,
    val waitingUnmetered: Boolean = false,
)

/**
 * Coordinates mic → Zipformer → glossary → DictaLM and optional lecture JSONL persistence.
 */
class CaptionSession(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = CaptionModelStore(context)
    private val localStore = LocalCaptionStore(context)
    private val _state = MutableStateFlow(CaptionUiState())
    val state: StateFlow<CaptionUiState> = _state.asStateFlow()

    private var zip: ZipformerEngine? = null
    private var lm: DictaLmEngine? = null
    private var mic: CaptionMic? = null
    private var glossary = CaptionGlossary(emptyList())
    private var lectureId: String? = null
    private var sessionStartMs = 0L
    private var lastDir: LayoutDirection? = null
    private var nextId = 1L
    private val cleanupMutex = Mutex()
    private val stoppedForSession = AtomicBoolean(false)
    private var captionsPausedAlone = false

    fun prepareGates(): CaptionUiState {
        store.refreshReadyState()
        val needsAgreement = !store.agreed
        val needsDownload = store.agreed && !store.modelsReady()
        val waiting = needsDownload && store.downloadState == CaptionDownloadState.Downloading
        _state.update {
            it.copy(
                needsAgreement = needsAgreement,
                needsDownload = needsDownload,
                waitingUnmetered = waiting || (needsDownload && store.downloadState != CaptionDownloadState.Failed),
                status = when {
                    needsAgreement -> context.getString(il.ac.tau.lecturesummarizer.R.string.captions_need_agree)
                    needsDownload && store.downloadState == CaptionDownloadState.Failed ->
                        store.lastError ?: context.getString(il.ac.tau.lecturesummarizer.R.string.captions_download_failed)
                    needsDownload -> context.getString(il.ac.tau.lecturesummarizer.R.string.captions_need_download)
                    else -> ""
                },
            )
        }
        return _state.value
    }

    fun agreeAndDownload() {
        val hw = CaptionHardware.snapshot(context, store.modelsDir)
        if (!hw.canDownload(CaptionModels.DOWNLOAD_TOTAL_BYTES - store.bytesOnDisk())) {
            _state.update { it.copy(status = context.getString(il.ac.tau.lecturesummarizer.R.string.captions_err_disk)) }
            return
        }
        store.agreed = true
        store.downloadState = CaptionDownloadState.Downloading
        CaptionModelDownloader.enqueue(context)
        prepareGates()
    }

    fun start(lecture: Lecture? = null, localeRtl: Boolean = true) {
        if (stoppedForSession.get() && lecture != null) {
            _state.update { it.copy(status = context.getString(il.ac.tau.lecturesummarizer.R.string.captions_stopped_session)) }
            return
        }
        prepareGates()
        if (!store.agreed || !store.modelsReady()) return
        val hw = CaptionHardware.snapshot(context, store.modelsDir)
        _state.update { it.copy(run = CaptionRunState.Starting, status = "") }
        scope.launch {
            try {
                lectureId = lecture?.id
                glossary = CaptionGlossary.fromLecture(context, lecture)
                sessionStartMs = System.currentTimeMillis()
                lastDir = if (localeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                zip = ZipformerEngine(store)
                val useCleanup = hw.canLoadCleanup && !hw.lowRamDevice
                lm = if (useCleanup) {
                    runCatching { DictaLmEngine.open(store) }.onFailure {
                        Log.w(TAG, "LM load skipped", it)
                    }.getOrNull()
                } else null
                mic = CaptionMic { pcm -> onPcm(pcm) }.also { it.start() }
                captionsPausedAlone = false
                _state.update {
                    it.copy(
                        run = CaptionRunState.Running,
                        cleanupActive = lm != null,
                        status = if (lm == null)
                            context.getString(il.ac.tau.lecturesummarizer.R.string.captions_no_cleanup_ram)
                        else context.getString(il.ac.tau.lecturesummarizer.R.string.captions_on),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                releaseEngines()
                _state.update {
                    it.copy(run = CaptionRunState.Off, status = e.message?.take(160) ?: "start failed")
                }
            }
        }
    }

    /** Pause captions only; mic recording (lecture) may continue. */
    fun pauseCaptions() {
        captionsPausedAlone = true
        mic?.stop()
        mic = null
        zip?.flush()?.let { emitLine(it) }
        _state.update { it.copy(run = CaptionRunState.Paused, status = context.getString(il.ac.tau.lecturesummarizer.R.string.captions_paused)) }
    }

    fun continueCaptions() {
        if (_state.value.run != CaptionRunState.Paused) return
        captionsPausedAlone = false
        mic = CaptionMic { pcm -> onPcm(pcm) }.also { it.start() }
        _state.update { it.copy(run = CaptionRunState.Running, status = context.getString(il.ac.tau.lecturesummarizer.R.string.captions_on)) }
    }

    /** Stop captions for the rest of this lecture recording. */
    fun stopCaptionsForSession() {
        stoppedForSession.set(true)
        pauseCaptions()
        _state.update { it.copy(run = CaptionRunState.Stopped, status = context.getString(il.ac.tau.lecturesummarizer.R.string.captions_stopped_session)) }
    }

    fun startAgain() {
        stoppedForSession.set(false)
        continueCaptions()
    }

    /** Recording pause: freeze captions with the mic. */
    fun onRecordingPaused() {
        if (_state.value.run == CaptionRunState.Running) pauseCaptions()
    }

    /** Recording resume: restore prior caption state (stay stopped if user stopped captions). */
    fun onRecordingResumed() {
        if (stoppedForSession.get()) return
        if (captionsPausedAlone || _state.value.run == CaptionRunState.Paused) {
            // Was paused with recording — resume captions too.
            continueCaptions()
        }
    }

    fun onRecordingStopped() {
        scope.launch {
            zip?.flush()?.let { emitLine(it) }
            releaseEngines()
            _state.update { it.copy(run = CaptionRunState.Off) }
            stoppedForSession.set(false)
        }
    }

    fun dump(): List<CaptionUiLine> {
        val lines = _state.value.lines
        releaseEngines()
        _state.value = CaptionUiState()
        stoppedForSession.set(false)
        return lines
    }

    fun exportText(): String = LocalCaptionStore(context).exportTxt(
        _state.value.lines.map {
            LocalCaptionLine(it.t0, it.t1, it.raw, it.text, it.cleaned)
        },
    )

    fun loadLectureLines(id: String) {
        val loaded = localStore.read(id).mapIndexed { i, l ->
            val dir = LineDirection.of(l.text, lastDir, LayoutDirection.Rtl)
            lastDir = dir
            CaptionUiLine(i.toLong() + 1, l.raw, l.text, l.cleaned, l.t0, l.t1, dir)
        }
        nextId = (loaded.maxOfOrNull { it.id } ?: 0L) + 1
        _state.update { it.copy(lines = loaded) }
    }

    fun release() {
        releaseEngines()
        scope.cancel()
    }

    private fun onPcm(pcm: FloatArray) {
        if (_state.value.run != CaptionRunState.Running) return
        val line = runCatching { zip?.acceptPcm(pcm) }.getOrNull() ?: return
        emitLine(line)
    }

    private fun emitLine(raw: String) {
        val glossed = glossary.apply(raw)
        val now = (System.currentTimeMillis() - sessionStartMs) / 1000.0
        val t0 = (now - 1.28).coerceAtLeast(0.0)
        val dir = LineDirection.of(glossed, lastDir, LayoutDirection.Rtl)
        lastDir = dir
        val id = nextId++
        val ui = CaptionUiLine(id, raw, glossed, false, t0, now, dir)
        _state.update { it.copy(lines = it.lines + ui) }
        lectureId?.let {
            localStore.append(it, LocalCaptionLine(t0, now, raw, glossed, false))
        }
        val engine = lm ?: return
        scope.launch {
            cleanupMutex.withLock {
                val cleaned = runCatching { engine.rewrite(glossed, glossary.slideTermsForPrompt()) }.getOrNull() ?: return@withLock
                _state.update { st ->
                    st.copy(lines = st.lines.map { if (it.id == id) it.copy(text = cleaned, cleaned = true) else it })
                }
                lectureId?.let { localStore.updateLastText(it, cleaned, true) }
            }
        }
    }

    private fun releaseEngines() {
        runCatching { mic?.close() }; mic = null
        runCatching { zip?.close() }; zip = null
        runCatching { lm?.close() }; lm = null
    }

    companion object {
        private const val TAG = "CaptionSession"
    }
}
