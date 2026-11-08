package il.ac.tau.lecturesummarizer.ui

import androidx.compose.ui.graphics.graphicsLayer

import java.io.File

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.study.*
import kotlinx.coroutines.*
import kotlin.random.Random

@Composable fun StudyToolsEntry(onMemory:()->Unit,onTutor:()->Unit){
 SectionCard(stringResource(R.string.study_tools),Icons.Default.School){
   Text(stringResource(R.string.study_tools_body),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   TonalAction(stringResource(R.string.memory_title),Icons.Default.GridView,Modifier.fillMaxWidth(),onClick=onMemory)
   OutlineAction(stringResource(R.string.tutor_title),Icons.Default.ChatBubbleOutline,Modifier.fillMaxWidth(),onClick=onTutor)
 }
}
private fun densityOf(l:Lecture)=SummaryDensity.entries.firstOrNull{l.pdfPath?.endsWith("-${it.api}.pdf")==true}?.api
@Composable fun MemoryScreen(app:LectureApp,id:String,onBack:()->Unit){
 val lectures by app.repo.lectures.collectAsStateWithLifecycle();val l=lectures.firstOrNull{it.id==id}?:return
 val store=remember{StudyStore(app)};var cache by remember(id){mutableStateOf<CachedConcepts?>(null)}
 var busy by remember{mutableStateOf(true)};var error by remember{mutableStateOf(false)};val scope=rememberCoroutineScope();val online=rememberOnline().first
 LaunchedEffect(id,l.pdfPath){cache=withContext(Dispatchers.IO){l.pdfPath?.let{store.delivered(id,File(it),densityOf(l) ?: "standard",l.jobId.orEmpty())};store.cached(id,densityOf(l))};busy=false}
 fun reload(){if(busy||l.remoteId==null)return;scope.launch{busy=true;error=false;try{withContext(Dispatchers.IO){store.refresh(ApiProvider.get(app.settings),id,l.remoteId,densityOf(l),l.jobId.orEmpty())};cache=withContext(Dispatchers.IO){store.cached(id,densityOf(l))}}catch(e:CancellationException){throw e}catch(_:Exception){error=true}finally{busy=false}}}
 AppScaffold(stringResource(R.string.memory_title),onBack=onBack,actions={if(cache!=null)IconButton(enabled=!busy&&online&&l.remoteId!=null,onClick={reload()}){Icon(Icons.Default.Download,stringResource(R.string.memory_update))}}){pad->
   if(cache?.entries?.size?.let{it>=1}==true)Column(Modifier.padding(pad).fillMaxSize()){
     if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
     if(error)Text(stringResource(R.string.memory_load_error),Modifier.padding(horizontal=20.dp),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
     MemoryGameContent(l.title,cache!!.entries,cache!!.fingerprint,Modifier.weight(1f))
   }
   else MemoryAvailabilityContent(l.title,cache!=null,busy,error,online,l.remoteId!=null,Modifier.padding(pad)){reload()}
 }
}
/** The same empty/loading/offline content is used by the runtime and screenshot fixtures. */
@Composable fun MemoryAvailabilityContent(title:String,hasCache:Boolean,busy:Boolean,error:Boolean,online:Boolean,hasRemote:Boolean,modifier:Modifier=Modifier,onDownload:()->Unit){
 Column(modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
   Text(bidi(title),style=MaterialTheme.typography.titleLarge)
   if(busy)SkeletonLoader()
   else SectionCard(stringResource(if(hasCache)R.string.memory_empty_title else R.string.memory_download_title),Icons.Default.GridView){
     Text(stringResource(if(hasCache)R.string.memory_empty_body else R.string.memory_download_body))
     if(error)Text(stringResource(R.string.memory_load_error),color=MaterialTheme.colorScheme.error)
     if(!hasRemote)Text(stringResource(R.string.memory_wait_summary),style=MaterialTheme.typography.bodySmall)
     if(!online)Text(stringResource(R.string.study_offline),style=MaterialTheme.typography.bodySmall)
     OutlinedButton(enabled=!busy&&online&&hasRemote,onClick=onDownload){Text(stringResource(R.string.memory_download))}
   }
 }
}
/** Active recall: review repeats a concept; understanding removes it from the queue. */
@Composable fun MemoryGameContent(title:String,entries:List<Concept>,version:String,modifier:Modifier=Modifier,initialSeed:Int=0){
 val concepts=remember(entries){cleanConcepts(entries)}
 var pending by rememberSaveable(version,concepts){mutableStateOf(concepts.indices.toList())}
 var known by rememberSaveable(version,concepts){mutableIntStateOf(0)}
 var attempts by rememberSaveable(version,concepts){mutableIntStateOf(0)}
 Column(modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text(title,Modifier.fillMaxWidth().heightIn(max=96.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.titleMedium.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Content))
  Text(stringResource(R.string.flashcard_progress,ltr(known),ltr(concepts.size)),style=MaterialTheme.typography.labelMedium.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Content))
  LinearProgressIndicator(progress={if(concepts.isEmpty())1f else known.toFloat()/concepts.size},modifier=Modifier.fillMaxWidth())
  if(pending.isEmpty())Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
   Icon(Icons.Default.CheckCircle,null,Modifier.size(56.dp),tint=MaterialTheme.colorScheme.primary)
   Text(stringResource(R.string.flashcard_finished),style=MaterialTheme.typography.titleLarge.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Content))
   Button(onClick=hapticAction{pending=concepts.indices.toList();known=0;attempts=0}){Text(stringResource(R.string.memory_restart))}
  }else key(pending.first(),attempts){
   ActiveRecallCard(concepts[pending.first()],Modifier.weight(1f),onAnswer={understood->
    pending=if(understood)pending.drop(1)else pending.drop(1)+pending.first()
    if(understood)known++;attempts++
   })
  }
 }
}
@Composable private fun ActiveRecallCard(concept:Concept,modifier:Modifier,onAnswer:(Boolean)->Unit){
 var flipped by rememberSaveable{mutableStateOf(false)}
 val angle by androidx.compose.animation.core.animateFloatAsState(if(flipped)180f else 0f,androidx.compose.animation.core.tween(320),label="flashcard-flip")
 val back=angle>90f
 Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)){
  OutlinedCard(onClick=hapticAction{if(angle==0f||angle==180f)flipped=!flipped},modifier=Modifier.weight(1f).fillMaxWidth().testTag("recall-card").graphicsLayer{rotationY=angle;cameraDistance=12*density}){
   Column(Modifier.fillMaxSize().testTag("recall-text").graphicsLayer{rotationY=if(back)180f else 0f}.padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
    Text(stringResource(if(back)R.string.memory_definition else R.string.memory_term),style=LocalTextStyle.current.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Content),color=MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(16.dp))
    Text(if(back)concept.explanation else concept.term,Modifier.fillMaxWidth(),style=(if(back)MaterialTheme.typography.bodyLarge else MaterialTheme.typography.headlineSmall).copy(textDirection=androidx.compose.ui.text.style.TextDirection.Content),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    Spacer(Modifier.height(16.dp));Text(stringResource(R.string.flashcard_tap),style=MaterialTheme.typography.labelSmall.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Content),color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  Row(Modifier.fillMaxWidth().heightIn(min=48.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
   if(flipped){
   OutlinedButton(enabled=flipped&&angle>=179f,onClick=hapticAction{onAnswer(false)},modifier=Modifier.weight(1f).testTag("recall-review")){Icon(Icons.Default.Close,null);Text(stringResource(R.string.flashcard_review))}
   Button(enabled=flipped&&angle>=179f,onClick=hapticAction{onAnswer(true)},modifier=Modifier.weight(1f).testTag("recall-known")){Icon(Icons.Default.Check,null);Text(stringResource(R.string.flashcard_known))}
   }
  }
 }
}

@Composable fun TutorScreen(app:LectureApp,id:String,onBack:()->Unit){
 val lectures by app.repo.lectures.collectAsStateWithLifecycle();val l=lectures.firstOrNull{it.id==id}?:return
 val ctx=LocalContext.current;val store=remember{StudyStore(app)};val scope=rememberCoroutineScope();val online=rememberOnline().first
 var turns by remember(id){mutableStateOf(emptyList<TutorTurn>())};var concepts by remember(id){mutableStateOf(emptyList<Concept>())};var busy by remember{mutableStateOf(false)}
 var question by rememberSaveable(id){mutableStateOf("")};var error by remember{mutableIntStateOf(0)};var clear by remember{mutableStateOf(false)};var retryAt by remember{mutableLongStateOf(0L)};var now by remember{mutableLongStateOf(System.currentTimeMillis())}
 LaunchedEffect(id){val saved=withContext(Dispatchers.IO){store.turns(id) to store.cached(id,densityOf(l))?.entries.orEmpty()};turns=saved.first;concepts=saved.second}
 LaunchedEffect(retryAt){while(System.currentTimeMillis()<retryAt){delay(1000);now=System.currentTimeMillis()};now=System.currentTimeMillis()}
 AppScaffold(stringResource(R.string.tutor_title),onBack=onBack,actions={IconButton(enabled=turns.isNotEmpty()&&!busy,onClick={clear=true}){Icon(Icons.Default.DeleteSweep,stringResource(R.string.tutor_clear))}}){pad->
   TutorConversation(l.title,turns,question,{question=it.take(1200)},busy,online&&l.remoteId!=null&&now>=retryAt,if(!online)ctx.getString(R.string.tutor_offline)else if(l.remoteId==null)ctx.getString(R.string.tutor_not_ready)else if(retryAt>now)ctx.getString(R.string.tutor_limit_until,ltr(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(retryAt))))else if(error!=0)ctx.getString(error)else null,concepts.take(3),Modifier.padding(pad).consumeWindowInsets(pad)){
     if(busy||question.isBlank())return@TutorConversation
     val text=question.trim();busy=true;error=0
     scope.launch{try{
       val language=tutorQuestionLanguage(text,ctx.resources.configuration.locales[0].language)
       val reply=ApiProvider.get(app.settings).tutor(l.remoteId!!,text,language,turns.filter{it.reply.kind=="answer"}.takeLast(3).map{it.question.take(400)})
       withContext(Dispatchers.IO){store.append(id,TutorTurn(text,reply))};turns=withContext(Dispatchers.IO){store.turns(id)};question=""
     }catch(e:CancellationException){throw e}catch(e:HttpStatusException){error=when(e.code){404,405->R.string.tutor_server_required;409->R.string.tutor_not_ready;429->{retryAt=System.currentTimeMillis()+(e.retryAfterSeconds?:60).coerceIn(1,86400)*1000L;R.string.tutor_rate_limit};401,403->R.string.tutor_access_error;else->R.string.tutor_error}}catch(_:Exception){error=R.string.tutor_error}finally{busy=false}}
   }
 }
 if(clear)AlertDialog(onDismissRequest={clear=false},title={Text(stringResource(R.string.tutor_clear))},text={Text(stringResource(R.string.tutor_clear_body))},confirmButton={TextButton(onClick={scope.launch{withContext(Dispatchers.IO){store.clearChat(id)};turns=emptyList();clear=false}}){Text(stringResource(R.string.tutor_clear))}},dismissButton={TextButton(onClick={clear=false}){Text(stringResource(R.string.copy_cancel))}})
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun TutorConversation(title:String,turns:List<TutorTurn>,question:String,onQuestion:(String)->Unit,busy:Boolean,canSend:Boolean,error:String?,concepts:List<Concept>,modifier:Modifier=Modifier,onSend:()->Unit){
 val ctx=LocalContext.current;val list=androidx.compose.foundation.lazy.rememberLazyListState()
 LaunchedEffect(turns.lastOrNull()?.id,busy){list.animateScrollToItem(((if(turns.isEmpty())1 else turns.size)+(if(busy)2 else 0)-1).coerceAtLeast(0))}
 Column(modifier.fillMaxSize().navigationBarsPadding().imePadding()){
   OutlinedCard(Modifier.padding(horizontal=20.dp,vertical=8.dp).fillMaxWidth()){
     Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(bidi(title),Modifier.heightIn(max=112.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.titleMedium);Text(stringResource(R.string.tutor_scope),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
   }
   LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,contentPadding=PaddingValues(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
     if(turns.isEmpty())item(key="StudyTools:static:1"){Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text(stringResource(R.string.tutor_empty),style=MaterialTheme.typography.titleMedium);Text(stringResource(R.string.tutor_empty_body),style=MaterialTheme.typography.bodyMedium);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){concepts.forEach{c->SuggestionChip(onClick={onQuestion(ctx.getString(R.string.tutor_suggestion,c.term))},label={Text(bidi(c.term))})}}}}
     itemsIndexed(turns,key={_,turn->turn.id}){index,turn->Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
       Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.fillMaxWidth()){Text(bidi(turn.question),Modifier.padding(16.dp),style=MaterialTheme.typography.bodyMedium)}
       OutlinedCard(Modifier.fillMaxWidth()){
         Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
           Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){Icon(if(turn.reply.kind=="answer")Icons.Default.School else Icons.Default.Info,null,Modifier.size(18.dp));Text(stringResource(R.string.tutor_label),style=MaterialTheme.typography.labelMedium)}
           TutorAnswerBody(turn.reply.answer,turn.reply.citations,turn.id)
         }
       }
     }}
     if(busy)item(key="tutor-pending-question"){Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium){Text(bidi(question),Modifier.fillMaxWidth().padding(12.dp))}}
     if(busy)item(key="StudyTools:static:2"){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Text(stringResource(R.string.tutor_thinking),style=MaterialTheme.typography.bodySmall)}}
   }
   Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
     if(error!=null)Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
     Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)){
       OutlinedTextField(question,onQuestion,Modifier.weight(1f).testTag("tutor-question"),enabled=!busy,label={Text(stringResource(R.string.tutor_question))},maxLines=4)
       IconButton(enabled=canSend&&!busy&&question.isNotBlank(),onClick=hapticAction(onSend)){Icon(Icons.AutoMirrored.Filled.Send,stringResource(R.string.tutor_send),tint=if(canSend&&!busy&&question.isNotBlank())MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}
     }
     Text(stringResource(R.string.tutor_privacy),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
 }
}

/** Keep the client hint aligned with the actual question; the server independently checks it. */
internal fun tutorQuestionLanguage(question:String,fallback:String):String {
 val he=question.count{it in '\u0590'..'\u05ff'};val en=question.count{it in 'a'..'z'||it in 'A'..'Z'}
 return if(he>0&&he>=en/2f)"he" else if(en>0)"en" else if(fallback=="he")"he" else "en"
}
