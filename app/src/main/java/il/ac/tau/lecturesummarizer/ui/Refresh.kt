package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.data.CourseActivity
import android.content.Context
import kotlinx.coroutines.delay

enum class RefreshPhase { IDLE,RUNNING,SUCCESS,ERROR }
@Composable fun relativeUpdated(time:Long):String{
    val now by produceState(System.currentTimeMillis()){while(true){value=System.currentTimeMillis();delay(60_000)}}
    val minutes=((now-time).coerceAtLeast(0)/60_000)
    return when{time==0L->stringResource(R.string.updated_never);minutes<1->stringResource(R.string.updated_now);minutes<60->stringResource(R.string.updated_minutes,ltr(minutes));minutes<1440->stringResource(R.string.updated_hours,ltr(minutes/60));else->stringResource(R.string.updated_days,ltr(minutes/1440))}
}
@Composable fun CourseUpdated(ctx:Context,id:String){
    val activity=remember(ctx){CourseActivity(ctx)};var time by remember(id){mutableLongStateOf(activity.updated(id))}
    DisposableEffect(id){val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key==id)time=activity.updated(id)};activity.prefs.registerOnSharedPreferenceChangeListener(listener);onDispose{activity.prefs.unregisterOnSharedPreferenceChangeListener(listener)}}
    Text(stringResource(R.string.course_updated,relativeUpdated(time)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable fun SearchIndexRefreshCard(phase:RefreshPhase,lastUpdated:Long,done:Int=0,total:Int=0,onRefresh:()->Unit){
    OutlinedCard(Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text(stringResource(R.string.refresh_title),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                TextButton(enabled=phase!=RefreshPhase.RUNNING,onClick=onRefresh){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(6.dp));Text(stringResource(if(phase==RefreshPhase.RUNNING)R.string.refresh_running else R.string.refresh_action))}}
            if(phase==RefreshPhase.RUNNING){
                if(total>0)LinearProgressIndicator(progress={done.toFloat()/total},modifier=Modifier.fillMaxWidth())else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.refresh_progress,ltr(done),ltr(total)),style=MaterialTheme.typography.bodySmall)
            }
            if(phase==RefreshPhase.SUCCESS || phase==RefreshPhase.ERROR)Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Icon(if(phase==RefreshPhase.SUCCESS)Icons.Default.CheckCircle else Icons.Default.ErrorOutline,null,tint=if(phase==RefreshPhase.ERROR)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text(stringResource(if(phase==RefreshPhase.SUCCESS)R.string.refresh_success else R.string.refresh_failed),style=MaterialTheme.typography.bodySmall,color=if(phase==RefreshPhase.ERROR)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            Text(stringResource(R.string.refresh_updated,relativeUpdated(lastUpdated)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.search_local_notice),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
