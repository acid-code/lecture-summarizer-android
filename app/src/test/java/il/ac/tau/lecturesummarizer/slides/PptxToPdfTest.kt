package il.ac.tau.lecturesummarizer.slides

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PptxToPdfTest {
    @Test fun convertsPptxWithEmbeddedPngToPdf() {
        val pptx = File.createTempFile("deck", ".pptx")
        val pdf = File.createTempFile("deck", ".pdf")
        try {
            writeMinimalPptx(pptx, withImage = true)
            val ok = PptxToPdf.convert(pptx, pdf)
            assertTrue("convert failed: ${PptxToPdf.lastError}", ok)
            assertTrue(pdf.length() > 500)
            // PDF header
            assertEquals('%'.code.toByte(), pdf.readBytes()[0])
            assertEquals('P'.code.toByte(), pdf.readBytes()[1])
        } finally {
            pptx.delete(); pdf.delete()
        }
    }

    @Test fun convertsTextOnlyPptx() {
        val pptx = File.createTempFile("text-deck", ".pptx")
        val pdf = File.createTempFile("text-deck", ".pdf")
        try {
            writeMinimalPptx(pptx, withImage = false)
            val ok = PptxToPdf.convert(pptx, pdf)
            assertTrue("convert failed: ${PptxToPdf.lastError}", ok)
            assertTrue(pdf.length() > 200)
        } finally {
            pptx.delete(); pdf.delete()
        }
    }

    @Test fun rejectsNonPptx() {
        val f = File.createTempFile("notes", ".pdf")
        val out = File.createTempFile("out", ".pdf")
        try {
            f.writeText("%PDF-1.4")
            assertFalse(PptxToPdf.convert(f, out))
        } finally {
            f.delete(); out.delete()
        }
    }

    private fun writeMinimalPptx(dest: File, withImage: Boolean) {
        ZipOutputStream(dest.outputStream()).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
            put(
                "ppt/presentation.xml",
                """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                   <p:sldIdLst><p:sldId r:id="rId1"/></p:sldIdLst>
                   <p:sldSz cx="12192000" cy="6858000"/></p:presentation>"""
            )
            put(
                "ppt/_rels/presentation.xml.rels",
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                   <Relationship Id="rId1" Target="slides/slide1.xml"/></Relationships>"""
            )
            if (withImage) {
                put(
                    "ppt/slides/slide1.xml",
                    """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                       xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                       <p:cSld><p:spTree>
                         <p:pic><p:blipFill><a:blip r:embed="rIdImg"/></p:blipFill>
                           <p:spPr><a:xfrm><a:off x="914400" y="914400"/><a:ext cx="4572000" cy="3429000"/></a:xfrm></p:spPr>
                         </p:pic>
                         <p:sp><p:txBody><a:p><a:r><a:t>Hello diagram</a:t></a:r></a:p></p:txBody></p:sp>
                       </p:spTree></p:cSld></p:sld>"""
                )
                put(
                    "ppt/slides/_rels/slide1.xml.rels",
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                       <Relationship Id="rIdImg" Target="../media/image1.png"/></Relationships>"""
                )
                z.putNextEntry(ZipEntry("ppt/media/image1.png"))
                z.write(tinyPng())
                z.closeEntry()
            } else {
                put(
                    "ppt/slides/slide1.xml",
                    """<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                       <a:t>Only text slide</a:t></p:sld>"""
                )
            }
        }
    }

    private fun tinyPng(): ByteArray {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.BLUE)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
