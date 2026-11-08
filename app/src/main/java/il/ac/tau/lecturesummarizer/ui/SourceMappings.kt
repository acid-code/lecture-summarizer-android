package il.ac.tau.lecturesummarizer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CourseSourcesScreen(app:LectureApp,course:Course,onBack:()->Unit){
    val ctx=LocalContext.current;val store=remember{SourceMappingStore(app)}
    var saved by remember(course.id){mutableStateOf(store.migrate(course))}
    var editing by remember{mutableStateOf<String?>(null)}
    var calendarEdit by remember{mutableStateOf(false)}
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
            val cs=CalendarRuleStore(app);val events=CalendarRuleProvider.read(app,cs.sources());val matched=CalendarRules.preview(cs.rules(),events.events).filter{it.rule.targetCourseId==course.id || (it.rule.targetCourseId==null && CalendarRules.norm(it.rule.targetCourse)==CalendarRules.norm(course.name))}
            val registry=DataSourceRegistry();val listings=configs.map{c->c to (registry.provider(c.providerId) ?: error("provider")).items(c)}
            partial=events.truncated || listings.any{it.second.partial}
            matched.take(20).map{m->val e=m.event;val event=SourceEvent(e.title,e.start,e.end,e.zone);m to listings.flatMap{(c,list)->SourceMatcher.rank(event,c,list.items)}.sortedByDescending{it.score}}
        };previews=result;checked=true
    }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){error=true;if(e is MoodleAuthRequired)signIn=moodleBase else if(e is PanoptoAuthRequired)signIn=e.viewerUrl ?: panoptoBase}finally{busy=false}}}
    AppScaffold(stringResource(R.string.sources_course_mapping),onBack=onBack){pad->Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(bidi(course.name),style=MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.sources_explanation),style=MaterialTheme.typography.bodyMedium)
        val activeRules=CalendarRuleStore(app).rules().filter{it.targetCourseId==course.id || (it.targetCourseId==null && CalendarRules.norm(it.targetCourse)==CalendarRules.norm(course.name))}
        CourseSourcesOverview(
            calendarSummary=stringResource(R.string.mapping_calendar_count,activeRules.count{it.enabled}),calendarReady=activeRules.any{it.enabled},
            moodleSummary=saved.firstOrNull{it.providerId=="moodle"}?.courseId?.let{stringResource(R.string.mapping_course_id,ltr(it))} ?: stringResource(R.string.mapping_unset),
            moodleReady=saved.any{it.providerId=="moodle" && it.courseId!=null},
            panoptoSummary=saved.firstOrNull{it.providerId=="panopto"}?.let{c->when{c.folderId.isNotBlank()->stringResource(R.string.mapping_folder_set);c.sessionId.isNotBlank()->stringResource(R.string.mapping_session_set);c.courseId!=null->stringResource(R.string.mapping_from_moodle);else->stringResource(R.string.mapping_unset)}} ?: stringResource(R.string.mapping_unset),
            panoptoReady=saved.any{it.providerId=="panopto" && (it.courseId!=null || it.folderId.isNotBlank() || it.sessionId.isNotBlank())},
            onCalendar={calendarEdit=true},onMoodle={editing="moodle"},onPanopto={editing="panopto"},onMatching={editing="matching"})
        CourseUpdated(app,course.id)
        if(saved.any{it.legacy} && !savedNotice)Text(stringResource(R.string.sources_migrated),style=MaterialTheme.typography.bodySmall)

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
    fun resetDraft(){val m=saved.firstOrNull{it.providerId=="moodle"};val p=saved.firstOrNull{it.providerId=="panopto"}
        moodleBase=m?.baseUrl ?: MoodleStore(app).baseUrl;courseId=m?.courseId?.toString().orEmpty()
        panoptoBase=if(p?.folderId?.isNotBlank()==true || p?.sessionId?.isNotBlank()==true)p.baseUrl else Panopto.HOST
        folder=p?.folderId.orEmpty();session=p?.sessionId.orEmpty();tolerance=(p?.toleranceMinutes ?: 120).toString();number=p?.numberPattern ?: SourceConfig.DEFAULT_NUMBER_PATTERN
        date=p?.matchDate ?: true;time=p?.matchTime ?: true;title=p?.matchTitle ?: true
    }
    if(editing!=null)ModalBottomSheet(onDismissRequest={resetDraft();editing=null}){
        Column(Modifier.fillMaxWidth().heightIn(max=700.dp).verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            when(editing){
                "moodle"->{
        OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            MoodleSourceFields(moodleBase,courseId,{moodleBase=it;savedNotice=false},{courseId=it;SourceIds.courseSite(it)?.let{site->moodleBase=site};savedNotice=false})
            TextButton(enabled=!busy,onClick={busy=true;error=false;scope.launch{try{suggestions=withContext(Dispatchers.IO){MoodleMatch.rank(MoodleClient.of(moodleBase).courses(),course.courseNumber,ClassKind.UNKNOWN,course.name).take(5).map{it.course}}}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){error=true;if(e is MoodleAuthRequired)signIn=moodleBase}finally{busy=false}}}){Text(stringResource(R.string.sources_suggest))}
            suggestions.forEach{c->OutlinedButton(onClick={courseId=c.id.toString();savedNotice=false}){Column{Text(bidi(c.fullname));Text(stringResource(R.string.sources_name_suggestion),style=MaterialTheme.typography.bodySmall)}}}
        }}
                }
                "panopto"->{
        OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            PanoptoSourceFields(panoptoBase,folder,session,{panoptoBase=it;savedNotice=false},{folder=it;savedNotice=false},{session=it;savedNotice=false})
        }}
                }
                else->{
        OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(stringResource(R.string.sources_matching),style=MaterialTheme.typography.titleMedium)
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(date,{date=it});Text(stringResource(R.string.sources_match_date))}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(time,{time=it});Text(stringResource(R.string.sources_match_time))}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(title,{title=it});Text(stringResource(R.string.sources_match_title))}
            OutlinedTextField(tolerance,{tolerance=it},modifier=Modifier.fillMaxWidth(),label={Text(stringResource(R.string.sources_tolerance))},singleLine=true)
            OutlinedTextField(number,{number=it},modifier=Modifier.fillMaxWidth(),label={Text(stringResource(R.string.sources_number_pattern))})
            Text(stringResource(R.string.sources_pattern_help),style=MaterialTheme.typography.bodySmall)
        }}
                }
            }
        if(!valid)Text(stringResource(R.string.mapping_invalid),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        Button(modifier=Modifier.fillMaxWidth(),enabled=valid && !busy,onClick={store.save(course.id,configs);id?.let{m->ClassKind.entries.forEach{k->MoodleStore(app).mapCourse("${course.courseNumber ?: course.id}:${k.name}",m)}};saved=configs;il.ac.tau.lecturesummarizer.data.CourseActivity(app).touch(course.id);savedNotice=true;editing=null}){Text(stringResource(R.string.save))}
            TextButton(onClick={resetDraft();editing=null}){Text(stringResource(R.string.cancel))}
        }
    }
    if(calendarEdit)CalendarCourseRulesSheet(app,course,{calendarEdit=false}){calendarEdit=false;rules=true}
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

