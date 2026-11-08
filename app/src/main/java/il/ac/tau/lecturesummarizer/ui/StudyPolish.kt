package il.ac.tau.lecturesummarizer.ui

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.data.*
import kotlinx.coroutines.*
import java.io.File

fun audioLike(ctx:Context,uri:Uri):Boolean{
 val mime=ctx.contentResolver.getType(uri).orEmpty();val name=displayName(ctx,uri.toString())
 return mime.startsWith("audio/") || mime.startsWith("video/") || name.substringAfterLast('.').lowercase() in setOf("m4a","mp3","wav","aac","ogg","mp4","webm","flac")
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AddLectureSheet(onDismiss:()->Unit,onRecord:()->Unit,onPanopto:()->Unit,onUpload:(List<Uri>)->Unit){
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty())onUpload(it)}
 ModalBottomSheet(onDismissRequest=onDismiss){Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text(stringResource(R.string.add_lecture),style=MaterialTheme.typography.titleLarge)
  FilledTonalButton(onClick=onRecord,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Mic,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.new_record))}
  OutlinedButton(onClick=onPanopto,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.OndemandVideo,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.import_panopto))}
  OutlinedButton(onClick={picker.launch(arrayOf("audio/*","video/*","application/pdf","application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.presentationml.presentation"))},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.UploadFile,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.new_files))}
  TextButton(onClick=onDismiss,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.copy_cancel))}
 }}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CourseActionsSheet(name:String,onDismiss:()->Unit,onCustomize:()->Unit,onDelete:()->Unit){
 ModalBottomSheet(onDismissRequest=onDismiss){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text(bidi(name),style=MaterialTheme.typography.titleLarge)
  TextButton(onClick=onCustomize){Icon(Icons.Default.Palette,null);Text(stringResource(R.string.customize))}
  TextButton(onClick=onDelete){Icon(Icons.Default.Delete,null,tint=MaterialTheme.colorScheme.error);Text(stringResource(R.string.delete_course),color=MaterialTheme.colorScheme.error)}
 }}
}
fun processingStep(l:Lecture)=when{
 l.state==LectureState.UPLOADING->0
 l.state==LectureState.DONE->4
 l.stage==null || l.stage in setOf("asr","queued","asr_wait","uploaded","normalized","transcribed","repair")->1
 else->3 // Slides are an optional suggestion, never a mandatory pipeline blocker.
}
@Composable fun ProcessingSteps(l:Lecture){
 val active=processingStep(l)
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  listOf(R.string.step_upload,R.string.step_transcript,R.string.step_slides,R.string.step_summary).forEachIndexed{i,label->
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    val completed=active>i && i!=2
    Icon(if(completed)Icons.Default.CheckCircle else if(i==2)Icons.Default.AutoStories else if(active==i)Icons.Default.Pending else Icons.Default.RadioButtonUnchecked,null,Modifier.size(18.dp),tint=if(completed||active==i)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(label),style=MaterialTheme.typography.labelMedium)
    if(i==2)Text(stringResource(R.string.step_optional),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
 }
}
@Composable fun PdfHeroPreview(file:File?,modifier:Modifier=Modifier){
 var bitmap by remember(file?.absolutePath,file?.lastModified()){mutableStateOf<Bitmap?>(null)}
 LaunchedEffect(file?.absolutePath,file?.lastModified()){
  bitmap=withContext(Dispatchers.IO){if(file?.exists()!=true)null else runCatching{
   ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{renderer->renderer.openPage(0).use{page->
    Bitmap.createBitmap(480,(480f*page.height/page.width).toInt().coerceIn(1,1000),Bitmap.Config.ARGB_8888).also{it.eraseColor(android.graphics.Color.WHITE);page.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}
   }}}
  }.onFailure{android.util.Log.w("SummaryPreview","PDF preview unavailable",it)}.getOrNull()}
 }
 Box(modifier.fillMaxWidth().height(190.dp).background(MaterialTheme.colorScheme.surfaceContainerLow,RoundedCornerShape(16.dp)).border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center){
  bitmap?.let{Image(it.asImageBitmap(),stringResource(R.string.pdf_preview),Modifier.fillMaxSize().padding(8.dp).testTag("pdf-preview-page"))} ?: Icon(Icons.Default.PictureAsPdf,stringResource(R.string.pdf_preview),Modifier.size(56.dp),tint=MaterialTheme.colorScheme.primary)
 }
}

fun shareStudyPdf(ctx:Context,app:il.ac.tau.lecturesummarizer.LectureApp,l:Lecture){
 val file=l.pdfPath?.let(::File)?.takeIf{it.exists()} ?: return
 val name=summaryPdfName(app.repo.course(l.courseId)?.name,l.title)
 val dir=File(ctx.filesDir,"shared/pdf").apply{mkdirs()};val named=File(dir,name);file.copyTo(named,overwrite=true)
 val uri=androidx.core.content.FileProvider.getUriForFile(ctx,ctx.packageName+".files",named)
 safeStart(ctx,android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/pdf").putExtra(android.content.Intent.EXTRA_STREAM,uri).putExtra(android.content.Intent.EXTRA_SUBJECT,name.removeSuffix(".pdf")).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),ctx.getString(R.string.share_pdf)))
}
@Composable fun SummaryChangeNote(app:il.ac.tau.lecturesummarizer.LectureApp,l:Lecture){
 var tick by remember{mutableIntStateOf(0)}
 DisposableEffect(l.id){val p=app.getSharedPreferences("summary_material_versions",0);val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,_->tick++};p.registerOnSharedPreferenceChangeListener(listener);onDispose{p.unregisterOnSharedPreferenceChangeListener(listener)}}
 val f=remember{il.ac.tau.lecturesummarizer.slides.SummaryFreshness(app)}
 val note=remember(tick,l.id){f.changeNote(l.id)?.let{runCatching{org.json.JSONObject(it)}.getOrNull()}}
 if(note!=null && note.optString("revision")==f.revision(l))OutlinedCard{Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
  Text(stringResource(R.string.summary_changed_title),style=MaterialTheme.typography.titleSmall)
  Text(stringResource(R.string.summary_changed_body,note.optInt("count")),style=MaterialTheme.typography.bodySmall)
  Text(note.optString("levels").split(',').mapNotNull{api->SummaryDensity.entries.find{it.api==api}}.joinToString(" · "){app.getString(it.labelRes)},style=MaterialTheme.typography.labelSmall)
 }}
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable fun SummaryHero(file:File?,available:Set<String>,selected:String?,onSelect:(SummaryDensity)->Unit,onOpen:()->Unit,levelNote:@Composable (SummaryDensity)->Unit={}){
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  PdfHeroPreview(file)
  FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   SummaryDensity.entries.filter{it.api in setOf("standard","detailed") || it.api in available}.forEach{d->FilterChip(selected=selected==d.api,enabled=d.api in available,onClick={onSelect(d)},label={Column{Text(densityLabel(d));levelNote(d)}})}
  }
  Button(enabled=file?.exists()==true,onClick=onOpen,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.OpenInNew,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.open_summary))}
 }
}
