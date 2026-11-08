package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.calendar.*
import il.ac.tau.lecturesummarizer.data.*
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

/** Derives catch-up only from explicitly selected calendars and enabled rules; no inferred account scan. */
fun matchedClasses(app:LectureApp):List<CalendarRuleMatch>{
 val s=CalendarRuleStore(app)
 if(!app.settings.calendarRulesEnabled || !CalendarResolver.hasPermission(app) || s.sources().isEmpty())return emptyList()
 return CalendarRules.preview(s.rules(),CalendarRuleProvider.read(app,s.sources()).events)
}
fun hasLectureFor(app:LectureApp,m:CalendarRuleMatch)=app.repo.lectures.value.any{l->
 (l.courseId==m.rule.targetCourseId || app.repo.course(l.courseId)?.name==m.rule.targetCourse) &&
 kotlin.math.abs(l.recordedAtMs-m.event.start)<60*60_000 && (l.segments.isNotEmpty() || l.remoteId!=null || l.fromPanopto)
}
@Composable fun MainTabs(route:String,onSelect:(String)->Unit){
 NavigationBar(containerColor=MaterialTheme.colorScheme.surface){
  listOf(Triple("home",R.string.tab_home,Icons.Default.Home),Triple("courses",R.string.tab_courses,Icons.Default.AutoStories),Triple("search",R.string.tab_search,Icons.Default.Search)).forEach{(id,label,icon)->
   NavigationBarItem(selected=route==id,onClick={onSelect(id)},icon={Icon(icon,null)},label={Text(stringResource(label))})
  }
 }
}
@Composable fun HomeScreen(app:LectureApp,onOpen:(String)->Unit,onSettings:()->Unit,onCourses:()->Unit,onRecord:(String)->Unit,onPanopto:(String,Long?)->Unit){
 val all by app.repo.lectures.collectAsState();val courses by app.repo.courses.collectAsState();val unread by app.unread.ids.collectAsState()
 var calendar by remember{mutableStateOf<List<CalendarRuleMatch>>(emptyList())};var loading by remember{mutableStateOf(true)}
 var watch by remember{mutableStateOf(false)};var available by remember{mutableStateOf(CalendarConnection.connected(app))};var selectedCalendars by remember{mutableStateOf(false)};var automatic by remember{mutableStateOf(false)};var lastScan by remember{mutableLongStateOf(0)};var scanError by remember{mutableStateOf(false)}
 var setup by remember{mutableStateOf(false)};var calendarError by remember{mutableStateOf(false)};var retry by remember{mutableIntStateOf(0)}
 val watchStore=remember{CalendarRuleStore(app)}
 DisposableEffect(Unit){val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,_->retry++};val settingsPrefs=app.getSharedPreferences("settings",android.content.Context.MODE_PRIVATE);watchStore.prefs.registerOnSharedPreferenceChangeListener(listener);settingsPrefs.registerOnSharedPreferenceChangeListener(listener);onDispose{watchStore.prefs.unregisterOnSharedPreferenceChangeListener(listener);settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)}}
 androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME){available=CalendarConnection.connected(app);retry++}
 val now by produceState(System.currentTimeMillis()){while(true){value=System.currentTimeMillis();delay(60_000)}}
 LaunchedEffect(courses.map{it.id to it.name},now/60_000,retry,setup,watch){loading=true;available=CalendarConnection.connected(app);selectedCalendars=watchStore.sources().isNotEmpty() || app.settings.calendarIds.isNotEmpty();automatic=app.settings.calendarRulesEnabled && watchStore.automatic;lastScan=watchStore.prefs.getLong("lastScan",0);scanError=watchStore.prefs.getBoolean("scanError",false);try{val result=withContext(Dispatchers.IO){runCatching{matchedClasses(app)}};calendarError=result.isFailure;calendar=result.getOrDefault(emptyList())}finally{loading=false}}
 val next=calendar.filter{it.event.end>now}.minByOrNull{it.event.start}
 val missed=calendar.filter{it.event.end<now && it.event.start>now-7*86400_000L && !hasLectureFor(app,it)}.sortedByDescending{it.event.start}.distinctBy{it.rule.targetCourseId ?: it.rule.targetCourse}.take(3)
 AppScaffold(stringResource(R.string.home_title),logo=true,actions={IconButton(onClick=onSettings){Icon(Icons.Default.Settings,stringResource(R.string.settings))}}){pad->
  HomeContent(all,courses,unread,next,missed,loading,Modifier.padding(pad),onOpen,onCourses,onRecord,onPanopto,{setup=true},calendarError,{retry++},calendarStatus=if(available)({CalendarWatchStatus(calendar,selectedCalendars,automatic,lastScan,calendarError || scanError,{watch=true},{setup=true},loading=loading,trackingEnabled=app.settings.calendarRulesEnabled,watchConfigured=watchStore.rules().any{it.enabled && it.valid} && watchStore.sources().isNotEmpty())})else null)
 }
 if(setup)SemesterSetupSheet(app,{setup=false})
 if(watch)androidx.compose.ui.window.Dialog(onDismissRequest={watch=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){CalendarWatchListScreen(app,calendar,{watch=false})}
}
@Composable fun HomeContent(all:List<Lecture>,courses:List<Course>,unread:Set<String>,next:CalendarRuleMatch?,missed:List<CalendarRuleMatch>,loading:Boolean,modifier:Modifier=Modifier,onOpen:(String)->Unit,onCourses:()->Unit,onRecord:(String)->Unit,onPanopto:(String,Long?)->Unit,onSetup:()->Unit,calendarError:Boolean=false,onRetry:()->Unit={},calendarStatus:(@Composable ()->Unit)?=null){
 LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{Text(stringResource(R.string.home_greeting),style=MaterialTheme.typography.displaySmall)}
  calendarStatus?.let{status->item{status()}}
  if(next!=null || calendarStatus==null)item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),elevation=CardDefaults.cardElevation(0.dp)){
   Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Text(stringResource(if(next!=null && next.event.start<=System.currentTimeMillis())R.string.home_now else R.string.home_next),style=MaterialTheme.typography.labelMedium)
    if(calendarError){Text(stringResource(R.string.home_calendar_error));TextButton(onClick=onRetry){Text(stringResource(R.string.retry))}}
    else if(next!=null){Text(bidi(next.rule.targetCourse),style=MaterialTheme.typography.titleLarge);Text(bidi(next.event.title));Text(ltr(DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(next.event.start))))
     val course=courses.firstOrNull{it.id==next.rule.targetCourseId || it.name==next.rule.targetCourse}
     if(course!=null)Button(onClick={onRecord(course.id)}){Icon(Icons.Default.Mic,null);Text(stringResource(R.string.copy_record_lecture))}
    }else{Text(stringResource(R.string.home_no_next),style=MaterialTheme.typography.titleLarge);Text(stringResource(R.string.home_no_next_body));TextButton(onClick=onSetup){Text(stringResource(R.string.semester_setup))}}
    if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
   }
  }}
  val processing=all.filter{it.state in setOf(LectureState.UPLOADING,LectureState.PROCESSING) || it.state==LectureState.RECORDED && it.segments.any{segment->!segment.uploaded}}
  if(processing.isNotEmpty())item{Text(stringResource(R.string.home_processing),style=MaterialTheme.typography.titleMedium)}
  items(processing,key={"processing:${it.id}"}){l->DashboardLectureRow(l,courses,{onOpen(l.id)}){LectureStatusChip(l);LinearProgressIndicator(progress={l.progress.coerceIn(0,100)/100f},modifier=Modifier.fillMaxWidth())}}
  val ready=all.filter{it.state==LectureState.DONE}.sortedWith(compareByDescending<Lecture>{it.id in unread}.thenByDescending{it.recordedAtMs}).take(8)
  item{Text(stringResource(R.string.home_summaries),style=MaterialTheme.typography.titleMedium)}
  if(ready.isEmpty())item{Text(stringResource(R.string.home_no_summaries));OutlinedButton(onClick=onCourses){Text(stringResource(R.string.tab_courses))}}
  items(ready,key={"ready:${it.id}"}){l->DashboardLectureRow(l,courses,{onOpen(l.id)}){if(l.id in unread)Surface(color=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary,shape=MaterialTheme.shapes.small){Text(stringResource(R.string.label_new),Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium)}else LectureStatusChip(l)}}
  if(missed.isNotEmpty())item{Text(stringResource(R.string.home_catchup),style=MaterialTheme.typography.titleMedium)}
  items(missed,key={"missed:${it.event.key}"}){m->OutlinedCard{
   Column(Modifier.padding(16.dp)){Text(bidi(m.rule.targetCourse),style=MaterialTheme.typography.titleSmall);Text(bidi(m.event.title));Text(ltr(DateFormat.getDateTimeInstance().format(Date(m.event.start))));TextButton(onClick={courses.firstOrNull{it.id==m.rule.targetCourseId || it.name==m.rule.targetCourse}?.let{onPanopto(it.id,m.event.start)}}){Text(stringResource(R.string.import_panopto))}}
  }}
  item{OutlinedButton(onClick=onSetup,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.EventNote,null);Text(stringResource(R.string.semester_setup))}}
 }
}
@Composable fun DashboardLectureRow(l:Lecture,courses:List<Course>,onClick:()->Unit,detail:@Composable ()->Unit){
 OutlinedCard(onClick=onClick,modifier=Modifier.fillMaxWidth()){
  Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(bidi(courses.firstOrNull{it.id==l.courseId}?.name.orEmpty()),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary);Text(bidi(l.title),style=MaterialTheme.typography.titleSmall);detail()}
 }
}
@Composable fun SearchScreen(app:LectureApp,onOpen:(String)->Unit){
 val lectures by app.repo.lectures.collectAsState();val courses by app.repo.courses.collectAsState()
 var query by rememberSaveable{mutableStateOf("")};var hits by remember{mutableStateOf<List<StudyIndex.Hit>>(emptyList())};var busy by remember{mutableStateOf(false)};var indexed by remember{mutableIntStateOf(0)}
 var phase by remember{mutableStateOf(RefreshPhase.IDLE)};var lastUpdated by remember{mutableLongStateOf(StudyIndex(app).lastUpdated)};var done by remember{mutableIntStateOf(0)};var total by remember{mutableIntStateOf(0)};val snack=remember{SnackbarHostState()}
 val scope=rememberCoroutineScope();val index=remember{StudyIndex(app)}
 LaunchedEffect(query,lectures,courses,indexed){delay(250);hits=if(query.trim().length<2)emptyList()else withContext(Dispatchers.IO){index.search(lectures,courses,query.take(200))}}
 AppScaffold(stringResource(R.string.tab_search)){pad->
  Box(Modifier.padding(pad)){
   SearchContent(query,{query=it.take(200)},hits,busy,Modifier,onOpen,onIndex={if(!busy)scope.launch{busy=true;phase=RefreshPhase.RUNNING;done=0;total=lectures.size
    try{val result=index.reindexLocal(app){d,t->done=d;total=t};phase=if(result.failures==0)RefreshPhase.SUCCESS else RefreshPhase.ERROR;lastUpdated=index.lastUpdated;indexed++}
    catch(e:CancellationException){throw e}catch(_:Exception){phase=RefreshPhase.ERROR}finally{busy=false}
    snack.showSnackbar(app.getString(if(phase==RefreshPhase.SUCCESS)R.string.refresh_success else R.string.refresh_failed))
   }},refreshPhase=phase,lastUpdated=lastUpdated,refreshDone=done,refreshTotal=total)
   SnackbarHost(snack,Modifier.align(Alignment.BottomCenter))
  }
 }
}
@Composable fun SearchContent(query:String,onQuery:(String)->Unit,hits:List<StudyIndex.Hit>,busy:Boolean,modifier:Modifier=Modifier,onOpen:(String)->Unit,onIndex:()->Unit,refreshPhase:RefreshPhase=if(busy)RefreshPhase.RUNNING else RefreshPhase.IDLE,lastUpdated:Long=0L,refreshDone:Int=0,refreshTotal:Int=0){
 LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{OutlinedTextField(query,onQuery,Modifier.fillMaxWidth(),label={Text(stringResource(R.string.search_hint))},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true)}
  item{RefreshStatusCard(refreshPhase,lastUpdated,refreshDone,refreshTotal,onIndex)}
  if(query.trim().length>=2 && hits.isEmpty())item{Text(stringResource(R.string.search_empty))}
  items(hits,key={it.lecture.id}){hit->OutlinedCard(onClick={onOpen(hit.lecture.id)}){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(bidi(hit.course),style=MaterialTheme.typography.labelMedium);Text(bidi(hit.lecture.title),style=MaterialTheme.typography.titleMedium);if(hit.snippet.isNotBlank())Text(bidi(hit.snippet),maxLines=3);LectureStatusChip(hit.lecture)}}}
 }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SemesterSetupSheet(app:LectureApp,onDismiss:()->Unit){
 val courses by app.repo.courses.collectAsState();val selected=remember{mutableStateMapOf<String,Boolean>()};var configure by remember{mutableStateOf<Course?>(null)}
 configure?.let{course->androidx.compose.ui.window.Dialog(onDismissRequest={configure=null},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){CourseSourcesScreen(app,course){configure=null}}}
 ModalBottomSheet(onDismissRequest=onDismiss){
  Column(Modifier.heightIn(max=650.dp).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Text(stringResource(R.string.semester_setup),style=MaterialTheme.typography.titleLarge)
   Text(stringResource(R.string.semester_body),style=MaterialTheme.typography.bodyMedium)
   var rulesEnabled by remember{mutableStateOf(app.settings.calendarRulesEnabled)}
   CalendarImportButton(app){rulesEnabled=app.settings.calendarRulesEnabled}
   Row(verticalAlignment=Alignment.CenterVertically){Text(stringResource(R.string.calendar_rules_flag),Modifier.weight(1f));Switch(rulesEnabled,{rulesEnabled=it;app.settings.calendarRulesEnabled=it})}
   CalendarRulesSection(app,rulesEnabled)
   courses.forEach{course->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(selected[course.id] ?: false,{selected[course.id]=it});Text(bidi(course.name),Modifier.weight(1f));TextButton(enabled=rulesEnabled,onClick={configure=course}){Text(stringResource(R.string.semester_sources))}}}
   if(selected.values.any{it})Button(enabled=rulesEnabled,onClick={courses.firstOrNull{selected[it.id]==true}?.let{configure=it;selected[it.id]=false}}){Text(stringResource(R.string.semester_configure_next))}
   TextButton(onClick=onDismiss){Text(stringResource(R.string.done_save))}
  }
 }
}
