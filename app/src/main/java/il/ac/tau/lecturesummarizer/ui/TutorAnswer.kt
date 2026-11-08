package il.ac.tau.lecturesummarizer.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.study.TutorCitation
import kotlin.math.roundToInt

/** Display / inline math and paragraph blocks for the tutor answer. */
sealed class TutorBlock {
    data class Paragraph(val text: String) : TutorBlock()
    data class DisplayMath(val tex: String) : TutorBlock()
}

private val DISPLAY_MATH = Regex("""\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]""")
private val INLINE_MATH = Regex("""\\\((.+?)\\\)|\$(?!\s)([^$\n]+?)(?<!\s)\$(?!\d)""")
private val CITE_MARK = Regex("""\[(\d{1,2})\]""")

fun hasTutorMath(answer: String): Boolean = DISPLAY_MATH.containsMatchIn(answer) || INLINE_MATH.containsMatchIn(answer)

/** Split answer into paragraphs and display-math blocks (order preserved). */
fun parseTutorBlocks(answer: String): List<TutorBlock> {
    val out = mutableListOf<TutorBlock>()
    var last = 0
    for (m in DISPLAY_MATH.findAll(answer)) {
        flushParagraphs(answer.substring(last, m.range.first), out)
        val tex = (m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: m.groupValues.getOrNull(2).orEmpty()).trim()
        if (tex.isNotEmpty()) out += TutorBlock.DisplayMath(tex)
        last = m.range.last + 1
    }
    flushParagraphs(answer.substring(last), out)
    return out
}

private fun flushParagraphs(chunk: String, out: MutableList<TutorBlock>) {
    chunk.replace("\r\n", "\n").split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        .forEach { out += TutorBlock.Paragraph(it.replace(Regex("""\n+"""), " ")) }
}

fun hebrewDominant(text: String): Boolean {
    val he = text.count { it in '\u0590'..'\u05FF' }
    val la = text.count { it in 'a'..'z' || it in 'A'..'Z' }
    return he > 0 && he >= la / 2
}

@Composable
fun TutorAnswerBody(answer: String, citations: List<TutorCitation>, turnId: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (hasTutorMath(answer)) TutorMathWebAnswer(answer, hebrewDominant(answer))
        else TutorPlainAnswer(answer)
        if (citations.isNotEmpty()) TutorSources(citations, turnId)
    }
}

@Composable
private fun TutorPlainAnswer(answer: String) {
    val blocks = remember(answer) { parseTutorBlocks(answer) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is TutorBlock.Paragraph -> {
                    val rtl = hebrewDominant(block.text)
                    Text(
                        annotateCites(block.text),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is TutorBlock.DisplayMath -> TutorMathStrip(block.tex, display = true)
            }
        }
    }
}

private fun annotateCites(text: String) = buildAnnotatedString {
    var last = 0
    for (m in CITE_MARK.findAll(text)) {
        append(text.substring(last, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = androidx.compose.ui.graphics.Color(0xFF1E3A8A))) {
            append(ltr(m.value))
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TutorSources(citations: List<TutorCitation>, turnId: String) {
    var expanded by rememberSaveable(turnId) { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        citations.forEachIndexed { i, _ ->
            AssistChip(onClick = { expanded = true }, label = { Text(ltr("[${i + 1}]"), style = MaterialTheme.typography.labelMedium) })
        }
    }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(R.string.tutor_sources, ltr(citations.size)))
    }
    if (expanded) {
        citations.forEachIndexed { i, c ->
            Spacer(Modifier.height(8.dp)); HorizontalDivider()
            Text(ltr("[${i + 1}]") + " · " + stringResource(when (c.kind) {
                "transcript" -> R.string.tutor_source_transcript
                "summary" -> R.string.tutor_source_summary
                "context" -> R.string.tutor_source_context
                else -> R.string.tutor_source_slides
            }), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Text(bidi(c.label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
                Text(
                    bidi(c.quote),
                    Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDirection = if (hebrewDominant(c.quote)) TextDirection.Rtl else TextDirection.Ltr,
                    ),
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TutorMathWebAnswer(answer: String, rtl: Boolean) {
    var heightPx by remember(answer) { mutableIntStateOf(120) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fg = MaterialTheme.colorScheme.onSurface
    val fgCss = String.format("#%02X%02X%02X", (fg.red * 255).roundToInt(), (fg.green * 255).roundToInt(), (fg.blue * 255).roundToInt())
    val html = remember(answer, rtl, fgCss) { tutorAnswerHtml(answer, rtl, fgCss) }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(
                            "(function(){var h=document.body?document.body.scrollHeight:120;TutorHeight&&TutorHeight.postMessage(String(h));})();",
                        ) {}
                    }
                }
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun postMessage(h: String) {
                        h.toIntOrNull()?.takeIf { it > 40 }?.let { heightPx = it }
                    }
                }, "TutorHeight")
                loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "utf-8", null)
            }
        },
        update = { it.loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "utf-8", null) },
        modifier = Modifier.fillMaxWidth().height(with(density) { heightPx.toDp() }),
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TutorMathStrip(tex: String, display: Boolean) {
    var heightPx by remember(tex, display) { mutableIntStateOf(if (display) 64 else 36) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val html = remember(tex, display) { katexStripHtml(tex, display) }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(
                            "(function(){var h=document.body?document.body.scrollHeight:40;StripHeight&&StripHeight.postMessage(String(h));})();",
                        ) {}
                    }
                }
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun postMessage(h: String) {
                        h.toIntOrNull()?.takeIf { it > 20 }?.let { heightPx = it }
                    }
                }, "StripHeight")
                loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth().height(with(density) { heightPx.toDp() }),
    )
}

