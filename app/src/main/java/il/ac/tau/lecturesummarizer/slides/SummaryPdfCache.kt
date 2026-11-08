package il.ac.tau.lecturesummarizer.slides

import il.ac.tau.lecturesummarizer.data.SummaryDensity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** On-disk PDFs survive process death. Shared locks coalesce preloads and taps into ONE download. */
class SummaryPdfCache(private val dir:File,private val id:String) {
 fun file(density:String)=File(dir,"$id-$density.pdf")
 fun available()=SummaryDensity.entries.filter{file(it.api).let{f->f.isFile&&f.length()>0}}.map{it.api}.toSet()
 fun density(file:File?)=SummaryDensity.entries.firstOrNull{file?.name=="$id-${it.api}.pdf"}?.api
 suspend fun ensure(density:String,download:suspend(File)->Unit):File=withContext(Dispatchers.IO){
  val file=file(density);val lock=synchronized(locks){locks.getOrPut(file.absolutePath){Mutex()}}
  lock.withLock { if(!file.isFile||file.length()==0L){download(file);check(file.isFile&&file.length()>0){"summary_download_empty"}};file }
 }
 companion object { private val locks=mutableMapOf<String,Mutex>() }
}
