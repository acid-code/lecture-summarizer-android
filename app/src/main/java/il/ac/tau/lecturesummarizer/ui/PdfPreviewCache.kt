package il.ac.tau.lecturesummarizer.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep ready-to-display first-page renders warm when switching summary variants. */
internal object PdfPreviewCache {
 private val lock=Mutex()
 private val cache=object:LruCache<String,Bitmap>(12*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
 private fun key(file:File)="${file.absolutePath}:${file.lastModified()}:${file.length()}"
 fun peek(file:File?)=file?.let{cache.get(key(it))}
 suspend fun preload(file:File):Bitmap?=withContext(Dispatchers.IO){lock.withLock{
  peek(file) ?: runCatching {
   ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{renderer->renderer.openPage(0).use{page->
    Bitmap.createBitmap(480,(480f*page.height/page.width).toInt().coerceIn(1,1000),Bitmap.Config.ARGB_8888).also{it.eraseColor(android.graphics.Color.WHITE);page.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}
   }}}.also{cache.put(key(file),it)}
  }.onFailure{android.util.Log.w("SummaryPreview","PDF preview unavailable",it)}.getOrNull()
 }}
}
