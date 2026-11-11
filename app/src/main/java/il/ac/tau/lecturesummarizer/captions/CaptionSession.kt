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
    /** In-progress hypothesis (not yet a committed line). */
    val partial: String = "",
    val partialDirection: LayoutDirection = LayoutDirection.Rtl,
    val status: String = "",
    val cleanupActive: Boolean = false,
    val needsAgreement: Boolean = false,
    /** True until Zipformer is on disk (speech can start). */
    val needsSpeechDownload: Boolean = false,
    /** True while cleanup GGUF is still downloading (speech may already work). */
    val cleanupDownloading: Boolean = false,
    /** Speech model on disk — Start captions is allowed. */
    val canStart: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadPercent: Int = 0,
    val waitingUnmetered: Boolean = false,
    val asrLanguage: CaptionAsrLanguage = CaptionAsrLanguage.Hebrew,
    /** English Zipformer files present (chip enabled). */
    val englishAvailable: Boolean = false,
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
        if (store.agreed && !store.modelsReady()) store.ensureBackgroundDownload()
        val needsAgreement = !store.agreed
        val speechReady = store.speechReady()
        val needsSpeechDownload = store.agreed && !speechReady
        val cleanupDownloading = store.agreed && speechReady &&
            (!store.englishReady() || !store.ggufReady()) &&
            store.downloadState == CaptionDownloadState.Downloading
        val downloading = store.downloadState == CaptionDownloadState.Downloading
        val pct = (store.progressFraction() * 100).toInt().coerceIn(0, 100)
        val canStart = store.agreed && speechReady
        // While a session is open, EN only if the engine actually loaded it (needs stop/start after download).
        val englishAvailable = zip?.englishLoaded ?: store.englishReady()
        if (store.asrLanguage == CaptionAsrLanguage.English && !englishAvailable) {
            store.asrLanguage = CaptionAsrLanguage.Hebrew
        }
        _state.update {
            val runningStatus = it.status.takeIf { s ->
                s == context.getString(il.ac.tau.lecturesummarizer.R.string.captions_on) ||
                    s == context.getString(il.ac.tau.lecturesummarizer.R.string.captions_no_cleanup_ram) ||
                    s == context.getString(il.ac.tau.lecturesummarizer.R.string.captions_no_english_yet) ||
                    s == context.getString(il.ac.tau.lecturesummarizer.R.string.captions_paused) ||
                    s == context.getString(il.ac.tau.lecturesummarizer.R.string.captions_stopped_session) ||
                    s == context.getString(il.ac.tau.lecturesummarizer.R.string.captions_starting)
            }
            it.copy(
                needsAgreement = needsAgreement,
                needsSpeechDownload = needsSpeechDownload,
                cleanupDownloading = cleanupDownloading,
                canStart = canStart,
                waitingUnmetered = needsSpeechDownload && !downloading &&
                    store.downloadState != CaptionDownloadState.Failed,
                downloadProgress = store.progressFraction(),
                downloadPercent = pct,
                asrLanguage = store.asrLanguage,
                englishAvailable = englishAvailable,
                status = when {
                    it.run == CaptionRunState.Running || it.run == CaptionRunState.Starting ||
                        it.run == CaptionRunState.Paused || it.run == CaptionRunState.Stopped ->
                        runningStatus.orEmpty()
                    needsAgreement -> context.getString(il.ac.tau.lecturesummarizer.R.string.captions_need_agree)
                    needsSpeechDownload && store.downloadState == CaptionDownloadState.Failed ->
                        store.lastError ?: context.getString(il.ac.tau.lecturesummarizer.R.string.captions_download_failed)
                    needsSpeechDownload && downloading ->
                        context.getString(il.ac.tau.lecturesummarizer.R.string.captions_downloading_speech)
                    needsSpeechDownload ->
                        context.getString(il.ac.tau.lecturesummarizer.R.string.captions_need_download)
                    cleanupDownloading ->
                        context.getString(il.ac.tau.lecturesummarizer.R.string.captions_ready_optional_download)
                    canStart ->
                        context.getString(il.ac.tau.lecturesummarizer.R.string.captions_ready_tap_start)
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

    /** Active HE/EN choice — can change mid-lecture; no auto switching. */
    fun setAsrLanguage(lang: CaptionAsrLanguage) {
        val enOk = zip?.englishLoaded ?: store.englishReady()
        if (lang == CaptionAsrLanguage.English && !enOk) return
        store.asrLanguage = lang
        lastDir = if (lang == CaptionAsrLanguage.Hebrew) LayoutDirection.Rtl else LayoutDirection.Ltr
        val leftover = zip?.setLanguage(lang)
        _state.update {
            it.copy(
                asrLanguage = store.asrLanguage,
                englishAvailable = enOk,
                partial = "",
            )
        }
        leftover?.let { emitLine(it) }
    }

    fun start(lecture: Lecture? = null, localeRtl: Boolean = true) {
        if (stoppedForSession.get() && lecture != null) {
            _state.update { it.copy(status = context.getString(il.ac.tau.lecturesummarizer.R.string.captions_stopped_session)) }
            return
        }
        prepareGates()
        if (!store.agreed || !store.speechReady()) return
        val hw = CaptionHardware.snapshot(context, store.modelsDir)
        _state.update { it.copy(run = CaptionRunState.Starting, status = "", partial = "") }
        scope.launch {
            try {
                lectureId = lecture?.id
                glossary = CaptionGlossary.fromLecture(context, lecture)
                sessionStartMs = System.currentTimeMillis()
                lastDir = when (store.asrLanguage) {
                    CaptionAsrLanguage.Hebrew -> LayoutDirection.Rtl
                    CaptionAsrLanguage.English -> LayoutDirection.Ltr
                }
                zip = ZipformerEngine(store, store.asrLanguage)
                val useCleanup = store.ggufReady() && hw.canLoadCleanup && !hw.lowRamDevice
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
                        asrLanguage = store.asrLanguage,
                        englishAvailable = zip?.englishLoaded == true,
                        status = when {
                            lm != null -> context.getString(il.ac.tau.lecturesummarizer.R.string.captions_on)
                            else -> context.getString(il.ac.tau.lecturesummarizer.R.string.captions_no_cleanup_ram)
                        },
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

    fun pauseCaptions() {
        captionsPausedAlone = true
        mic?.stop()
        mic = null
        zip?.flush()?.let { emitLine(it) }
        _state.update {
            it.copy(
                run = CaptionRunState.Paused,
                partial = "",
                status = context.getString(il.ac.tau.lecturesummarizer.R.string.captions_paused),
            )
        }
    }

    fun continueCaptions() {
        if (_state.value.run != CaptionRunState.Paused) return
        captionsPausedAlone = false
        mic = CaptionMic { pcm -> onPcm(pcm) }.also { it.start() }
        _state.update {
            it.copy(
                run = CaptionRunState.Running,
                status = if (lm != null) context.getString(il.ac.tau.lecturesummarizer.R.string.captions_on)
                else context.getString(il.ac.tau.lecturesummarizer.R.string.captions_no_cleanup_ram),
            )
        }
    }

    fun stopCaptionsForSession() {
        stoppedForSession.set(true)
        pauseCaptions()
        _state.update { it.copy(run = CaptionRunState.Stopped, status = context.getString(il.ac.tau.lecturesummarizer.R.string.captions_stopped_session)) }
    }

    fun startAgain() {
        stoppedForSession.set(false)
        if (_state.value.run == CaptionRunState.Paused) continueCaptions()
        else if (zip != null) wakeCaptions()
    }

    /**
     * Close the captions UI during an ongoing lecture recording:
     * stop listening but keep models/lines so Open captions can resume.
     */
    fun dismissCaptionsUi() {
        stoppedForSession.set(false)
        captionsPausedAlone = false
        when (_state.value.run) {
            CaptionRunState.Running, CaptionRunState.Starting -> {
                mic?.stop()
                mic = null
                zip?.flush()?.let { emitLine(it) }
            }
            CaptionRunState.Paused, CaptionRunState.Stopped -> {
                mic?.stop()
                mic = null
            }
            CaptionRunState.Off -> Unit
        }
        _state.update { it.copy(run = CaptionRunState.Off, partial = "", status = "") }
    }

    /** Resume after [dismissCaptionsUi] without reloading models when possible. */
    fun wakeCaptions(lecture: Lecture? = null, localeRtl: Boolean = true) {
        stoppedForSession.set(false)
        if (zip == null) {
            start(lecture, localeRtl)
            return
        }
        if (_state.value.run == CaptionRunState.Running || _state.value.run == CaptionRunState.Starting) return
        captionsPausedAlone = false
        mic?.stop()
        mic = CaptionMic { pcm -> onPcm(pcm) }.also { it.start() }
        _state.update {
            it.copy(
                run = CaptionRunState.Running,
                partial = "",
                asrLanguage = store.asrLanguage,
                englishAvailable = zip?.englishLoaded == true,
                status = if (lm != null) context.getString(il.ac.tau.lecturesummarizer.R.string.captions_on)
                else context.getString(il.ac.tau.lecturesummarizer.R.string.captions_no_cleanup_ram),
            )
        }
    }

    fun onRecordingPaused() {
        // Lecture pause — pause captions with it, but not as a captions-only pause.
        if (_state.value.run != CaptionRunState.Running) return
        captionsPausedAlone = false
        mic?.stop()
        mic = null
        zip?.flush()?.let { emitLine(it) }
        _state.update {
            it.copy(
                run = CaptionRunState.Paused,
                partial = "",
                status = context.getString(il.ac.tau.lecturesummarizer.R.string.captions_paused),
            )
        }
    }

    fun onRecordingResumed() {
        if (stoppedForSession.get()) return
        if (_state.value.run != CaptionRunState.Paused) return
        // User paused captions alone — leave them paused while recording continues.
        if (captionsPausedAlone) return
        continueCaptions()
    }

    fun onRecordingStopped() {
        scope.launch {
            zip?.flush()?.let { emitLine(it) }
            releaseEngines()
            _state.update { it.copy(run = CaptionRunState.Off, partial = "") }
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
        when (val result = runCatching { zip?.acceptPcm(pcm) }.getOrNull()) {
            is ZipResult.Partial -> {
                val dir = LineDirection.of(result.text, lastDir, LayoutDirection.Rtl)
                _state.update { it.copy(partial = result.text, partialDirection = dir) }
            }
            is ZipResult.Final -> {
                _state.update { it.copy(partial = "") }
                emitLine(result.text)
            }
            null -> Unit
        }
    }

    private fun emitLine(raw: String) {
        val glossed = glossary.apply(raw)
        val now = (System.currentTimeMillis() - sessionStartMs) / 1000.0
        val t0 = (now - 1.28).coerceAtLeast(0.0)
        val dir = LineDirection.of(glossed, lastDir, LayoutDirection.Rtl)
        lastDir = dir
        val id = nextId++
        val ui = CaptionUiLine(id, raw, glossed, false, t0, now, dir)
        _state.update { it.copy(lines = it.lines + ui, partial = "") }
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
