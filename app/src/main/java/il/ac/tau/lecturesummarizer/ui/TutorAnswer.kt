package il.ac.tau.lecturesummarizer.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
private val RAW_UUID = Regex("""\[?\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?::[a-z]:\d+)?\b\]?""", RegexOption.IGNORE_CASE)

fun hasTutorMath(answer: String): Boolean = DISPLAY_MATH.containsMatchIn(answer) || INLINE_MATH.containsMatchIn(answer)

/** Client-side safety net mirroring API normalizeAnswerMarkers. */
fun sanitizeTutorAnswer(answer: String, citations: List<TutorCitation>): String {
    var out = answer
    citations.mapIndexed { i, c -> c.sourceId to (i + 1) }
        .filter { it.first.isNotBlank() }
        .sortedByDescending { it.first.length }
        .forEach { (id, n) ->
            out = out.replace(Regex("""\[?\b${Regex.escape(id)}\b\]?""", RegexOption.IGNORE_CASE), "[$n]")
        }
    out = RAW_UUID.replace(out) { m ->
        val bare = m.value.trim('[', ']')
        val hit = citations.indexOfFirst {
            it.sourceId.equals(bare, true) ||
                bare.endsWith(it.sourceId, true) ||
                it.sourceId.endsWith(bare, true)
        }
        if (hit >= 0) "[${hit + 1}]" else ""
    }
    out = out.replace(Regex("""[ \t]{2,}"""), " ").replace(Regex("""\n{3,}"""), "\n\n").trim()
    val missing = citations.indices.filter { i -> !Regex("""\[${i + 1}\]""").containsMatchIn(out) }.map { it + 1 }
    if (missing.isNotEmpty()) out = (out.trimEnd() + " " + missing.joinToString(" ") { "[$it]" }).trim()
    return out
}

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
fun TutorAnswerBody(
    answer: String,
    citations: List<TutorCitation>,
    turnId: String,
    onOpenSource: (Int) -> Unit = {},
) {
    val clean = remember(answer, citations) { sanitizeTutorAnswer(answer, citations) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (hasTutorMath(clean)) TutorMathWebAnswer(clean, hebrewDominant(clean), onOpenSource)
        else TutorPlainAnswer(clean, onOpenSource)
        if (citations.isNotEmpty()) TutorSources(citations, turnId, onOpenSource)
    }
}

