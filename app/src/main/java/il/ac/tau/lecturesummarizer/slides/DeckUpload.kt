package il.ac.tau.lecturesummarizer.slides

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Opens a local deck for API upload; PPTX is converted to a cached PDF when possible. */
object DeckUpload {
    data class Open(
        val name: String,
        val mime: String,
        val stream: InputStream,
    )

    /**
     * Prefer the original `.pptx` on the wire so the server can:
     *  - extract real slide text for commentary ([pptxToText])
     *  - composite positioned pictures for figures (LibreOffice is unavailable on Vercel)
     * Raster PDF conversion is kept in [PptxToPdf] for offline/tests, but uploading that PDF
     * previously wiped OCR text and baked broken text overlays into every page.
     */
    fun open(ctx: Context, uri: Uri, displayName: String): Open {
        val name = displayName.ifBlank { "slides.bin" }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext != "pptx") {
            val mime = ctx.contentResolver.getType(uri)
                ?: if (ext == "pdf") "application/pdf" else "application/octet-stream"
            val stream = ctx.contentResolver.openInputStream(uri)
                ?: error("cannot open deck")
            return Open(name, mime, stream)
        }
        val pptx = materialize(ctx, uri) ?: run {
            val stream = ctx.contentResolver.openInputStream(uri) ?: error("cannot open deck")
            return Open(name, pptxMime, stream)
        }
        return Open(name, pptxMime, pptx.inputStream())
    }

    private const val pptxMime = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    private fun materialize(ctx: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.isFile }
        // FileProvider / content: copy into cache once keyed by uri string.
        val dir = File(ctx.cacheDir, "pptx-src").apply { mkdirs() }
        val dest = File(dir, fingerprint(uri.toString()) + ".pptx")
        if (dest.isFile && dest.length() > 0) return dest
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.takeIf { it.isFile && it.length() > 0 }
        } catch (_: Exception) {
            dest.delete()
            null
        }
    }

    private fun fingerprint(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
}