internal fun tutorAnswerHtml(answer: String, rtl: Boolean, color: String): String {
    val blocks = parseTutorBlocks(answer)
    val body = buildString {
        for (b in blocks) {
            when (b) {
                is TutorBlock.Paragraph -> {
                    val esc = htmlEsc(b.text)
                        .replace(CITE_MARK) { m -> """<span class="cite">${m.value}</span>""" }
                        .replace(INLINE_MATH) { m ->
                            val tex = m.groupValues[1].ifEmpty { m.groupValues[2] }
                            """<span class="math">\(${htmlEsc(tex)}\)</span>"""
                        }
                    append("""<p>$esc</p>""")
                }
                is TutorBlock.DisplayMath -> append("""<div class="display">$$${htmlEsc(b.tex)}$$</div>""")
            }
        }
    }
    val dir = if (rtl) "rtl" else "ltr"
    return """<!DOCTYPE html><html dir="$dir"><head><meta charset="utf-8"/>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"></script>
<style>
body{margin:0;padding:0;background:transparent;color:$color;font:15px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans Hebrew",Arial,sans-serif;word-wrap:break-word}
p{margin:0 0 0.75em}p:last-child{margin-bottom:0}
.cite{display:inline-block;direction:ltr;unicode-bidi:isolate;font-weight:700;font-family:ui-monospace,monospace;color:#1E3A8A;background:#EEF2FF;border-radius:999px;padding:0 6px;margin:0 2px;font-size:12px}
.math,.katex,.display{direction:ltr;unicode-bidi:isolate}
.display{text-align:center;margin:0.6em 0;overflow-x:auto}
</style></head><body>$body
<script>
document.addEventListener("DOMContentLoaded",function(){
  try{renderMathInElement(document.body,{delimiters:[{left:"$$",right:"$$",display:true},{left:"\\\\[",right:"\\\\]",display:true},{left:"\\\\(",right:"\\\\)",display:false},{left:"$",right:"$",display:false}],throwOnError:false});}catch(e){}
  setTimeout(function(){if(window.TutorHeight)TutorHeight.postMessage(String(document.body.scrollHeight));},50);
});
</script></body></html>"""
}

private fun katexStripHtml(tex: String, display: Boolean): String {
    val mode = if (display) "true" else "false"
    return """<!DOCTYPE html><html><head><meta charset="utf-8"/>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
<style>body{margin:0;padding:4px 0;background:transparent;text-align:center;direction:ltr}</style></head>
<body><div id="m"></div>
<script>
try{katex.render(${jsonStr(tex)},document.getElementById("m"),{displayMode:$mode,throwOnError:false});}catch(e){document.getElementById("m").textContent=${jsonStr(tex)};}
setTimeout(function(){if(window.StripHeight)StripHeight.postMessage(String(document.body.scrollHeight));},40);
</script></body></html>"""
}

private fun htmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
private fun jsonStr(s: String) = buildString {
    append('"')
    s.forEach { c ->
        when (c) {
            '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}
