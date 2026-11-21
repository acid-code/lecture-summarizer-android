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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 var bitmap by remember(file?.absolutePath,file?.lastModified(),file?.length()){mutableStateOf(PdfPreviewCache.peek(file))}
 LaunchedEffect(file?.absolutePath,file?.lastModified(),file?.length()){
  bitmap=if(file?.exists()==true)PdfPreviewCache.preload(file) else null
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

private fun densityIcon(d:SummaryDensity)=when(d){
 SummaryDensity.COMPACT->Icons.Default.ShortText
 SummaryDensity.STANDARD->Icons.Default.Description
 SummaryDensity.DETAILED->Icons.Default.AutoStories
 SummaryDensity.SLIDES->Icons.Default.Slideshow
}

@Composable fun SummaryHero(file:File?,available:Set<String>,selected:String?,onSelect:(SummaryDensity)->Unit,onOpen:()->Unit,
 onShare:()->Unit={},onSave:()->Unit={},hasSlides:Boolean=true,
 updating:Set<String> = emptySet(),loading:Set<String> = emptySet(),failed:Set<String> = emptySet(),canGenerate:Boolean=true,onGenerate:(SummaryDensity)->Unit={},levelNote:@Composable (SummaryDensity)->Unit={}){
 var generate by remember{mutableStateOf<SummaryDensity?>(null)}
 val selectedDensity=SummaryDensity.of(selected)
 val pdfReady=file?.exists()==true
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  PdfHeroPreview(file)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
   SummaryDensity.entries.forEach{d->
    val busy=d.api in updating;val fetching=d.api in loading
    FilterChip(selected=selected==d.api,enabled=!busy,onClick={if(d.api in available)onSelect(d)else generate=d},
     modifier=Modifier.weight(1f).testTag("summary-variant:${d.api}"),
     label={
      when{
       busy||fetching->CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
       d.api in failed->Icon(Icons.Default.Error,densityLabel(d),Modifier.size(20.dp),tint=MaterialTheme.colorScheme.error)
       selected==d.api->Icon(Icons.Default.Check,densityLabel(d),Modifier.size(20.dp))
       d.api !in available->Icon(Icons.Default.Add,densityLabel(d),Modifier.size(20.dp))
       else->Icon(densityIcon(d),densityLabel(d),Modifier.size(20.dp))
      }
     })
   }
  }
  selectedDensity?.let{Text(stringResource(R.string.summary_selected_level,densityLabel(it)),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
  val shareDesc=stringResource(R.string.share_pdf)
  val saveDesc=stringResource(R.string.copy_save_pdf)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
   Button(enabled=pdfReady,onClick=onOpen,modifier=Modifier.weight(1f).testTag("pdf-action-open")){Text(stringResource(R.string.pdf_action_open))}
   OutlinedButton(enabled=pdfReady,onClick=onShare,modifier=Modifier.testTag("pdf-action-share").semantics{contentDescription=shareDesc},contentPadding=PaddingValues(horizontal=14.dp,vertical=10.dp)){
    Text("📤", style=MaterialTheme.typography.titleMedium)
   }
   OutlinedButton(enabled=pdfReady,onClick=onSave,modifier=Modifier.testTag("pdf-action-save").semantics{contentDescription=saveDesc},contentPadding=PaddingValues(horizontal=14.dp,vertical=10.dp)){
    Text("💾", style=MaterialTheme.typography.titleMedium)
   }
  }
 }
 generate?.let{d->
  val slidesBlocked=d==SummaryDensity.SLIDES && !hasSlides
  val body=when{
   slidesBlocked->stringResource(R.string.summary_generate_slides_need)
   canGenerate->stringResource(R.string.summary_generate_variant_body,densityLabel(d))+"\n\n"+stringResource(d.blurbRes)
   else->stringResource(R.string.summary_generate_unavailable,densityLabel(d))
  }
  AlertDialog(onDismissRequest={generate=null},title={Text(stringResource(R.string.summary_generate_variant)+" · "+densityLabel(d))},
   text={Text(body)},
   confirmButton={TextButton(enabled=canGenerate&&!slidesBlocked,onClick={generate=null;onGenerate(d)}){Text(stringResource(R.string.summary_generate_confirm))}},
   dismissButton={TextButton(onClick={generate=null}){Text(stringResource(R.string.copy_cancel))}})
 }
}
