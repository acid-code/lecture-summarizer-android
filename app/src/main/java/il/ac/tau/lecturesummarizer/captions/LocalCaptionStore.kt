package il.ac.tau.lecturesummarizer.captions

import android.content.Context
import org.json.JSONObject
import java.io.File

data class LocalCaptionLine(
    val t0: Double,
    val t1: Double,
    val raw: String,
    val text: String,
    val cleaned: Boolean,
)

/** UTF-8 JSONL next to lecture recordings: recordings/{id}/local_captions.jsonl */
class LocalCaptionStore(private val context: Context) {
    fun file(lectureId: String): File =
        File(File(context.filesDir, "recordings/$lectureId").apply { mkdirs() }, "local_captions.jsonl")

    @Synchronized
    fun append(lectureId: String, line: LocalCaptionLine) {
        val f = file(lectureId)
        val obj = JSONObject()
            .put("t0", line.t0)
            .put("t1", line.t1)
            .put("raw", line.raw)
            .put("text", line.text)
            .put("cleaned", line.cleaned)
        f.appendText(obj.toString() + "\n", Charsets.UTF_8)
    }

    @Synchronized
    fun updateLastText(lectureId: String, text: String, cleaned: Boolean) {
        val f = file(lectureId)
        if (!f.isFile) return
        val lines = f.readLines(Charsets.UTF_8).toMutableList()
        if (lines.isEmpty()) return
        val obj = JSONObject(lines.last())
        obj.put("text", text)
        obj.put("cleaned", cleaned)
        lines[lines.lastIndex] = obj.toString()
        f.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    fun read(lectureId: String): List<LocalCaptionLine> {
        val f = file(lectureId)
        if (!f.isFile) return emptyList()
        return f.readLines(Charsets.UTF_8).mapNotNull { row ->
            runCatching {
                val o = JSONObject(row)
                LocalCaptionLine(
                    t0 = o.getDouble("t0"),
                    t1 = o.getDouble("t1"),
                    raw = o.getString("raw"),
                    text = o.getString("text"),
                    cleaned = o.optBoolean("cleaned", false),
                )
            }.getOrNull()
        }
    }

    fun delete(lectureId: String) {
        file(lectureId).delete()
    }

    fun exportTxt(lines: List<LocalCaptionLine>): String =
        lines.joinToString("\n") { it.text }.trim() + if (lines.isNotEmpty()) "\n" else ""
}
