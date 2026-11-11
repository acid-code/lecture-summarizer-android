package il.ac.tau.lecturesummarizer.captions

import android.util.Log
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
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = CaptionModels.SAMPLE_RATE, featureDim = 80),
            modelConfig = model,
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

/**
 * Hebrew Zipformer + optional English Zipformer.
 * English speech is routed to Latin script via [AsrPick] instead of HE-only mistranslation.
 */
class ZipformerEngine(store: CaptionModelStore) : AutoCloseable {
    private val he: StreamingZipformer
    private val en: StreamingZipformer?
    private val closed = AtomicBoolean(false)
    private var lastPartial = ""
    private var lastPartialAt = 0L

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
        Log.i(TAG, "ASR ready he=true en=${en != null}")
    }

    @Synchronized
    fun acceptPcm(samples: FloatArray): ZipResult? {
        if (closed.get()) return null
        val (heText, heEnd) = he.feed(samples)
        val (enText, enEnd) = en?.feed(samples) ?: ("" to false)
        val endpoint = heEnd || enEnd
        if (endpoint) {
            // Flush the peer that did not endpoint so both hypotheses are complete.
            val heLine = if (heEnd) heText else he.flush()
            val enLine = when {
                en == null -> ""
                enEnd -> enText
                else -> en.flush()
            }
            lastPartial = ""
            lastPartialAt = 0L
            val picked = AsrPick.choose(heLine, enLine)
            return picked.takeIf { it.isNotBlank() }?.let { ZipResult.Final(it) }
        }
        val partial = AsrPick.choose(heText, enText)
        if (partial.isBlank() || partial == lastPartial) return null
        val now = System.currentTimeMillis()
        if (now - lastPartialAt < 180L && partial.startsWith(lastPartial)) return null
        lastPartial = partial
        lastPartialAt = now
        return ZipResult.Partial(partial)
    }

    @Synchronized
    fun flush(): String? {
        if (closed.get()) return null
        val heLine = he.flush()
        val enLine = en?.flush().orEmpty()
        lastPartial = ""
        lastPartialAt = 0L
        return AsrPick.choose(heLine, enLine).takeIf { it.isNotBlank() }
    }

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
