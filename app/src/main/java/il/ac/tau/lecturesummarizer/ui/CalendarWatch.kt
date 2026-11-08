package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.calendar.*
import java.text.DateFormat
import java.util.Date

@Composable fun CalendarWatchStatus(matches:List<CalendarRuleMatch>,selected:Boolean,automatic:Boolean,lastScan:Long,error:Boolean,onWatch:()->Unit,onChoose:()->Unit,loading:Boolean=false,trackingEnabled:Boolean=true,watchConfigured:Boolean=selected,compact:Boolean=false){
 CompactCalendarTypography {CalendarWatchStatusBody(matches,selected,automatic,lastScan,error,onWatch,onChoose,loading,trackingEnabled,watchConfigured,compact)}
}

@Composable private fun CalendarWatchStatusBody(matches:List<CalendarRuleMatch>,selected:Boolean,automatic:Boolean,lastScan:Long,error:Boolean,onWatch:()->Unit,onChoose:()->Unit,loading:Boolean=false,trackingEnabled:Boolean=true,watchConfigured:Boolean=selected,compact:Boolean=false){
    if(compact){
        HorizontalDivider(color=MaterialTheme.colorScheme.primary.copy(alpha=0.15f))
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.Top,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                Icon(Icons.Default.EventAvailable,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)){
                    Text(stringResource(if(selected)R.string.watch_connected else R.string.watch_available),style=MaterialTheme.typography.labelMedium)
                    if(!loading)Text(stringResource(R.string.watch_counts,ltr(matches.size),ltr(matches.map{it.rule.targetCourseId ?: it.rule.targetCourse}.distinct().size)),modifier=Modifier.fillMaxWidth().testTag("calendar-watch-counts"),style=MaterialTheme.typography.labelSmall,softWrap=true)
                }
            }
            // Never squeeze this question beside a count. Give both their complete logical width.
            TextButton(onClick=if(watchConfigured && trackingEnabled)onWatch else onChoose,modifier=Modifier.fillMaxWidth().testTag("calendar-watch-action"),contentPadding=PaddingValues(horizontal=0.dp,vertical=4.dp)){
                Text(stringResource(if(watchConfigured && trackingEnabled)R.string.watch_open else R.string.calendar_pick_events),modifier=Modifier.weight(1f).testTag("calendar-watch-action-text"),style=MaterialTheme.typography.labelLarge,textAlign=TextAlign.Start,softWrap=true)
                Spacer(Modifier.width(8.dp));Icon(Icons.AutoMirrored.Filled.ArrowForward,null,Modifier.size(16.dp))
            }
            if(error)Text(stringResource(R.string.calendar_rules_error),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
            else if(!trackingEnabled)Text(stringResource(R.string.watch_disabled),style=MaterialTheme.typography.labelSmall)
            else if(automatic){
                val estimate=(lastScan+30*60_000L).takeIf{lastScan>0 && it>System.currentTimeMillis()}
                Text(if(estimate!=null)stringResource(R.string.watch_next_check,ltr(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(estimate))))else stringResource(R.string.watch_check_soon),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }else Text(stringResource(R.string.watch_manual_short),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val upcoming=upcomingClasses(matches,System.currentTimeMillis())
    OutlinedCard(Modifier.fillMaxWidth()){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            StatusChip(stringResource(if(selected)R.string.watch_connected else R.string.watch_available),color=MaterialTheme.colorScheme.primary,icon=Icons.Default.EventAvailable)
            if(loading){LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.watch_loading),style=MaterialTheme.typography.bodySmall)}
            else Text(stringResource(R.string.watch_counts,ltr(matches.size),ltr(matches.map{it.rule.targetCourseId ?: it.rule.targetCourse}.distinct().size)),style=MaterialTheme.typography.titleMedium)
            if(!trackingEnabled)Text(stringResource(R.string.watch_disabled),style=MaterialTheme.typography.bodySmall)
            Text(stringResource(if(watchConfigured)R.string.watch_next_explanation else R.string.watch_choose_explanation),style=MaterialTheme.typography.bodySmall)
            if(error)Text(stringResource(R.string.calendar_rules_error),color=MaterialTheme.colorScheme.error)
            if(upcoming.isEmpty() && watchConfigured && !loading && trackingEnabled)Text(stringResource(R.string.watch_no_upcoming),style=MaterialTheme.typography.bodySmall)
            UpcomingLectureRows(upcoming,onOpenAll=onWatch)
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
 CompactCalendarTypography {

    var manage by remember{mutableStateOf(false)}
    AppScaffold(stringResource(R.string.watch_title),onBack=onBack,actions={TextButton(onClick={manage=true}){Text(stringResource(R.string.mapping_edit))}}){pad->
        LazyColumn(Modifier.padding(pad),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            item{CalendarImportButton(app);Text(stringResource(R.string.watch_list_explanation),style=MaterialTheme.typography.bodySmall)}
            if(matches.isEmpty())item{Text(stringResource(R.string.watch_no_upcoming))}
            items(matches.sortedWith(compareBy<CalendarRuleMatch>{it.event.end<System.currentTimeMillis()}.thenBy{it.event.start}),key={it.event.key}){m->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(bidi(m.rule.targetCourse),style=MaterialTheme.typography.titleMedium);Text(bidi(m.event.title));Text(ltr(DateFormat.getDateTimeInstance().format(Date(m.event.start))),style=MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.calendar_rule_fired,bidi(m.rule.name)),style=MaterialTheme.typography.labelSmall)
            }}}
        }
    }
    if(manage)androidx.compose.ui.window.Dialog(onDismissRequest={manage=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){CalendarRulesScreen(app,{manage=false})}

 }
}

