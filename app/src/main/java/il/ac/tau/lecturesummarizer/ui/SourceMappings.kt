package il.ac.tau.lecturesummarizer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.calendar.*
import il.ac.tau.lecturesummarizer.data.Course
import il.ac.tau.lecturesummarizer.moodle.*
import il.ac.tau.lecturesummarizer.sources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun CourseSourcesButton(app:LectureApp,course:Course){
    if(!app.settings.calendarRulesEnabled)return
    var open by remember{mutableStateOf(false)}
    TextButton(onClick={open=true}){Text(stringResource(R.string.sources_course_mapping))}
    if(open)androidx.compose.ui.window.Dialog(onDismissRequest={open=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){CourseSourcesScreen(app,course){open=false}}
}

@Composable fun CourseSourcesScreen(app:LectureApp,course:Course,onBack:()->Unit){
    val ctx=LocalContext.current;val store=remember{SourceMappingStore(app)}
    val saved=remember(course.id){store.migrate(course)}
    val moodle=saved.firstOrNull{it.providerId=="moodle"};val panopto=saved.firstOrNull{it.providerId=="panopto"}
    var moodleBase by remember{mutableStateOf(moodle?.baseUrl ?: MoodleStore(app).baseUrl)}
    var courseId by remember{mutableStateOf(moodle?.courseId?.toString().orEmpty())}
    var panoptoBase by remember{mutableStateOf(if(panopto?.folderId?.isNotBlank()==true || panopto?.sessionId?.isNotBlank()==true)panopto.baseUrl else Panopto.HOST)}
    var folder by remember{mutableStateOf(panopto?.folderId.orEmpty())};var session by remember{mutableStateOf(panopto?.sessionId.orEmpty())}
    var tolerance by remember{mutableStateOf((panopto?.toleranceMinutes ?: 120).toString())};var number by remember{mutableStateOf(panopto?.numberPattern ?: SourceConfig.DEFAULT_NUMBER_PATTERN)}
    var date by remember{mutableStateOf(panopto?.matchDate ?: true)};var time by remember{mutableStateOf(panopto?.matchTime ?: true)};var title by remember{mutableStateOf(panopto?.matchTitle ?: true)}
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf(false)};var savedNotice by remember{mutableStateOf(false)}
    var signIn by remember{mutableStateOf<String?>(null)};var rules by remember{mutableStateOf(false)}
    var suggestions by remember{mutableStateOf(emptyList<MoodleCourse>())}
    var previews by remember{mutableStateOf<List<Pair<CalendarRuleMatch,List<SourceMatch>>>>(emptyList())};var checked by remember{mutableStateOf(false)};var partial by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val id=SourceIds.course(courseId)
    val folderId=SourceIds.guid(folder,"folderID");val sessionId=SourceIds.guid(session,"id")
    val configs=listOfNotNull(id?.let{SourceConfig("moodle",moodleBase.trimEnd('/'),it,numberPattern=number,toleranceMinutes=tolerance.toIntOrNull() ?: -1)},
        if(id!=null || folder.isNotBlank() || session.isNotBlank())SourceConfig("panopto",if(folder.isNotBlank() || session.isNotBlank())panoptoBase.trimEnd('/')else moodleBase.trimEnd('/'),id,folderId.orEmpty(),sessionId.orEmpty(),date,time,title,number,tolerance.toIntOrNull() ?: -1)else null)
    val valid=configs.isNotEmpty() && configs.all{it.valid} && (folder.isBlank() || folderId!=null) && (session.isBlank() || sessionId!=null) && !(folder.isNotBlank() && session.isNotBlank())
    fun preview(){busy=true;error=false;checked=false;scope.launch{try{
        val result=withContext(Dispatchers.IO){
            val cs=CalendarRuleStore(app);val events=CalendarRuleProvider.read(app,cs.sources());val matched=CalendarRules.preview(cs.rules(),events.events).filter{CalendarRules.norm(it.rule.targetCourse)==CalendarRules.norm(course.name)}
            val registry=DataSourceRegistry();val listings=configs.map{c->c to (registry.provider(c.providerId) ?: error("provider")).items(c)}
            partial=events.truncated || listings.any{it.second.partial}
            matched.take(20).map{m->val e=m.event;val event=SourceEvent(e.title,e.start,e.end,e.zone);m to listings.flatMap{(c,list)->SourceMatcher.rank(event,c,list.items)}.sortedByDescending{it.score}}
        };previews=result;checked=true
    }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){error=true;if(e is MoodleAuthRequired)signIn=moodleBase else if(e is PanoptoAuthRequired)signIn=e.viewerUrl ?: panoptoBase}finally{busy=false}}}
    AppScaffold(stringResource(R.string.sources_course_mapping),onBack=onBack){pad->Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(bidi(course.name),style=MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.sources_explanation),style=MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick={rules=true}){Text(stringResource(R.string.calendar_rules))}
        OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(stringResource(R.string.sources_moodle),style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(moodleBase,{moodleBase=it;savedNotice=false},label={Text(stringResource(R.string.sources_moodle_site))},singleLine=true)
            OutlinedTextField(courseId,{courseId=it;SourceIds.courseSite(it)?.let{site->moodleBase=site};savedNotice=false},label={Text(stringResource(R.string.sources_moodle_course))},singleLine=true)
            TextButton(enabled=!busy,onClick={busy=true;error=false;scope.launch{try{suggestions=withContext(Dispatchers.IO){MoodleMatch.rank(MoodleClient.of(moodleBase).courses(),course.courseNumber,ClassKind.UNKNOWN,course.name).take(5).map{it.course}}}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){error=true;if(e is MoodleAuthRequired)signIn=moodleBase}finally{busy=false}}}){Text(stringResource(R.string.sources_suggest))}
            suggestions.forEach{c->OutlinedButton(onClick={courseId=c.id.toString();savedNotice=false}){Column{Text(bidi(c.fullname));Text(stringResource(R.string.sources_name_suggestion),style=MaterialTheme.typography.bodySmall)}}}
        }}
        OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(stringResource(R.string.sources_panopto),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sources_panopto_fallback),style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(panoptoBase,{panoptoBase=it;savedNotice=false},label={Text(stringResource(R.string.sources_panopto_site))},singleLine=true)
            OutlinedTextField(folder,{folder=it;savedNotice=false},label={Text(stringResource(R.string.sources_folder))})
            OutlinedTextField(session,{session=it;savedNotice=false},label={Text(stringResource(R.string.sources_session))})
            Text(stringResource(R.string.sources_folder_access),style=MaterialTheme.typography.bodySmall)
        }}
        OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(stringResource(R.string.sources_matching),style=MaterialTheme.typography.titleMedium)
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(date,{date=it});Text(stringResource(R.string.sources_match_date))}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(time,{time=it});Text(stringResource(R.string.sources_match_time))}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(title,{title=it});Text(stringResource(R.string.sources_match_title))}
            OutlinedTextField(tolerance,{tolerance=it},label={Text(stringResource(R.string.sources_tolerance))},singleLine=true)
            OutlinedTextField(number,{number=it},label={Text(stringResource(R.string.sources_number_pattern))})
            Text(stringResource(R.string.sources_pattern_help),style=MaterialTheme.typography.bodySmall)
        }}
        if(saved.any{it.legacy} && !savedNotice)Text(stringResource(R.string.sources_migrated),style=MaterialTheme.typography.bodySmall)
        Button(enabled=valid && !busy,onClick={store.save(course.id,configs);id?.let{m->ClassKind.entries.forEach{k->MoodleStore(app).mapCourse("${course.courseNumber ?: course.id}:${k.name}",m)}};savedNotice=true}){Text(stringResource(R.string.save))}
        if(savedNotice)Text(stringResource(R.string.sources_saved))
        OutlinedButton(enabled=valid && !busy && CalendarResolver.hasPermission(app),onClick={preview()}){Text(stringResource(R.string.sources_preview))}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(error)Text(stringResource(R.string.sources_error),color=MaterialTheme.colorScheme.error)
        if(partial)Text(stringResource(R.string.sources_partial),color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(checked && previews.isEmpty())Text(stringResource(R.string.sources_no_events))
        previews.forEach{(m,matches)->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(bidi(m.event.title),style=MaterialTheme.typography.titleMedium)
            Text(ltr(Instant.ofEpochMilli(m.event.start).atZone(ZoneId.of(m.event.zone)).format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))))
            if(matches.isEmpty())Text(stringResource(R.string.sources_no_match))
            matches.take(5).forEach{match->HorizontalDivider();Text(bidi(match.item.title),style=MaterialTheme.typography.titleSmall)
                match.reasons.take(3).forEach{reason->Text(sourceReason(reason),style=MaterialTheme.typography.bodySmall)}
                if(!match.item.material)TextButton(onClick={store.pin(m.event.key,m.event.start,match.item);savedNotice=true}){Text(stringResource(R.string.sources_use_event))}
                TextButton(onClick={safeStart(ctx,Intent(Intent.ACTION_VIEW,Uri.parse(match.item.url)))}){Text(stringResource(R.string.sources_open))}
            }
        }}}
    }}
    if(rules)androidx.compose.ui.window.Dialog(onDismissRequest={rules=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){CalendarRulesScreen(app,onBack={rules=false},presetCourse=course.name)}
    signIn?.let{url->androidx.compose.ui.window.Dialog(onDismissRequest={signIn=null},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){AppScaffold(stringResource(R.string.sources_signin),onBack={signIn=null}){pad->Box(Modifier.padding(pad).fillMaxSize()){MoodleLoginView(if(url.contains("panopto",true))Panopto.origin(url)else moodleBase,startUrl=url){signIn=null}}}}}
}

@Composable fun sourceReason(reason:String)=when{
    reason=="course_mapping"->stringResource(R.string.sources_reason_course)
    reason=="date"->stringResource(R.string.sources_reason_date)
    reason=="time"->stringResource(R.string.sources_reason_time)
    reason=="number"->stringResource(R.string.sources_reason_number)
    reason.startsWith("tokens:")->stringResource(R.string.sources_reason_tokens,reason.substringAfter(':').toIntOrNull() ?: 0)
    else->stringResource(R.string.sources_reason_manual)
}
