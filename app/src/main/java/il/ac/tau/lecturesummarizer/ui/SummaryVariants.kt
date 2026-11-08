package il.ac.tau.lecturesummarizer.ui

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.slides.*
import kotlinx.coroutines.*
import java.io.File

@Composable internal fun rememberVariantState(id:String):Map<String,SummaryVariantState.Entry>{
 val context=LocalContext.current;val store=remember(context){SummaryVariantState(context)}
 var tick by remember(id){mutableIntStateOf(0)}
 val legacy=remember(context){context.getSharedPreferences("summary_material_versions",0)}
 DisposableEffect(store,id){val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key?.startsWith("$id:")==true)tick++};store.preferences.registerOnSharedPreferenceChangeListener(listener);val oldListener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key=="state:$id")tick++};legacy.registerOnSharedPreferenceChangeListener(oldListener);onDispose{store.preferences.unregisterOnSharedPreferenceChangeListener(listener);legacy.unregisterOnSharedPreferenceChangeListener(oldListener)}}
 return remember(id,tick){store.entries(id).ifEmpty{
  val old=legacy.getString("state:$id",null)
  if(old in setOf("queued","updating","error"))(context.applicationContext as? LectureApp)?.settings?.summaryDensities.orEmpty().associateWith{SummaryVariantState.Entry("legacy",old!!,0)} else emptyMap()
 }}
}
@Composable fun LectureSummaryVariants(app:LectureApp,l:Lecture,onOpen:()->Unit){
 val scope=rememberCoroutineScope();val state=rememberVariantState(l.id)
 val cache=remember(l.id){SummaryPdfCache(app.repo.pdfDir(),l.id)}
 val current=l.pdfPath?.let(::File)?.takeIf{it.exists()}
 var available by remember(l.id){mutableStateOf(cache.available())}
 var loading by remember(l.id){mutableStateOf(emptySet<String>())}
 var switchToken by remember(l.id){mutableIntStateOf(0)}
 var error by remember(l.id){mutableStateOf(false)}
 val ready=state.filterValues{it.state=="ready"}.mapValues{it.value.token}
 LaunchedEffect(l.remoteId,ready){
  val local=cache.available();available=available+local
  local.forEach{density->PdfPreviewCache.preload(cache.file(density))}
  val remote=l.remoteId ?: return@LaunchedEffect
  val api=ApiProvider.get(app.settings)
  // Pre-density filenames have no trustworthy selected-chip identity. Resolve once, don't guess.
  if(current!=null&&cache.density(current)==null&&l.jobId!=null){
   try{SummaryDensity.of(api.jobStatus(l.jobId).density)?.let{density->
    val dest=cache.ensure(density.api){file->withContext(Dispatchers.IO){current.copyTo(file,overwrite=false)}}
    if(app.repo.lecture(l.id)?.pdfPath==current.absolutePath)app.repo.updateLecture(l.id){it.copy(pdfPath=dest.absolutePath)}
   }}catch(e:CancellationException){throw e}catch(_:Exception){/* Keep the old PDF available offline. */}
  }
  val remoteLevels=api.availableDensities(remote).filter{SummaryDensity.of(it)!=null}.toSet()
  available=available+remoteLevels+cache.available()
  coroutineScope{remoteLevels.forEach{density->launch{
   if(density !in cache.available())loading=loading+density
   try{val file=cache.ensure(density){api.downloadSummaryPdf(remote,it,density)};il.ac.tau.lecturesummarizer.study.StudyStore(app).delivered(l.id,file,density,l.jobId.orEmpty());PdfPreviewCache.preload(file)}
   catch(e:CancellationException){throw e}catch(_:Exception){/* A tap can retry, without generating over an existing version. */}
   finally{loading=loading-density}
  }}}
 }
 SummaryHero(current,available,cache.density(current),onSelect={density->
  val request=++switchToken;val local=cache.file(density.api)
  if(local.isFile&&local.length()>0){app.repo.updateLecture(l.id){it.copy(pdfPath=local.absolutePath)};error=false}
  else scope.launch{
   loading=loading+density.api
   try{val file=cache.ensure(density.api){ApiProvider.get(app.settings).downloadSummaryPdf(l.remoteId!!,it,density.api)};PdfPreviewCache.preload(file)
    if(request==switchToken){app.repo.updateLecture(l.id){it.copy(pdfPath=file.absolutePath)};error=false}
   }catch(e:CancellationException){throw e}catch(_:Exception){error=true}finally{loading=loading-density.api}
  }
 },onOpen=onOpen,updating=state.filterValues{it.pending}.keys,loading=loading,canGenerate=l.remoteId!=null&&l.jobId!=null,
 onGenerate={SummaryRefreshWorker.enqueueDensity(app,l.id,it.api)},failed=state.filterValues{it.state=="error"}.keys)
 val failed=state.filterValues{it.state=="error"}.keys
 if(failed.isNotEmpty())androidx.compose.foundation.layout.Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
  androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.summary_update_failed),modifier=androidx.compose.ui.Modifier.weight(1f),style=androidx.compose.material3.MaterialTheme.typography.bodySmall,color=androidx.compose.material3.MaterialTheme.colorScheme.error)
  androidx.compose.material3.TextButton(onClick={failed.forEach{SummaryRefreshWorker.enqueueDensity(app,l.id,it)}}){androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.retry))}
 }
 if(error)androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.summary_download_failed),color=androidx.compose.material3.MaterialTheme.colorScheme.error)
}
