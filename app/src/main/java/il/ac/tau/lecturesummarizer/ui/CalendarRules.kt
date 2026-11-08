package il.ac.tau.lecturesummarizer.ui

import android.Manifest
import android.content.Intent
import android.content.ContentUris
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.calendar.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun CalendarRulesSection(app:LectureApp,enabled:Boolean=app.settings.calendarRulesEnabled){
    if(!enabled)return
    var open by remember{mutableStateOf(false)}
    OutlinedCard(Modifier.fillMaxWidth().clickable{open=true}){Text(stringResource(R.string.calendar_rules),Modifier.padding(16.dp),style=MaterialTheme.typography.titleMedium)}
    if(open)androidx.compose.ui.window.Dialog(onDismissRequest={open=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){CalendarRulesScreen(app,onBack={open=false})}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CalendarRulesScreen(app:LectureApp,onBack:()->Unit,presetCourse:String="") {
    val ctx=LocalContext.current;val store=remember{CalendarRuleStore(app)};val applier=remember{CalendarRuleApplier(app)}
    val scope=rememberCoroutineScope();val snack=remember{SnackbarHostState()};val undoLabel=stringResource(R.string.undo)
    var rules by remember{mutableStateOf(store.rules())};var selectedSources by remember{mutableStateOf(store.sources())}
    var sources by remember{mutableStateOf(emptyList<CalendarSource>())};var sourcePicker by remember{mutableStateOf(false)}
    var pending by remember{mutableStateOf(store.pending())};var selected by remember{mutableStateOf(pending.map{it.event.key}.toSet())}
    var editing by remember{mutableStateOf<CalendarImportRule?>(null)};var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf(false)};var truncated by remember{mutableStateOf(false)};var confirm by remember{mutableStateOf(false)}
    var automatic by remember{mutableStateOf(store.automatic)};var autoApprove by remember{mutableStateOf(store.autoApprove)}
    fun save(v:List<CalendarImportRule>){rules=v;store.saveRules(v);store.pending(emptyList());pending=emptyList()}
    fun chooseSources(){runCatching{sources=CalendarRuleProvider.sources(app);sourcePicker=true}.onFailure{error=true}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it)chooseSources()}
    fun preview(){busy=true;error=false;scope.launch{try{val scan=CalendarRuleAutomation.scan(app);pending=scan.matches;selected=pending.map{it.event.key}.toSet();truncated=scan.truncated}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){error=true}finally{busy=false}}}
    DisposableEffect(Unit){val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key=="pending")pending=store.pending()};store.prefs.registerOnSharedPreferenceChangeListener(listener);onDispose{store.prefs.unregisterOnSharedPreferenceChangeListener(listener)}}
    AppScaffold(stringResource(R.string.calendar_rules),onBack=onBack,fab={ExtendedFloatingActionButton(onClick={editing=CalendarImportRule(java.util.UUID.randomUUID().toString(),"",targetCourse=presetCourse)},icon={Icon(Icons.Default.Add,null)},text={Text(stringResource(R.string.calendar_rule_new))})}){pad->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(stringResource(R.string.calendar_sources),style=MaterialTheme.typography.titleMedium)
                if(selectedSources.isEmpty())Text(stringResource(R.string.calendar_choose_first))else selectedSources.groupBy{it.accountKey}.forEach{(_,v)->Text(bidi(v.first().account),style=MaterialTheme.typography.labelLarge);Text(v.joinToString(" · "){bidi(it.name)},style=MaterialTheme.typography.bodySmall)}
                Button(enabled=!busy,onClick={if(CalendarResolver.hasPermission(app))chooseSources()else permission.launch(Manifest.permission.READ_CALENDAR)}){Text(stringResource(R.string.calendar_choose_sources))}
                Text(stringResource(R.string.calendar_rules_privacy),style=MaterialTheme.typography.bodySmall)
            }}
            Row(verticalAlignment=Alignment.CenterVertically){Text(stringResource(R.string.calendar_rules_background),Modifier.weight(1f));Switch(automatic,{automatic=it;store.automatic=it;CalendarRuleWorker.schedule(app,it)})}
            Text(stringResource(R.string.calendar_rules_pending_default),style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically){Text(stringResource(R.string.calendar_rules_auto_approve),Modifier.weight(1f));Switch(autoApprove,{autoApprove=it;store.autoApprove=it})}
            if(error || store.prefs.getBoolean("scanError",false))Text(stringResource(R.string.calendar_rules_error),color=MaterialTheme.colorScheme.error)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(truncated)Text(stringResource(R.string.calendar_rules_truncated),color=MaterialTheme.colorScheme.error)
            if(rules.isEmpty())Text(stringResource(R.string.calendar_rules_empty))
            rules.forEachIndexed{i,r->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={editing=r},modifier=Modifier.weight(1f)){Text(bidi(r.name))};Switch(r.enabled,enabled=r.valid,onCheckedChange={on->save(rules.map{if(it.id==r.id)it.copy(enabled=on)else it})})}
                Text(stringResource(R.string.calendar_rule_summary,bidi(r.targetCourse),bidi(listOf(r.title,r.include.joinToString(", "),r.courseFilter).filter{it.isNotBlank()}.joinToString(" · "))),style=MaterialTheme.typography.bodySmall)
                if(r.exclude.isNotEmpty())Text(stringResource(R.string.calendar_rule_excluded,bidi(r.exclude.joinToString(", "))),style=MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.calendar_rule_matches,pending.count{it.rule.id==r.id}),style=MaterialTheme.typography.labelMedium)
                Row{if(i>0)TextButton(onClick={val v=rules.toMutableList();java.util.Collections.swap(v,i,i-1);save(v)}){Text(stringResource(R.string.calendar_rule_up))};TextButton(onClick={val old=rules;save(rules.filterNot{it.id==r.id});scope.launch{val result=withTimeoutOrNull(8000){snack.showSnackbar(app.getString(R.string.calendar_rule_deleted),undoLabel,duration=SnackbarDuration.Indefinite)};snack.currentSnackbarData?.dismiss();if(result==SnackbarResult.ActionPerformed)save(old)}}){Text(stringResource(R.string.copy_delete))}}
            }}}
            Button(enabled=!busy && selectedSources.isNotEmpty() && rules.any{it.enabled && it.valid},onClick={preview()}){Text(stringResource(R.string.calendar_rules_preview))}
            Text(stringResource(R.string.calendar_pending_count,pending.size),style=MaterialTheme.typography.titleLarge)
            pending.forEach{m->val e=m.event;OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Checkbox(e.key in selected,{on->selected=if(on)selected+e.key else selected-e.key});Text(bidi(e.title),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)}
                val zone=runCatching{ZoneId.of(e.zone)}.getOrDefault(ZoneId.of("Asia/Jerusalem"))
                Text(ltr(Instant.ofEpochMilli(e.start).atZone(if(e.allDay)ZoneOffset.UTC else zone).format(DateTimeFormatter.ofPattern(if(e.allDay)"dd/MM/yyyy"else"dd/MM/yyyy HH:mm"))))
                Text(stringResource(R.string.calendar_rule_destination,bidi(m.rule.targetCourse)),style=MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.calendar_rule_fired,bidi(m.rule.name)),style=MaterialTheme.typography.bodySmall)
                Text(bidi(e.snippet),maxLines=3,style=MaterialTheme.typography.bodySmall)
                Row{TextButton(onClick={safeStart(ctx,Intent(Intent.ACTION_VIEW,ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,e.eventId)))}){Text(stringResource(R.string.calendar_open_event))};TextButton(onClick={store.dismiss(m,true);pending=pending.filterNot{it.event.key==e.key};store.pending(pending);scope.launch{val result=withTimeoutOrNull(8000){snack.showSnackbar(app.getString(R.string.calendar_event_dismissed),undoLabel,duration=SnackbarDuration.Indefinite)};snack.currentSnackbarData?.dismiss();if(result==SnackbarResult.ActionPerformed){store.dismiss(m,false);preview()}}}){Text(stringResource(R.string.calendar_rule_dismiss))}}
            }}}
            Button(enabled=!busy && pending.any{it.event.key in selected},onClick={confirm=true}){Text(stringResource(R.string.calendar_add_count,pending.count{it.event.key in selected}))}
            SnackbarHost(snack);Spacer(Modifier.height(96.dp))
        }
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text(stringResource(R.string.calendar_import_confirm_title))},text={Text(stringResource(R.string.calendar_import_confirm_body))},confirmButton={TextButton(onClick={confirm=false;busy=true;scope.launch{
        val approvedMatches=pending.filter{it.event.key in selected};val undo=mutableListOf<CalendarRuleApplier.Undo>();try{approvedMatches.forEach{m->withContext(Dispatchers.IO){applier.apply(m)}?.let{undo+=it}}}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){error=true}finally{busy=false;pending=pending.filterNot{applier.current(it)};store.pending(pending)}
        if(undo.isNotEmpty()){val result=withTimeoutOrNull(8000){snack.showSnackbar(app.getString(R.string.calendar_events_added),undoLabel,duration=SnackbarDuration.Indefinite)};snack.currentSnackbarData?.dismiss();if(result==SnackbarResult.ActionPerformed){val ok=withContext(Dispatchers.IO){undo.reversed().map{u->val ok=applier.undo(u);if(ok)approvedMatches.firstOrNull{it.event.key==u.key}?.let{store.undoSuppression(it,true)};ok}.all{it}};if(!ok)error=true;preview()}}
    }}){Text(stringResource(R.string.calendar_add_count,pending.count{it.event.key in selected}))}},dismissButton={TextButton(onClick={confirm=false}){Text(stringResource(R.string.cancel))}})
    if(sourcePicker){var chosen by remember{mutableStateOf(selectedSources.map{it.id}.toSet())};AlertDialog(onDismissRequest={sourcePicker=false},title={Text(stringResource(R.string.calendar_choose_sources))},text={Column(Modifier.verticalScroll(rememberScrollState())){
        if(sources.isEmpty())Text(stringResource(R.string.calendar_none))
        sources.groupBy{it.accountKey}.forEach{(_,calendars)->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(calendars.all{it.id in chosen},{on->chosen=if(on)chosen+calendars.map{it.id}else chosen-calendars.map{it.id}.toSet()});Text(bidi(calendars.first().account))};calendars.forEach{c->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(c.id in chosen,{on->chosen=if(on)chosen+c.id else chosen-c.id});Text(bidi(c.name))}}}
    }},confirmButton={TextButton(onClick={selectedSources=sources.filter{it.id in chosen};store.sources(selectedSources);store.pending(emptyList());pending=emptyList();if(chosen.isNotEmpty())app.settings.calendarIds=chosen;sourcePicker=false}){Text(stringResource(R.string.save))}},dismissButton={TextButton(onClick={sourcePicker=false}){Text(stringResource(R.string.cancel))}})}
    editing?.let{r->CalendarRuleEditor(app,r,selectedSources,onDismiss={editing=null}){value->save(if(rules.any{it.id==value.id})rules.map{if(it.id==value.id)value else it}else rules+value);editing=null}}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CalendarRuleEditor(app:LectureApp,r:CalendarImportRule,sources:List<CalendarSource>,onDismiss:()->Unit,onSave:(CalendarImportRule)->Unit){
    val courses by app.repo.courses.collectAsStateWithLifecycle()
    var name by remember(r.id){mutableStateOf(r.name)};var title by remember(r.id){mutableStateOf(r.title)}
    var include by remember(r.id){mutableStateOf(r.include.joinToString(", "))};var exclude by remember(r.id){mutableStateOf(r.exclude.joinToString(", "))}
    var course by remember(r.id){mutableStateOf(r.targetCourse)};var targetId by remember(r.id){mutableStateOf(r.targetCourseId)};var filter by remember(r.id){mutableStateOf(r.courseFilter)};var calendars by remember(r.id){mutableStateOf(r.calendars)};var recurring by remember(r.id){mutableStateOf(r.recurringOnly)}
    fun split(s:String)=s.split(',').map{it.trim()}.filter{it.isNotEmpty()}.distinct()
    val value=r.copy(name=name.trim(),title=title.trim(),include=split(include),exclude=split(exclude),courseFilter=filter.trim(),targetCourse=course.trim(),calendars=calendars,recurringOnly=recurring,targetCourseId=targetId ?: courses.firstOrNull{CalendarRules.norm(it.name)==CalendarRules.norm(course)}?.id)
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.calendar_rule_edit))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(name,{name=it},label={Text(stringResource(R.string.calendar_rule_name))})
        OutlinedTextField(title,{title=it},label={Text(stringResource(R.string.calendar_rule_title))})
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(recurring,{recurring=it});Text(stringResource(R.string.calendar_rule_recurring))}
        OutlinedTextField(include,{include=it},label={Text(stringResource(R.string.calendar_rule_include))});FlowRow{split(include).forEach{term->InputChip(false,{include=split(include).filterNot{it==term}.joinToString(", ")},label={Text(bidi(term))})}}
        OutlinedTextField(exclude,{exclude=it},label={Text(stringResource(R.string.calendar_rule_exclude))});FlowRow{split(exclude).forEach{term->InputChip(false,{exclude=split(exclude).filterNot{it==term}.joinToString(", ")},label={Text(bidi(term))})}}
        OutlinedTextField(filter,{filter=it},label={Text(stringResource(R.string.calendar_rule_course_filter))})
        OutlinedTextField(course,{course=it;targetId=null},label={Text(stringResource(R.string.calendar_rule_target))})
        FlowRow{courses.take(20).forEach{c->SuggestionChip(onClick={course=c.name;targetId=c.id},label={Text(bidi(c.name))})}}
        Text(stringResource(R.string.calendar_rule_calendar_scope),style=MaterialTheme.typography.bodySmall)
        sources.forEach{c->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(c.id in calendars,{on->calendars=if(on)calendars+c.id else calendars-c.id});Text(bidi(c.name))}}
        if(!value.valid)Text(stringResource(R.string.calendar_rule_required),style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(enabled=value.valid,onClick={onSave(value)}){Text(stringResource(R.string.save))}},dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}})
}