@Composable
private fun TutorPlainAnswer(answer: String, onOpenSource: (Int) -> Unit) {
    val blocks = remember(answer) { parseTutorBlocks(answer) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is TutorBlock.Paragraph -> {
                    val rtl = hebrewDominant(block.text)
                    val annotated = remember(block.text) { annotateCites(block.text) }
                    ClickableText(
                        text = annotated,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { offset ->
                            annotated.getStringAnnotations("cite", offset, offset)
                                .firstOrNull()
                                ?.item
                                ?.toIntOrNull()
                                ?.let { onOpenSource(it) }
                        },
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
        val idx = (m.groupValues[1].toIntOrNull() ?: 1) - 1
        pushStringAnnotation("cite", idx.coerceAtLeast(0).toString())
        withStyle(
            SpanStyle(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = androidx.compose.ui.graphics.Color(0xFF1E3A8A),
                background = androidx.compose.ui.graphics.Color(0xFFEEF2FF),
            ),
        ) { append(ltr(m.value)) }
        pop()
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TutorSources(citations: List<TutorCitation>, turnId: String, onOpenSource: (Int) -> Unit) {
    var expanded by rememberSaveable(turnId) { mutableStateOf(false) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.tutor_sources, ltr(citations.size)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.tutor_sources_hide else R.string.tutor_sources_show))
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            citations.forEachIndexed { i, _ ->
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.clickable { onOpenSource(i) },
                ) {
                    Text(
                        "[${i + 1}]",
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            textDirection = TextDirection.Ltr,
                        ),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                citations.forEachIndexed { i, c ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth().clickable { onOpenSource(i) },
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(
                                        "[${i + 1}]",
                                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            textDirection = TextDirection.Ltr,
                                        ),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                                Text(
                                    stringResource(
                                        when (c.kind) {
                                            "transcript" -> R.string.tutor_source_transcript
                                            "summary" -> R.string.tutor_source_summary
                                            "context" -> R.string.tutor_source_context
                                            else -> R.string.tutor_source_slides
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (c.label.isNotBlank()) {
                                Text(bidi(c.label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val quote = CITE_MARK.replace(c.quote, "").trim()
                            Text(
                                bidi(quote),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    textDirection = if (hebrewDominant(quote)) TextDirection.Rtl else TextDirection.Ltr,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TutorMathWebAnswer(answer: String, rtl: Boolean, onOpenSource: (Int) -> Unit) {
    var heightPx by remember(answer) { mutableIntStateOf(140) }
    val density = LocalDensity.current
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
                            "(function(){var h=document.body?document.body.scrollHeight:140;if(window.TutorHeight)TutorHeight.postMessage(String(h));" +
                                "document.querySelectorAll('.cite').forEach(function(el){el.onclick=function(){if(window.TutorCite)TutorCite.postMessage(el.getAttribute('data-i')||'0');};});})();",
                        ) {}
                    }
                }
                addJavascriptInterface(object {
                    @JavascriptInterface fun postMessage(h: String) {
                        h.toIntOrNull()?.takeIf { it > 40 }?.let { heightPx = it + 8 }
                    }
                }, "TutorHeight")
                addJavascriptInterface(object {
                    @JavascriptInterface fun postMessage(i: String) {
                        val idx = i.toIntOrNull() ?: return
                        post { onOpenSource(idx) }
                    }
                }, "TutorCite")
                loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            view.loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "utf-8", null)
        },
        modifier = Modifier.fillMaxWidth().height(with(density) { heightPx.toDp() }),
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TutorMathStrip(tex: String, display: Boolean) {
    var heightPx by remember(tex, display) { mutableIntStateOf(if (display) 72 else 40) }
    val density = LocalDensity.current
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
                            "(function(){var h=document.body?document.body.scrollHeight:40;if(window.StripHeight)StripHeight.postMessage(String(h));})();",
                        ) {}
                    }
                }
                addJavascriptInterface(object {
                    @JavascriptInterface fun postMessage(h: String) {
                        h.toIntOrNull()?.takeIf { it > 20 }?.let { heightPx = it + 4 }
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
                        .replace(CITE_MARK) { m ->
                            val i = ((m.groupValues[1].toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
                            """<span class="cite" data-i="$i">${m.value}</span>"""
                        }
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
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"></script>
<style>
body{margin:0;padding:2px 0 6px;background:transparent;color:$color;font:16px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans Hebrew",Arial,sans-serif;word-wrap:break-word}
p{margin:0 0 0.9em}p:last-child{margin-bottom:0}
.cite{display:inline-block;direction:ltr;unicode-bidi:isolate;font-weight:700;font-family:ui-monospace,Menlo,Consolas,monospace;color:#1E3A8A;background:#EEF2FF;border-radius:999px;padding:1px 7px;margin:0 3px;font-size:12px;line-height:1.4;cursor:pointer}
.math,.katex,.display{direction:ltr;unicode-bidi:isolate}
.display{text-align:center;margin:0.75em 0;overflow-x:auto;padding:4px 0}
</style></head><body>$body
<script>
document.addEventListener("DOMContentLoaded",function(){
  try{renderMathInElement(document.body,{delimiters:[{left:"$$",right:"$$",display:true},{left:"\\\\[",right:"\\\\]",display:true},{left:"\\\\(",right:"\\\\)",display:false},{left:"$",right:"$",display:false}],throwOnError:false});}catch(e){}
  setTimeout(function(){if(window.TutorHeight)TutorHeight.postMessage(String(document.body.scrollHeight));},80);
});
</script></body></html>"""
}

private fun katexStripHtml(tex: String, display: Boolean): String {
    val mode = if (display) "true" else "false"
    return """<!DOCTYPE html><html><head><meta charset="utf-8"/>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
<style>body{margin:0;padding:6px 0;background:transparent;text-align:center;direction:ltr}</style></head>
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
