package il.ac.tau.lecturesummarizer.captions

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed class ZipResult {
    data class Partial(val text: String) : ZipResult()
    data class Final(val text: String) : ZipResult()
}

/** One streaming Zipformer transducer (Hebrew or English). */
class StreamingZipformer(
    encoder: File,
    decoder: File,
    joiner: File,
    tokens: File,
    modelType: String,
) : AutoCloseable {
    private val recognizer: OnlineRecognizer
    private var stream: OnlineStream
    private val closed = AtomicBoolean(false)
    private var lastPartial = ""

    init {
        val transducer = OnlineTransducerModelConfig(
            encoder = encoder.absolutePath,
            decoder = decoder.absolutePath,
            joiner = joiner.absolutePath,
        )
        val model = OnlineModelConfig(
            transducer = transducer,
            tokens = tokens.absolutePath,
            numThreads = 1,
            provider = "cpu",
            modelType = modelType,
        )
        // Short trailing silence so lines commit sooner; partials still stream while speaking.
        val endpoints = EndpointConfig(
            rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.5f, minUtteranceLength = 0.0f),
            rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 0.35f, minUtteranceLength = 0.0f),
            rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.0f, minUtteranceLength = 12.0f),
        )
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = CaptionModels.SAMPLE_RATE, featureDim = 80),
            modelConfig = model,
            endpointConfig = endpoints,
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        recognizer = OnlineRecognizer(config = config)
        stream = recognizer.createStream()
    }

    @Synchronized
    fun feed(samples: FloatArray): Pair<String, Boolean> {
        if (closed.get()) return "" to false
        stream.acceptWaveform(samples, sampleRate = CaptionModels.SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        val endpoint = recognizer.isEndpoint(stream)
        if (endpoint) {
            val line = text.ifBlank { lastPartial }.trim()
            recognizer.reset(stream)
            lastPartial = ""
            return line to true
        }
        if (text.isNotBlank()) lastPartial = text
        return text to false
    }

    @Synchronized
    fun flush(): String {
        if (closed.get()) return ""
        val text = recognizer.getResult(stream).text.trim().ifBlank { lastPartial }.trim()
        recognizer.reset(stream)
        lastPartial = ""
        return text
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { stream.release() }
        runCatching { recognizer.release() }
    }
}

/** Manual HE-only or EN-only Zipformer — no automatic language picking. */
class ZipformerEngine(
    store: CaptionModelStore,
    initialLanguage: CaptionAsrLanguage = store.asrLanguage,
) : AutoCloseable {
    private val he: StreamingZipformer
    private val en: StreamingZipformer?
    private val closed = AtomicBoolean(false)
    private var language: CaptionAsrLanguage = initialLanguage
    private var lastPartial = ""
    private var lastPartialAt = 0L

    val englishLoaded: Boolean get() = en != null

    init {
        require(store.zipformerReady()) { "Zipformer models not on disk" }
        he = StreamingZipformer(
            encoder = store.zipformerFile(CaptionModels.ENCODER),
            decoder = store.zipformerFile(CaptionModels.DECODER),
            joiner = store.zipformerFile(CaptionModels.JOINER),
            tokens = store.zipformerFile(CaptionModels.TOKENS),
            modelType = "zipformer2",
        )
        en = if (store.englishReady()) {
            runCatching {
                StreamingZipformer(
                    encoder = store.englishFile(CaptionModels.EN_ENCODER),
                    decoder = store.englishFile(CaptionModels.EN_DECODER),
                    joiner = store.englishFile(CaptionModels.EN_JOINER),
                    tokens = store.englishFile(CaptionModels.EN_TOKENS),
                    modelType = "zipformer",
                )
            }.onFailure { Log.w(TAG, "English Zipformer load failed", it) }.getOrNull()
        } else null
        if (language == CaptionAsrLanguage.English && en == null) {
            language = CaptionAsrLanguage.Hebrew
        }
        Log.i(TAG, "ASR ready he=true en=${en != null} lang=$language")
    }

    /** Switch HE/EN mid-session. Returns any leftover final text from the previous model. */
    @Synchronized
    fun setLanguage(lang: CaptionAsrLanguage): String? {
        if (closed.get()) return null
        val target = if (lang == CaptionAsrLanguage.English && en == null) CaptionAsrLanguage.Hebrew else lang
        if (target == language) return null
        val leftover = active().flush().takeIf { it.isNotBlank() }
        // Drop stale hypothesis on the inactive stream so a later switch starts clean.
        inactive()?.flush()
        language = target
        lastPartial = ""
        lastPartialAt = 0L
        Log.i(TAG, "ASR language -> $language")
        return leftover
    }

    @Synchronized
    fun acceptPcm(samples: FloatArray): ZipResult? {
        if (closed.get()) return null
        val (text, endpoint) = active().feed(samples)
        if (endpoint) {
            lastPartial = ""
            lastPartialAt = 0L
            return text.takeIf { it.isNotBlank() }?.let { ZipResult.Final(it) }
        }
        val partial = text.trim()
        if (partial.isBlank()) return null
        if (partial == lastPartial) return null
        val now = System.currentTimeMillis()
        if (now - lastPartialAt < 60L && partial.length <= lastPartial.length) return null
        lastPartial = partial
        lastPartialAt = now
        return ZipResult.Partial(partial)
    }

    @Synchronized
    fun flush(): String? {
        if (closed.get()) return null
        lastPartial = ""
        lastPartialAt = 0L
        return active().flush().takeIf { it.isNotBlank() }
    }

    private fun active(): StreamingZipformer =
        if (language == CaptionAsrLanguage.English && en != null) en else he

    private fun inactive(): StreamingZipformer? =
        if (language == CaptionAsrLanguage.English) he else en

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { he.close() }
        runCatching { en?.close() }
        Log.i(TAG, "Zipformer closed")
    }

    companion object {
        private const val TAG = "ZipformerEngine"
    }
}
