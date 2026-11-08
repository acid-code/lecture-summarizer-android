package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.data.Lecture
import il.ac.tau.lecturesummarizer.slides.*

@Composable
fun SummaryFreshnessNotice(app:LectureApp,l:Lecture,density:String?=null) {
    var tick by remember{mutableIntStateOf(0)}
    DisposableEffect(l.id){val p=app.getSharedPreferences("summary_material_versions",0);val cb=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,_->tick++};p.registerOnSharedPreferenceChangeListener(cb);onDispose{p.unregisterOnSharedPreferenceChangeListener(cb)}}
    val f=remember{SummaryFreshness(app)}
    val stale=remember(tick,l.slidesUri,l.extraSlides,l.slideRanges,density){if(density==null)app.settings.summaryDensities.any{f.outdated(l,it)}else f.outdated(l,density)}
    if(stale){
        if(f.state(l.id)=="api_required")Text(stringResource(R.string.slides_range_server_required),color=MaterialTheme.colorScheme.error)
        Text(stringResource(if(f.state(l.id) in setOf("queued","updating"))R.string.summary_updating_old else R.string.summary_outdated),color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(density==null && f.state(l.id) !in setOf("queued","updating","error"))OutlinedButton(enabled=l.state in setOf(il.ac.tau.lecturesummarizer.data.LectureState.DONE,il.ac.tau.lecturesummarizer.data.LectureState.FAILED) && l.jobId!=null && l.remoteId!=null,onClick={SummaryRefreshWorker.enqueue(app,l.id)}){Text(stringResource(if(app.settings.summaryDensities.size>1)R.string.summary_update_both else R.string.slides_update))}
    }
}
