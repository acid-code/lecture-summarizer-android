package il.ac.tau.lecturesummarizer.captions

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.atomic.AtomicBoolean

sealed class ZipResult {
    data class Partial(val text: String) : ZipResult()
    data class Final(val text: String) : ZipResult()
}

/**
 * Streaming Hebrew Zipformer via sherpa-onnx.
 * Emits [ZipResult.Partial] while speaking and [ZipResult.Final] on endpoint / pause.
 */
class ZipformerEngine(store: CaptionModelStore) : AutoCloseable {
    private val recognizer: OnlineRecognizer
    private var stream: OnlineStream
    private val closed = AtomicBoolean(false)
    private var lastPartial = ""
    private var lastPartialAt = 0L

    init {
        require(store.zipformerReady()) { "Zipformer models not on disk" }
        val transducer = OnlineTransducerModelConfig(
            encoder = store.zipformerFile(CaptionModels.ENCODER).absolutePath,
            decoder = store.zipformerFile(CaptionModels.DECODER).absolutePath,
            joiner = store.zipformerFile(CaptionModels.JOINER).absolutePath,
        )
        val model = OnlineModelConfig(
            transducer = transducer,
            tokens = store.zipformerFile(CaptionModels.TOKENS).absolutePath,
            numThreads = 2,
            provider = "cpu",
            modelType = "zipformer2",
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

    /** Feed 16 kHz mono PCM. Partial updates are throttled (~180 ms). */
    @Synchronized
    fun acceptPcm(samples: FloatArray): ZipResult? {
        if (closed.get()) return null
        stream.acceptWaveform(samples, sampleRate = CaptionModels.SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        if (recognizer.isEndpoint(stream)) {
            val line = text.ifBlank { lastPartial }.trim()
            recognizer.reset(stream)
            lastPartial = ""
            lastPartialAt = 0L
            return line.takeIf { it.isNotBlank() }?.let { ZipResult.Final(it) }
        }
        if (text.isBlank() || text == lastPartial) return null
        val now = System.currentTimeMillis()
        if (now - lastPartialAt < 180L && text.startsWith(lastPartial)) return null
        lastPartial = text
        lastPartialAt = now
        return ZipResult.Partial(text)
    }

    /** Force-flush a pending hypothesis (e.g. user paused). */
    @Synchronized
    fun flush(): String? {
        if (closed.get()) return null
        val text = recognizer.getResult(stream).text.trim().ifBlank { lastPartial }.trim()
        recognizer.reset(stream)
        lastPartial = ""
        lastPartialAt = 0L
        return text.takeIf { it.isNotBlank() }
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { stream.release() }
        runCatching { recognizer.release() }
        Log.i(TAG, "Zipformer closed")
    }

    companion object {
        private const val TAG = "ZipformerEngine"
    }
}
