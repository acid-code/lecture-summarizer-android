package il.ac.tau.lecturesummarizer.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.study.TutorCitation
import java.io.File

/** Full-screen swipeable source pages opened from tutor cite chips / sources list. */
@Composable
fun TutorSourceViewer(
    citations: List<TutorCitation>,
    startIndex: Int,
    summaryPdf: File?,
    slideUris: List<String>,
    onBack: () -> Unit,
) {
    if (citations.isEmpty()) {
        onBack()
        return
    }
    val start = startIndex.coerceIn(0, citations.lastIndex)
    val pager = rememberPagerState(initialPage = start, pageCount = { citations.size })
    val pageTitle = stringResource(
        R.string.tutor_source_page,
        ltr((pager.currentPage + 1).toString()),
        ltr(citations.size.toString()),
    )
    AppScaffold(pageTitle, onBack = onBack) { pad ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().padding(pad),
            beyondViewportPageCount = 1,
        ) { page ->
            val c = citations[page]
            TutorSourcePage(
                index = page,
                citation = c,
                pdfFile = resolveTutorPdf(c.kind, summaryPdf, slideUris),
            )
        }
    }
}

@Composable
private fun TutorSourcePage(index: Int, citation: TutorCitation, pdfFile: File?) {
    val kind = stringResource(
        when (citation.kind) {
            "transcript" -> R.string.tutor_source_transcript
            "summary" -> R.string.tutor_source_summary
            "context" -> R.string.tutor_source_context
            else -> R.string.tutor_source_slides
        },
    )
    val quote = Regex("""\[\d{1,2}\]""").replace(citation.quote, "").trim()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "[${index + 1}] · $kind",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, textDirection = TextDirection.Content),
        )
        if (citation.label.isNotBlank()) {
            Text(bidi(citation.label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (pdfFile != null) {
            TutorPdfScroll(pdfFile, Modifier.weight(1f).fillMaxWidth())
            if (quote.isNotBlank()) {
                Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        bidi(quote),
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDirection = if (hebrewDominant(quote)) TextDirection.Rtl else TextDirection.Ltr,
                        ),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        bidi(quote),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            textDirection = if (hebrewDominant(quote)) TextDirection.Rtl else TextDirection.Ltr,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun TutorPdfScroll(file: File, modifier: Modifier = Modifier) {
    var pages by remember(file.absolutePath, file.length()) { mutableStateOf<List<Bitmap>>(emptyList()) }
    DisposableEffect(file.absolutePath, file.length()) {
        pages = renderTutorPdfPages(file)
        onDispose { pages.forEach { it.recycle() }; pages = emptyList() }
    }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        items(pages.size) { i ->
            Image(pages[i].asImageBitmap(), contentDescription = stringResource(R.string.pdf_preview), modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun renderTutorPdfPages(file: File, maxPages: Int = 40): List<Bitmap> {
    if (!file.isFile || file.length() == 0L) return emptyList()
    return runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val n = minOf(renderer.pageCount, maxPages)
                (0 until n).map { i ->
                    renderer.openPage(i).use { page ->
                        val w = 1080
                        val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, 2400)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(android.graphics.Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }
    }.getOrDefault(emptyList())
}

internal fun resolveTutorPdf(kind: String, summaryPdf: File?, slideUris: List<String>): File? {
    when (kind) {
        "summary" -> summaryPdf?.takeIf { it.isFile && it.length() > 0 }?.let { return it }
        "slides" -> {
            slideUris.forEach { u ->
                val path = runCatching {
                    if (u.startsWith("file:")) Uri.parse(u).path else if (u.startsWith("/")) u else null
                }.getOrNull()
                path?.let { File(it).takeIf { f -> f.isFile && f.length() > 0 && f.name.endsWith(".pdf", true) } }?.let { return it }
            }
            summaryPdf?.takeIf { it.isFile && it.length() > 0 }?.let { return it }
        }
    }
    return null
}
