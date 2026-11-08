package il.ac.tau.lecturesummarizer.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.calendar.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable fun CalendarImportButton(app:LectureApp,onImported:()->Unit={}) {
    var open by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)}
    var calendars by remember{mutableStateOf(emptyList<CalendarResolver.DeviceCalendar>())}
    var ids by remember{mutableStateOf(app.settings.calendarIds)}
    var courses by remember{mutableStateOf<List<CalendarCourses.Course>?>(null)}
    var checked by remember{mutableStateOf(emptySet<Int>())};var error by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    fun show(){calendars=CalendarResolver.listCalendars(app);courses=null;error=false;open=true}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it)show()}
    Button(enabled=!busy,onClick={if(CalendarResolver.hasPermission(app))show()else permission.launch(Manifest.permission.READ_CALENDAR)}){Text(stringResource(R.string.ob1_cta))}
    if(open)AlertDialog(onDismissRequest={if(!busy)open=false},title={Text(stringResource(R.string.calendar_import_title))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        val term=CalendarCourses.currentTerm(LocalDate.now(java.time.ZoneId.of("Asia/Jerusalem")))
        Text(stringResource(R.string.calendar_term,ltr(term.first),ltr(term.second.minusDays(1))),style=MaterialTheme.typography.bodySmall)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(error)Text(stringResource(R.string.calendar_import_error),color=MaterialTheme.colorScheme.error)
        if(courses==null){
            if(calendars.isEmpty())Text(stringResource(R.string.calendar_none))
            calendars.forEach{c->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(c.id in ids,{on->ids=if(on)ids+c.id else ids-c.id},enabled=!busy);Column{Text(bidi(c.name));Text(bidi(c.account),style=MaterialTheme.typography.bodySmall)}}}
        }else{
            if(courses!!.isEmpty())Text(stringResource(R.string.calendar_no_courses))
            courses!!.forEachIndexed{i,c->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(i in checked,{on->checked=if(on)checked+i else checked-i},enabled=!busy);Text(bidi(c.name))}}
            TextButton(enabled=!busy,onClick={courses=null}){Text(stringResource(R.string.calendar_other))}
        }
    }},confirmButton={TextButton(enabled=!busy && if(courses==null)ids.isNotEmpty()else checked.isNotEmpty(),onClick={
        if(courses==null){busy=true;scope.launch{try{courses=withContext(Dispatchers.IO){CalendarCourses.preview(app,ids)};checked=courses!!.indices.toSet()}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){error=true}finally{busy=false}}}
        else{CalendarCourses.add(app,courses!!.filterIndexed{i,_->i in checked});app.settings.calendarIds=ids;open=false;onImported()}
    }){Text(stringResource(if(courses==null)R.string.calendar_preview else R.string.calendar_import_selected))}},dismissButton={TextButton(enabled=!busy,onClick={open=false}){Text(stringResource(R.string.cancel))}})
}
