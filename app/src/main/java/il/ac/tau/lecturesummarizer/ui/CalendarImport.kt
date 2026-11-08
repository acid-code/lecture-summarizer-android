package il.ac.tau.lecturesummarizer.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.calendar.*
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun CalendarImportButton(app:LectureApp,onImported:()->Unit={}) {
    var open by remember{mutableStateOf(false)}
    var connected by remember{mutableStateOf(CalendarConnection.connected(app))}
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME){connected=CalendarConnection.connected(app)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it){connected=CalendarConnection.connected(app);open=true}}
    OutlinedButton(onClick={if(CalendarResolver.hasPermission(app))open=true else permission.launch(Manifest.permission.READ_CALENDAR)}){
        Icon(if(connected)Icons.Default.EventAvailable else Icons.Default.CalendarMonth,null)
        Spacer(Modifier.width(8.dp));Text(stringResource(if(connected)R.string.calendar_manage_events else R.string.ob1_cta))
    }
    if(open)androidx.compose.ui.window.Dialog(onDismissRequest={open=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
        CalendarImportScreen(app,{open=false}){connected=CalendarConnection.connected(app);open=false;onImported()}
    }
}

@Composable fun CalendarImportScreen(app:LectureApp,onBack:()->Unit,onImported:()->Unit){
    var calendars by remember{mutableStateOf(emptyList<CalendarSource>())}
    var ids by rememberSaveable{mutableStateOf(app.settings.calendarIds)}
    var events by remember{mutableStateOf<List<CalendarImportEvent>?>(null)}
    var selected by rememberSaveable{mutableStateOf(emptySet<String>())}
    var query by rememberSaveable{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf(false)};var truncated by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit){try{calendars=withContext(Dispatchers.IO){CalendarRuleProvider.sources(app)}}catch(e:CancellationException){throw e}catch(_:Exception){error=true}}
    AppScaffold(stringResource(R.string.calendar_import_title),onBack={if(!busy)onBack()}){pad->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            val term=CalendarCourses.currentTerm(LocalDate.now(ZoneId.of("Asia/Jerusalem")))
            Text(stringResource(R.string.calendar_term,ltr(term.first),ltr(term.second.minusDays(1))),style=MaterialTheme.typography.bodySmall)
            if(error)Text(stringResource(R.string.calendar_import_error),color=MaterialTheme.colorScheme.error)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(truncated)Text(stringResource(R.string.calendar_rules_truncated),color=MaterialTheme.colorScheme.error)
            if(events==null){
                Text(stringResource(R.string.calendar_choose_sources),style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.calendar_rules_privacy),style=MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    if(calendars.isEmpty())item{Text(stringResource(R.string.calendar_none))}
                    items(calendars,key={it.id}){c->OutlinedCard{Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(c.id in ids,{on->ids=if(on)ids+c.id else ids-c.id},enabled=!busy);Column(Modifier.weight(1f)){Text(bidi(c.name));Text(bidi(c.account),style=MaterialTheme.typography.bodySmall)}}}}
                }
                Button(modifier=Modifier.fillMaxWidth(),enabled=!busy && calendars.any{it.id in ids},onClick={busy=true;error=false;scope.launch{try{
                    val result=withContext(Dispatchers.IO){CalendarRuleProvider.read(app,calendars.filter{it.id in ids})}
                    events=result.events.filter{it.title.isNotBlank()};selected=emptySet();truncated=result.truncated
                }catch(e:CancellationException){throw e}catch(_:Exception){error=true}finally{busy=false}}}){Text(stringResource(R.string.calendar_preview))}
            }else{
                CalendarEventPickerContent(events!!,selected,query,{query=it},{selected=it},modifier=Modifier.weight(1f),enabled=!busy)
                Text(stringResource(R.string.calendar_selection_explainer),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(enabled=!busy,onClick={events=null;selected=emptySet();query=""}){Text(stringResource(R.string.calendar_other))}
                Button(modifier=Modifier.fillMaxWidth(),enabled=!busy && selected.isNotEmpty(),onClick={busy=true;error=false;scope.launch{try{
                    withContext(Dispatchers.IO){CalendarSelectionImporter.import(app,app.repo,calendars.filter{it.id in ids},events!!.filter{it.key in selected})};onImported()
                }catch(e:CancellationException){throw e}catch(_:Exception){error=true}finally{busy=false}}}){Text(stringResource(R.string.calendar_import_event_count,selected.size))}
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Bulk actions never silently drop hidden selections. Clear all clears the entire selection. */
@Composable fun EventSelectionTools(query:String,onQuery:(String)->Unit,selectedCount:Int,total:Int,onSelectAll:()->Unit,onClear:()->Unit,enabled:Boolean=true){
    OutlinedTextField(query,onQuery,Modifier.fillMaxWidth(),singleLine=true,label={Text(stringResource(R.string.calendar_event_search))},leadingIcon={Icon(Icons.Default.Search,null)})
    Text(stringResource(R.string.calendar_selected_count,selectedCount,total),style=MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
        TextButton(enabled=enabled,onClick=onSelectAll){Text(stringResource(if(query.isBlank())R.string.calendar_select_all else R.string.calendar_select_visible))}
        TextButton(enabled=enabled && selectedCount>0,onClick=onClear){Text(stringResource(R.string.calendar_clear_all))}
    }
}
@Composable fun CalendarEventPickerContent(events:List<CalendarImportEvent>,selected:Set<String>,query:String,onQuery:(String)->Unit,onSelected:(Set<String>)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true){
    val visible=EventSelection.filter(events,query)
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(stringResource(R.string.calendar_pick_events),style=MaterialTheme.typography.titleLarge)
        EventSelectionTools(query,onQuery,selected.size,events.size,{onSelected(EventSelection.selectVisible(selected,visible))},{onSelected(emptySet())},enabled)
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(visible.isEmpty())item{Text(stringResource(R.string.calendar_events_empty))}
            items(visible,key={it.key}){e->OutlinedCard(onClick={if(enabled)onSelected(if(e.key in selected)selected-e.key else selected+e.key)},modifier=Modifier.fillMaxWidth()){
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.Top){Checkbox(e.key in selected,{on->onSelected(if(on)selected+e.key else selected-e.key)},enabled=enabled)
                    Column(Modifier.weight(1f).padding(top=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(bidi(e.title),style=MaterialTheme.typography.titleSmall)
                        val zone=runCatching{ZoneId.of(e.zone)}.getOrDefault(ZoneId.of("Asia/Jerusalem"))
                        Text(ltr(Instant.ofEpochMilli(e.start).atZone(if(e.allDay)ZoneOffset.UTC else zone).format(DateTimeFormatter.ofPattern(if(e.allDay)"dd/MM/yyyy" else "dd/MM/yyyy HH:mm"))),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }}
        }
    }
}
