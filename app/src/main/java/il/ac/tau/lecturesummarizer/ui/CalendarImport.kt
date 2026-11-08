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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import java.time.format.TextStyle
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
    var connected by remember{mutableStateOf(CalendarConnection.connected(app))}
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME){connected=CalendarConnection.connected(app)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val launch=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){
        connected=CalendarConnection.connected(app)
        if(it.resultCode==android.app.Activity.RESULT_OK)onImported()
    }
    val open={launch.launch(android.content.Intent(context,il.ac.tau.lecturesummarizer.CalendarImportActivity::class.java))}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it){connected=CalendarConnection.connected(app);open()}}
    OutlinedButton(onClick={if(CalendarResolver.hasPermission(app))open() else permission.launch(Manifest.permission.READ_CALENDAR)}){
        Icon(if(connected)Icons.Default.EventAvailable else Icons.Default.CalendarMonth,null)
        Spacer(Modifier.width(8.dp));Text(stringResource(if(connected)R.string.calendar_manage_events else R.string.ob1_cta))
    }
}

/** Sticky action owns navigation/IME insets. All explanatory content scrolls above it. */
@Composable fun CalendarImportFrame(onBack:()->Unit,label:String,enabled:Boolean,onConfirm:()->Unit,
    footerInsets:WindowInsets?=null,content:@Composable (PaddingValues)->Unit){
 CompactCalendarTypography {

    AppScaffold(stringResource(R.string.calendar_import_title),onBack=onBack,bottomBar={
        // Production uses the activity window's REAL insets, not a simulated dialog inset.
        val safe=if(footerInsets==null)Modifier.navigationBarsPadding().imePadding() else Modifier.windowInsetsPadding(footerInsets)
        Surface(tonalElevation=0.dp){Column(Modifier.fillMaxWidth().then(safe).padding(horizontal=16.dp,vertical=8.dp)){
            Button(onClick=onConfirm,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("calendar-import-primary")){
                Text(label,Modifier.fillMaxWidth(),textAlign=TextAlign.Center,softWrap=true)
            }
        }}
    },content=content)

 }
}
@Composable fun CalendarImportScreen(app:LectureApp,onBack:()->Unit,onImported:()->Unit){
    var calendars by remember{mutableStateOf(emptyList<CalendarSource>())}
    var ids by rememberSaveable{mutableStateOf(app.settings.calendarIds)}
    var events by remember{mutableStateOf<List<CalendarImportEvent>?>(null)}
    var selected by rememberSaveable{mutableStateOf(emptySet<String>())};var query by rememberSaveable{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf(false)};var truncated by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit){try{calendars=withContext(Dispatchers.IO){CalendarRuleProvider.sources(app)}}catch(e:CancellationException){throw e}catch(_:Exception){error=true}}
    CalendarImportFrame(onBack={if(!busy)onBack()},label=stringResource(if(events==null)R.string.calendar_preview else R.string.calendar_import_series_count,selected.size),enabled=!busy && if(events==null)calendars.any{it.id in ids}else selected.isNotEmpty(),onConfirm={
        busy=true;error=false;scope.launch{try{
            if(events==null){
                val result=withContext(Dispatchers.IO){CalendarRuleProvider.read(app,calendars.filter{it.id in ids})}
                events=result.events.filter{it.title.isNotBlank()};selected=CalendarSeriesSelection.savedKeys(CalendarRuleStore(app).rules(),result.events);truncated=result.truncated
            }else{
                withContext(Dispatchers.IO){CalendarSelectionImporter.import(app,app.repo,calendars.filter{it.id in ids},CalendarSeriesSelection.instances(selected,events!!))};onImported()
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){error=true}finally{busy=false}}
    }){pad->
        val header:@Composable ()->Unit={
            val term=CalendarCourses.currentTerm(LocalDate.now(ZoneId.of("Asia/Jerusalem")))
            Text(stringResource(R.string.calendar_term,ltr(term.first),ltr(term.second.minusDays(1))),style=MaterialTheme.typography.bodySmall)
            if(error)Text(stringResource(R.string.calendar_import_error),color=MaterialTheme.colorScheme.error)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(truncated)Text(stringResource(R.string.calendar_rules_truncated),color=MaterialTheme.colorScheme.error)
        }
        val body=Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize().padding(horizontal=16.dp)
        if(events==null)CalendarSourcePickerContent(calendars,ids,{ids=it},body,!busy,header)
        else CalendarEventPickerContent(events!!,selected,query,{query=it},{selected=it},body,!busy,header,{
            Text(stringResource(R.string.calendar_series_selection_explainer),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled=!busy,onClick={events=null;selected=emptySet();query=""}){Text(stringResource(R.string.calendar_other))}
        })
    }
}
@Composable fun CalendarSourcePickerContent(calendars:List<CalendarSource>,ids:Set<Long>,onSelected:(Set<Long>)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,header:@Composable ()->Unit={}){
 CompactCalendarTypography {

    LazyColumn(modifier.testTag("calendar-source-list"),contentPadding=PaddingValues(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{header()}
        item{Text(stringResource(R.string.calendar_choose_sources),style=MaterialTheme.typography.titleLarge)}
        item{Text(stringResource(R.string.calendar_rules_privacy),style=MaterialTheme.typography.bodySmall)}
        if(calendars.isEmpty())item{Text(stringResource(R.string.calendar_none))}
        items(calendars,key={it.id}){c->OutlinedCard(onClick={if(enabled)onSelected(if(c.id in ids)ids-c.id else ids+c.id)},modifier=Modifier.fillMaxWidth().testTag("calendar-source:${c.id}")){
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.Top){Checkbox(c.id in ids,{on->onSelected(if(on)ids+c.id else ids-c.id)},enabled=enabled)
                Column(Modifier.weight(1f).padding(top=6.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){Text(bidi(c.name),softWrap=true);Text(bidi(c.account),style=MaterialTheme.typography.bodySmall,softWrap=true)}
            }
        }}
    }

 }
}
/** Bulk actions never silently drop hidden selections. Clear all clears the entire selection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun EventSelectionTools(query:String,onQuery:(String)->Unit,selectedCount:Int,total:Int,onSelectAll:()->Unit,onClear:()->Unit,enabled:Boolean=true){
    OutlinedTextField(query,onQuery,Modifier.fillMaxWidth(),singleLine=true,label={Text(stringResource(R.string.calendar_event_search))},leadingIcon={Icon(Icons.Default.Search,null)},enabled=enabled)
    Text(stringResource(R.string.calendar_selected_series_count,selectedCount,total),style=MaterialTheme.typography.labelLarge)
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        TextButton(enabled=enabled,onClick=onSelectAll){Text(stringResource(if(query.isBlank())R.string.calendar_select_all else R.string.calendar_select_visible),softWrap=true)}
        TextButton(enabled=enabled && selectedCount>0,onClick=onClear){Text(stringResource(R.string.calendar_clear_all),softWrap=true)}
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun CalendarEventPickerContent(events:List<CalendarImportEvent>,selected:Set<String>,query:String,onQuery:(String)->Unit,onSelected:(Set<String>)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,header:@Composable ()->Unit={},footer:@Composable ()->Unit={}){
 CompactCalendarTypography {

    val groups=CalendarSeriesSelection.groups(events);val visible=CalendarSeriesSelection.filter(groups,query);val keys=CalendarSeriesSelection.migrateKeys(selected,groups)
    LazyColumn(modifier.fillMaxSize().testTag("calendar-series-list"),contentPadding=PaddingValues(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{header()}
        item{Text(stringResource(R.string.calendar_pick_events),style=MaterialTheme.typography.titleLarge,softWrap=true)}
        item{Text(stringResource(R.string.calendar_series_only),style=MaterialTheme.typography.bodySmall)}
        item{EventSelectionTools(query,onQuery,keys.size,groups.size,{onSelected(keys+visible.map{it.key})},{onSelected(emptySet())},enabled)}
        if(visible.isEmpty())item{Text(stringResource(R.string.calendar_series_empty))}
        items(visible,key={it.key}){series->OutlinedCard(onClick={if(enabled)onSelected(if(series.key in keys)keys-series.key else keys+series.key)},modifier=Modifier.fillMaxWidth().testTag("calendar-series:${series.key}")){
            Row(Modifier.padding(8.dp),verticalAlignment=Alignment.Top){Checkbox(series.key in keys,{on->onSelected(if(on)keys+series.key else keys-series.key)},enabled=enabled)
                Column(Modifier.weight(1f).padding(top=6.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
                    Text(bidi(series.title),style=MaterialTheme.typography.titleSmall,softWrap=true)
                    Text(seriesSchedule(series),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,softWrap=true)
                    if(series.instances.any{it.seriesId!=null && it.eventId!=it.seriesId})Text(stringResource(R.string.calendar_series_exceptions),style=MaterialTheme.typography.labelSmall,softWrap=true)
                }
            }
        }}
        item{footer()}
    }

 }
}
@Composable fun seriesSchedule(s:CalendarSeries):String{
    val locale=LocalConfiguration.current.locales[0];val zone=runCatching{ZoneId.of(s.zone)}.getOrDefault(ZoneId.of("Asia/Jerusalem"))
    val start=Instant.ofEpochMilli(s.start).atZone(if(s.allDay)ZoneOffset.UTC else zone)
    val time=if(s.allDay)stringResource(R.string.calendar_series_all_day)else ltr(start.format(DateTimeFormatter.ofPattern("HH:mm"))+"–"+start.plusNanos((s.instances.first().end-s.instances.first().start)*1000000).format(DateTimeFormatter.ofPattern("HH:mm")))
    val frequency=when(s.frequency){"WEEKLY"->if(s.interval==1)stringResource(R.string.calendar_series_weekly)else stringResource(R.string.calendar_series_weeks,ltr(s.interval));"DAILY"->if(s.interval==1)stringResource(R.string.calendar_series_daily)else stringResource(R.string.calendar_series_days,ltr(s.interval));"MONTHLY"->if(s.interval==1)stringResource(R.string.calendar_series_monthly)else stringResource(R.string.calendar_series_months,ltr(s.interval));"YEARLY"->if(s.interval==1)stringResource(R.string.calendar_series_yearly)else stringResource(R.string.calendar_series_years,ltr(s.interval));else->stringResource(R.string.calendar_series_repeating)}
    val days=s.days.joinToString(", "){it.getDisplayName(TextStyle.FULL,locale)}
    return listOf(days.takeIf{it.isNotBlank()},time,frequency).filterNotNull().joinToString(" · ")
}
