package il.ac.tau.lecturesummarizer.captions

import android.util.Log
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DictaLM 3.0 1.7B Instruct Q4 via llama.cpp Android. Rewrites one finished caption line.
 * Never translate; never add nikud. Reject empty / translated / wildly different length.
 */
class DictaLmEngine private constructor(private val model: LlamaModel) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun rewrite(line: String, slideTerms: String): String? {
        if (closed.get() || line.isBlank()) return null
        val prompt = buildString {
            if (slideTerms.isNotBlank()) append("Slide terms: ").append(slideTerms).append('\n')
            append("Line: ").append(line)
        }
        val out = runCatching {
            runBlocking {
                Llama.complete(
                    model,
                    prompt = prompt,
                    systemPrompt = SYSTEM,
                    maxTokens = 96,
                ).text.trim()
            }
        }.getOrElse {
            Log.w(TAG, "rewrite failed", it)
            return null
        }
        return accept(line, out)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { runBlocking { Llama.releaseModel(model) } }
        Log.i(TAG, "DictaLM closed")
    }

    companion object {
        private const val TAG = "DictaLmEngine"

        private const val SYSTEM =
            "You fix one caption line from a Hebrew university lecture that mixes in English. " +
                "Keep Hebrew in Hebrew and English in Latin letters. " +
                "Fix Hebrew spelling and grammar, and English spelling and grammar. " +
                "If an English word was spelled with Hebrew letters, write it in Latin letters. " +
                "Do not translate. Do not add nikud. Return the line only."

        fun open(store: CaptionModelStore): DictaLmEngine {
            require(store.ggufReady()) { "DictaLM GGUF not on disk" }
            val model = runBlocking {
                Llama.loadModel(
                    modelPath = store.ggufFile.absolutePath,
                    config = LlamaConfig(
                        contextSize = 1024,
                        threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                    ),
                )
            }
            return DictaLmEngine(model)
        }

        fun accept(original: String, candidate: String): String? {
            val c = candidate.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (c.isEmpty()) return null
            val origHe = original.count { it in '\u0590'..'\u05FF' }
            val candHe = c.count { it in '\u0590'..'\u05FF' }
            if (origHe >= 3 && candHe == 0 && c.any { it.isLetter() }) return null
            val oLen = original.length.coerceAtLeast(1)
            if (c.length > oLen * 3 || c.length < oLen / 4) return null
            return c
        }
    }
}
