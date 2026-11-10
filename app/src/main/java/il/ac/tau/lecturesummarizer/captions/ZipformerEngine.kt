package il.ac.tau.lecturesummarizer.captions

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming Hebrew Zipformer via sherpa-onnx. Emits a finished line on endpoint / pause.
 */
class ZipformerEngine(store: CaptionModelStore) : AutoCloseable {
    private val recognizer: OnlineRecognizer
    private var stream: OnlineStream
    private val closed = AtomicBoolean(false)
    private var lastEmitted = ""

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

    /** Feed 16 kHz mono PCM. Returns a finished line when the endpoint fires, else null. */
    @Synchronized
    fun acceptPcm(samples: FloatArray): String? {
        if (closed.get()) return null
        stream.acceptWaveform(samples, sampleRate = CaptionModels.SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        val endpoint = recognizer.isEndpoint(stream)
        if (!endpoint) return null
        val line = text.removePrefix(lastEmitted).trim().ifEmpty { text }
        recognizer.reset(stream)
        lastEmitted = ""
        return line.takeIf { it.isNotBlank() }
    }

    /** Force-flush a pending hypothesis (e.g. user paused). */
    @Synchronized
    fun flush(): String? {
        if (closed.get()) return null
        val text = recognizer.getResult(stream).text.trim()
        recognizer.reset(stream)
        lastEmitted = ""
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