@Composable fun CourseSourcesOverview(calendarSummary:String,calendarReady:Boolean,moodleSummary:String,moodleReady:Boolean,panoptoSummary:String,panoptoReady:Boolean,onCalendar:()->Unit,onMoodle:()->Unit,onPanopto:()->Unit,onMatching:()->Unit){
    Text(stringResource(R.string.mapping_section_events),style=MaterialTheme.typography.titleMedium)
    SourceMappingRow(stringResource(R.string.mapping_calendar),calendarSummary,calendarReady,Icons.Default.CalendarMonth,onCalendar)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.mapping_section_materials),style=MaterialTheme.typography.titleMedium)
    SourceMappingRow(stringResource(R.string.sources_moodle),moodleSummary,moodleReady,Icons.Default.School,onMoodle)
    SourceMappingRow(stringResource(R.string.sources_panopto),panoptoSummary,panoptoReady,Icons.Default.VideoLibrary,onPanopto)
    TextButton(onClick=onMatching){Icon(Icons.Default.Tune,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.sources_matching))}
}
@Composable private fun SourceMappingRow(title:String,summary:String,ready:Boolean,icon:androidx.compose.ui.graphics.vector.ImageVector,onEdit:()->Unit){
    OutlinedCard(Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Icon(icon,null,tint=MaterialTheme.colorScheme.primary);Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)}
            Text(bidi(summary),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                StatusChip(stringResource(if(ready)R.string.mapping_configured else R.string.mapping_not_configured),color=if(ready)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,icon=if(ready)Icons.Default.CheckCircle else Icons.Default.LinkOff)
                TextButton(onClick=onEdit){Text(stringResource(R.string.mapping_edit))}
            }
        }
    }
}