/** One compact next-class row by default; a bounded expansion never dumps a semester onto Home. */
fun upcomingClasses(matches:List<CalendarRuleMatch>,now:Long)=matches.filter{it.event.end>now}.sortedBy{it.event.start}.distinctBy{it.event.key}
@Composable fun UpcomingLectureRows(matches:List<CalendarRuleMatch>,onOpenAll:(()->Unit)?=null){
 CompactCalendarTypography {

 var expanded by rememberSaveable{mutableStateOf(false)}
 val largeText=LocalDensity.current.fontScale>1.3f
 val visible=matches.take(if(expanded)3 else 1)
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)){
  visible.forEachIndexed{index,m->Row(Modifier.fillMaxWidth().padding(vertical=2.dp).testTag("upcoming:${m.event.key}"),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=androidx.compose.ui.Alignment.Top){
   Icon(Icons.Default.Event,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.primary)
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
    Text(bidi(m.rule.targetCourse),style=MaterialTheme.typography.titleSmall,softWrap=true)
    if(expanded && m.event.title.trim()!=m.rule.targetCourse.trim())Text(bidi(m.event.title),style=MaterialTheme.typography.bodySmall,softWrap=true)
    val date=ltr(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(m.event.start)))
    if(index==0 && matches.size>1 && !largeText)Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
     Text(date,Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,softWrap=true)
     TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)){Text(stringResource(if(expanded)R.string.home_fewer_events else R.string.home_more_events),style=MaterialTheme.typography.labelMedium,softWrap=true)}
    }else {
     Text(date,Modifier.fillMaxWidth(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,softWrap=true)
     if(index==0 && matches.size>1 && largeText)TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)){Text(stringResource(if(expanded)R.string.home_fewer_events else R.string.home_more_events),style=MaterialTheme.typography.labelMedium,softWrap=true)}
    }
   }
  }}
  if(expanded && matches.size>3 && onOpenAll!=null)TextButton(onClick=onOpenAll,contentPadding=PaddingValues(horizontal=0.dp,vertical=4.dp)){Text(stringResource(R.string.home_all_events,ltr(matches.size)),softWrap=true)}
 }

 }
}
