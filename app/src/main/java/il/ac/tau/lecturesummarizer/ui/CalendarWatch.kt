package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.calendar.*
import java.text.DateFormat
import java.util.Date

@Composable fun CalendarWatchStatus(matches:List<CalendarRuleMatch>,selected:Boolean,automatic:Boolean,lastScan:Long,error:Boolean,onWatch:()->Unit,onChoose:()->Unit,loading:Boolean=false,trackingEnabled:Boolean=true,watchConfigured:Boolean=selected){
    val upcoming=matches.filter{it.event.end>System.currentTimeMillis()}.sortedBy{it.event.start}.take(2)
    OutlinedCard(Modifier.fillMaxWidth()){
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            StatusChip(stringResource(if(selected)R.string.watch_connected else R.string.watch_available),color=MaterialTheme.colorScheme.primary,icon=Icons.Default.EventAvailable)
            if(loading){LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.watch_loading),style=MaterialTheme.typography.bodySmall)}
            else Text(stringResource(R.string.watch_counts,ltr(matches.size),ltr(matches.map{it.rule.targetCourseId ?: it.rule.targetCourse}.distinct().size)),style=MaterialTheme.typography.titleMedium)
            if(!trackingEnabled)Text(stringResource(R.string.watch_disabled),style=MaterialTheme.typography.bodySmall)
            Text(stringResource(if(watchConfigured)R.string.watch_next_explanation else R.string.watch_choose_explanation),style=MaterialTheme.typography.bodySmall)
            if(error)Text(stringResource(R.string.calendar_rules_error),color=MaterialTheme.colorScheme.error)
            if(upcoming.isEmpty() && watchConfigured && !loading && trackingEnabled)Text(stringResource(R.string.watch_no_upcoming),style=MaterialTheme.typography.bodySmall)
            upcoming.forEach{m->Column{Text(bidi(m.rule.targetCourse),style=MaterialTheme.typography.titleSmall);Text(bidi(m.event.title),style=MaterialTheme.typography.bodySmall);Text(ltr(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(m.event.start))),style=MaterialTheme.typography.labelSmall)}}
            if(automatic){
                val estimate=(lastScan+30*60_000L).takeIf{lastScan>0 && it>System.currentTimeMillis()}
                Text(if(estimate!=null)stringResource(R.string.watch_next_check,ltr(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(estimate))))else stringResource(R.string.watch_check_soon),style=MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.watch_check_estimate),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }else Text(stringResource(R.string.watch_check_manual),style=MaterialTheme.typography.bodySmall)
            if(lastScan>0)Text(stringResource(R.string.watch_last_check,relativeUpdated(lastScan)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick=if(watchConfigured && trackingEnabled)onWatch else onChoose){Text(stringResource(if(watchConfigured && trackingEnabled)R.string.watch_open else R.string.calendar_pick_events))}
        }
    }
}

@Composable fun CalendarWatchListScreen(app:LectureApp,matches:List<CalendarRuleMatch>,onBack:()->Unit){
    var manage by remember{mutableStateOf(false)}
    AppScaffold(stringResource(R.string.watch_title),onBack=onBack,actions={TextButton(onClick={manage=true}){Text(stringResource(R.string.mapping_edit))}}){pad->
        LazyColumn(Modifier.padding(pad),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{CalendarImportButton(app);Text(stringResource(R.string.watch_list_explanation),style=MaterialTheme.typography.bodySmall)}
            if(matches.isEmpty())item{Text(stringResource(R.string.watch_no_upcoming))}
            items(matches.sortedWith(compareBy<CalendarRuleMatch>{it.event.end<System.currentTimeMillis()}.thenBy{it.event.start}),key={it.event.key}){m->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(bidi(m.rule.targetCourse),style=MaterialTheme.typography.titleMedium);Text(bidi(m.event.title));Text(ltr(DateFormat.getDateTimeInstance().format(Date(m.event.start))),style=MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.calendar_rule_fired,bidi(m.rule.name)),style=MaterialTheme.typography.labelSmall)
            }}}
        }
    }
    if(manage)androidx.compose.ui.window.Dialog(onDismissRequest={manage=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){CalendarRulesScreen(app,{manage=false})}
}