@Composable fun MoodleSourceFields(base:String,course:String,onBase:(String)->Unit,onCourse:(String)->Unit){
    Text(stringResource(R.string.sources_moodle),style=MaterialTheme.typography.titleMedium)
    OutlinedTextField(base,onBase,Modifier.fillMaxWidth(),label={Text(stringResource(R.string.sources_moodle_site))},singleLine=true)
    OutlinedTextField(course,onCourse,Modifier.fillMaxWidth(),label={Text(stringResource(R.string.sources_moodle_course))},singleLine=true)
}
@Composable fun PanoptoSourceFields(base:String,folder:String,session:String,onBase:(String)->Unit,onFolder:(String)->Unit,onSession:(String)->Unit){
    Text(stringResource(R.string.sources_panopto),style=MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.sources_panopto_fallback),style=MaterialTheme.typography.bodySmall)
    OutlinedTextField(base,onBase,Modifier.fillMaxWidth(),label={Text(stringResource(R.string.sources_panopto_site))},singleLine=true)
    OutlinedTextField(folder,onFolder,Modifier.fillMaxWidth(),label={Text(stringResource(R.string.sources_folder))})
    OutlinedTextField(session,onSession,Modifier.fillMaxWidth(),label={Text(stringResource(R.string.sources_session))})
    Text(stringResource(R.string.sources_folder_access),style=MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CalendarCourseRulesSheet(app:LectureApp,course:Course,onDismiss:()->Unit,onEdit:()->Unit){
    val store=remember{CalendarRuleStore(app)};var rules by remember{mutableStateOf(store.rules())}
    DisposableEffect(Unit){val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key=="rules")rules=store.rules()};store.prefs.registerOnSharedPreferenceChangeListener(listener);onDispose{store.prefs.unregisterOnSharedPreferenceChangeListener(listener)}}
    ModalBottomSheet(onDismissRequest=onDismiss){
        Column(Modifier.fillMaxWidth().heightIn(max=650.dp).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text(bidi(course.name),style=MaterialTheme.typography.titleLarge)
            CalendarCourseRuleRows(rules.filter{it.targetCourseId==course.id || (it.targetCourseId==null && CalendarRules.norm(it.targetCourse)==CalendarRules.norm(course.name))},onToggle={r,on->store.saveRules(rules.map{if(it.id==r.id)it.copy(enabled=on)else it});store.pending(emptyList())},onEdit=onEdit)
        }
    }
}
@Composable fun CalendarCourseRuleRows(rules:List<CalendarImportRule>,onToggle:(CalendarImportRule,Boolean)->Unit,onEdit:()->Unit){
    Text(stringResource(R.string.mapping_calendar),style=MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.mapping_calendar_help),style=MaterialTheme.typography.bodyMedium)
    if(rules.isEmpty())Text(stringResource(R.string.mapping_calendar_empty),color=MaterialTheme.colorScheme.onSurfaceVariant)
    rules.forEach{rule->OutlinedCard(Modifier.fillMaxWidth()){
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(bidi(rule.name),style=MaterialTheme.typography.titleSmall)
                Text(bidi(rule.title.ifBlank{rule.include.joinToString(" · ")}),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if(rule.enabled)R.string.mapping_rule_on else R.string.mapping_rule_off),style=MaterialTheme.typography.labelMedium)
            }
            Switch(rule.enabled,{onToggle(rule,it)},enabled=rule.valid)
        }
    }}
    Button(onClick=onEdit,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.mapping_calendar_edit))}
}
