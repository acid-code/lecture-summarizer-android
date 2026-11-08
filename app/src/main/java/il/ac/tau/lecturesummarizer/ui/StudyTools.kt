package il.ac.tau.lecturesummarizer.ui

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
 LaunchedEffect(id,l.pdfPath){cache=withContext(Dispatchers.IO){store.cached(id,densityOf(l))};busy=false}
 fun reload(){if(busy||l.remoteId==null)return;scope.launch{busy=true;error=false;try{withContext(Dispatchers.IO){store.refresh(ApiProvider.get(app.settings),id,l.remoteId,densityOf(l),l.jobId.orEmpty())};cache=withContext(Dispatchers.IO){store.cached(id,densityOf(l))}}catch(e:CancellationException){throw e}catch(_:Exception){error=true}finally{busy=false}}}
 AppScaffold(stringResource(R.string.memory_title),onBack=onBack,actions={if(cache!=null)IconButton(enabled=!busy&&online&&l.remoteId!=null,onClick={reload()}){Icon(Icons.Default.Download,stringResource(R.string.memory_update))}}){pad->
   if(cache?.entries?.size?.let{it>=2}==true)Column(Modifier.padding(pad).fillMaxSize()){
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
   SectionCard(stringResource(if(hasCache)R.string.memory_empty_title else R.string.memory_download_title),Icons.Default.GridView){
     Text(stringResource(if(hasCache)R.string.memory_empty_body else R.string.memory_download_body))
     if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
     if(error)Text(stringResource(R.string.memory_load_error),color=MaterialTheme.colorScheme.error)
     if(!hasRemote)Text(stringResource(R.string.memory_wait_summary),style=MaterialTheme.typography.bodySmall)
     if(!online)Text(stringResource(R.string.study_offline),style=MaterialTheme.typography.bodySmall)
     OutlinedButton(enabled=!busy&&online&&hasRemote,onClick=onDownload){Text(stringResource(R.string.memory_download))}
   }
 }
}
private val RoundSaver=Saver<MemoryRound,List<Int>>(save={listOf(it.attempts,it.matched.size)+it.matched+it.open},restore={MemoryRound(it.drop(2+it[1]),it.drop(2).take(it[1]),it[0])})
/** Four pairs at a time; no timer. A mismatch stays readable until the learner closes it. */
@Composable fun MemoryGameContent(title:String,entries:List<Concept>,version:String,modifier:Modifier=Modifier,initialSeed:Int=Random.nextInt()){
 var group by rememberSaveable(version){mutableIntStateOf(0)};var seed by rememberSaveable(version){mutableIntStateOf(initialSeed)}
 val groups=remember(entries){memoryGroups(entries)};if(groups.isEmpty())return
 val current=groups[group.coerceIn(groups.indices)];val cards=remember(current,seed){memoryCards(current,seed)}
 var round by rememberSaveable(version,group,seed,stateSaver=RoundSaver){mutableStateOf(MemoryRound())}
 var detail by remember{mutableStateOf<String?>(null)}
 Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Text(bidi(title),style=MaterialTheme.typography.titleLarge)
   Text(stringResource(R.string.memory_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(stringResource(R.string.memory_pairs,ltr(round.matched.size),ltr(current.size)),style=MaterialTheme.typography.labelMedium);Text(stringResource(R.string.memory_attempts,ltr(round.attempts)),style=MaterialTheme.typography.labelMedium)}
   cards.indices.chunked(2).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
     row.forEach{index->val card=cards[index];val matched=card.pair in round.matched;val face=matched||index in round.open
       OutlinedCard(onClick={round=round.flip(index,cards)},enabled=!matched&&round.open.size<2,modifier=Modifier.weight(1f).height(120.dp).testTag("memory-card-$index"),colors=CardDefaults.outlinedCardColors(containerColor=if(matched)MaterialTheme.colorScheme.secondaryContainer else if(face)MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer,disabledContainerColor=if(matched)MaterialTheme.colorScheme.secondaryContainer else if(face)MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer,disabledContentColor=if(matched)MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)){
         Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
           if(face){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){if(matched)Icon(Icons.Default.CheckCircle,stringResource(R.string.memory_matched),Modifier.size(14.dp),tint=MaterialTheme.colorScheme.primary);Text(stringResource(if(card.definition)R.string.memory_definition else R.string.memory_term),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)};Text(bidi(card.text),Modifier.weight(1f,fill=false),style=MaterialTheme.typography.bodyMedium,maxLines=3,overflow=TextOverflow.Ellipsis);if(card.text.length>85)TextButton(onClick={detail=card.text},contentPadding=PaddingValues(0.dp)){Text(stringResource(R.string.memory_read_more),style=MaterialTheme.typography.labelSmall)}}
           else{Icon(Icons.Default.QuestionMark,null,tint=MaterialTheme.colorScheme.primary);Text(stringResource(R.string.memory_card,ltr(index+1)),style=MaterialTheme.typography.labelSmall)}
         }
       }
     }
   }}
   if(round.open.size==2){Text(stringResource(R.string.memory_mismatch),style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick={round=round.conceal()},Modifier.fillMaxWidth()){Text(stringResource(R.string.memory_close_cards))}}
   if(round.complete(current.size)){
     Text(stringResource(R.string.memory_complete),style=MaterialTheme.typography.titleMedium)
     Button(onClick={if(group<groups.lastIndex)group++ else group=0;seed=Random.nextInt()},Modifier.fillMaxWidth()){Text(stringResource(if(group<groups.lastIndex)R.string.memory_next else R.string.memory_restart))}
   }else TextButton(onClick={seed=Random.nextInt()}){Icon(Icons.Default.Refresh,null,Modifier.size(18.dp));Text(stringResource(R.string.memory_shuffle))}
   Text(stringResource(R.string.memory_round,ltr(group+1),ltr(groups.size)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Text(stringResource(R.string.memory_local),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 }
 detail?.let{body->AlertDialog(modifier=Modifier.testTag("memory-full-text"),onDismissRequest={detail=null},title={Text(stringResource(R.string.memory_read_more))},text={Text(bidi(body),Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={detail=null}){Text(stringResource(R.string.queue_close))}})}
}

@Composable fun TutorScreen(app:LectureApp,id:String,onBack:()->Unit){
 val lectures by app.repo.lectures.collectAsStateWithLifecycle();val l=lectures.firstOrNull{it.id==id}?:return
 val ctx=LocalContext.current;val store=remember{StudyStore(app)};val scope=rememberCoroutineScope();val online=rememberOnline().first
 var turns by remember(id){mutableStateOf(emptyList<TutorTurn>())};var concepts by remember(id){mutableStateOf(emptyList<Concept>())};var busy by remember{mutableStateOf(false)}
 var question by rememberSaveable(id){mutableStateOf("")};var error by remember{mutableIntStateOf(0)};var clear by remember{mutableStateOf(false)};var retryAt by remember{mutableLongStateOf(0L)};var now by remember{mutableLongStateOf(System.currentTimeMillis())}
 LaunchedEffect(id){val saved=withContext(Dispatchers.IO){store.turns(id) to store.cached(id,densityOf(l))?.entries.orEmpty()};turns=saved.first;concepts=saved.second}
 LaunchedEffect(retryAt){while(System.currentTimeMillis()<retryAt){delay(1000);now=System.currentTimeMillis()};now=System.currentTimeMillis()}
 AppScaffold(stringResource(R.string.tutor_title),onBack=onBack,actions={IconButton(enabled=turns.isNotEmpty()&&!busy,onClick={clear=true}){Icon(Icons.Default.DeleteSweep,stringResource(R.string.tutor_clear))}}){pad->
   TutorConversation(l.title,turns,question,{question=it.take(1200)},busy,online&&l.remoteId!=null&&now>=retryAt,if(!online)ctx.getString(R.string.tutor_offline)else if(l.remoteId==null)ctx.getString(R.string.tutor_not_ready)else if(retryAt>now)ctx.getString(R.string.tutor_limit_until,ltr(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(retryAt))))else if(error!=0)ctx.getString(error)else null,concepts.take(3),Modifier.padding(pad)){
     if(busy||question.isBlank())return@TutorConversation
     val text=question.trim();busy=true;error=0
     scope.launch{try{
       val language=ctx.resources.configuration.locales[0].language.let{if(it=="he")"he"else "en"}
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
 LaunchedEffect(turns.size,busy){if(turns.isNotEmpty())list.animateScrollToItem((turns.size-1).coerceAtLeast(0))}
 Column(modifier.fillMaxSize().imePadding()){
   OutlinedCard(Modifier.padding(horizontal=20.dp,vertical=8.dp).fillMaxWidth()){
     Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(bidi(title),style=MaterialTheme.typography.titleMedium);Text(stringResource(R.string.tutor_scope),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
   }
   LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,contentPadding=PaddingValues(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
     if(turns.isEmpty())item{Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text(stringResource(R.string.tutor_empty),style=MaterialTheme.typography.titleMedium);Text(stringResource(R.string.tutor_empty_body),style=MaterialTheme.typography.bodyMedium);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){concepts.forEach{c->SuggestionChip(onClick={onQuestion(ctx.getString(R.string.tutor_suggestion,c.term))},label={Text(bidi(c.term))})}}}}
     itemsIndexed(turns){index,turn->Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
       Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.fillMaxWidth()){Text(bidi(turn.question),Modifier.padding(16.dp),style=MaterialTheme.typography.bodyMedium)}
       OutlinedCard(Modifier.fillMaxWidth()){
         Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
           Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){Icon(if(turn.reply.kind=="answer")Icons.Default.School else Icons.Default.Info,null,Modifier.size(18.dp));Text(stringResource(R.string.tutor_label),style=MaterialTheme.typography.labelMedium)}
           Text(bidi(turn.reply.answer),style=MaterialTheme.typography.bodyMedium)
           if(turn.reply.citations.isNotEmpty()){
             var expanded by rememberSaveable(index,turn.question){mutableStateOf(false)}
             TextButton(onClick={expanded=!expanded}){Text(stringResource(R.string.tutor_sources,ltr(turn.reply.citations.size)));Icon(if(expanded)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)}
             if(expanded)turn.reply.citations.forEach{c->HorizontalDivider();Text(stringResource(when(c.kind){"transcript"->R.string.tutor_source_transcript;"summary"->R.string.tutor_source_summary;else->R.string.tutor_source_slides}),style=MaterialTheme.typography.labelSmall);if(c.kind=="slides")Text(bidi(c.label),style=MaterialTheme.typography.labelSmall);Text(bidi(c.quote),style=MaterialTheme.typography.bodySmall)}
           }
         }
       }
     }}
     if(busy)item{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Text(stringResource(R.string.tutor_thinking),style=MaterialTheme.typography.bodySmall)}}
   }
   Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
     if(error!=null)Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
     Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)){
       OutlinedTextField(question,onQuestion,Modifier.weight(1f).testTag("tutor-question"),enabled=!busy,label={Text(stringResource(R.string.tutor_question))},maxLines=4)
       IconButton(enabled=canSend&&!busy&&question.isNotBlank(),onClick=onSend){Icon(Icons.AutoMirrored.Filled.Send,stringResource(R.string.tutor_send),tint=if(canSend&&!busy&&question.isNotBlank())MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}
     }
     Text(stringResource(R.string.tutor_privacy),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
 }
}
