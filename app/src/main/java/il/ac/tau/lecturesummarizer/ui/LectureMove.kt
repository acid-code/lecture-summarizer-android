package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.data.*
import kotlinx.coroutines.*

@Composable fun LectureMoveDialog(app:LectureApp,lecture:Lecture,onDismiss:()->Unit){
 val courses by app.repo.courses.collectAsState();val scope=rememberCoroutineScope()
 var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf(false)}
 AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text(stringResource(R.string.move_to_course))},
  text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text(bidi(lecture.title),style=MaterialTheme.typography.bodyMedium)
   if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
   if(error)Text(stringResource(R.string.move_course_failed),color=MaterialTheme.colorScheme.error)
   LazyColumn(Modifier.heightIn(max=400.dp)){items(courses.filter{it.id!=lecture.courseId},key={it.id}){course->
    TextButton(enabled=!busy,onClick={busy=true;error=false;scope.launch{
     try{LectureMover.move(app.repo,ApiProvider.get(app.settings),lecture.id,course.id);onDismiss()}
     catch(e:CancellationException){throw e}catch(_:Exception){error=true}finally{busy=false}
    }},modifier=Modifier.fillMaxWidth()){Text(bidi(course.name))}
   }}
  }},confirmButton={},dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text(stringResource(R.string.copy_cancel))}})
}
