package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.autosync.SyncQueueEntry
import java.text.DateFormat
import java.util.Date

fun syncCategoryLabel(category:String)=when(category){"wait"->R.string.queue_wait;"queued"->R.string.queue_queued;"done"->R.string.queue_done;"skip"->R.string.queue_existing;"notfound"->R.string.queue_missing;"failed"->R.string.queue_failed;else->R.string.queue_dismissed}
fun syncCategoryExplanation(category:String)=when(category){"wait"->R.string.queue_wait_help;"queued"->R.string.queue_queued_help;"done"->R.string.queue_done_help;"skip"->R.string.queue_existing_help;"notfound"->R.string.queue_missing_help;"failed"->R.string.queue_failed_help;else->R.string.queue_dismissed_help}
private fun syncIcon(category:String)=when(category){"wait"->Icons.Default.Schedule;"queued"->Icons.Default.CloudDownload;"done"->Icons.Default.CheckCircle;"notfound"->Icons.Default.SearchOff;"failed"->Icons.Default.ErrorOutline;else->Icons.Default.LibraryBooks}

/** Count opens its records; the independent info action only opens a short explanation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun SyncCountChips(rows:List<SyncQueueEntry>,onInfo:((String)->Unit)?=null,onOpen:(String)->Unit){
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        listOf("wait","queued","done","skip","notfound","failed").forEach{category->
            val title=stringResource(syncCategoryLabel(category))
            Surface(shape=MaterialTheme.shapes.small,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline),color=MaterialTheme.colorScheme.surface){
                Row(verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={onOpen(category)},contentPadding=PaddingValues(start=12.dp,end=8.dp,top=4.dp,bottom=4.dp)){
                        Icon(syncIcon(category),null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.queue_count,title,ltr(rows.count{it.category==category})))
                    }
                    VerticalDivider(Modifier.height(24.dp))
                    IconButton(onClick={(onInfo ?: onOpen)(category)}){
                        Icon(Icons.Default.Info,stringResource(R.string.queue_info_for,title),Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable fun SyncInfoDialog(category:String,onClose:()->Unit){
    AlertDialog(modifier=Modifier.widthIn(max=340.dp).testTag("sync-info-dialog"),onDismissRequest=onClose,icon={Icon(syncIcon(category),null)},title={Text(stringResource(syncCategoryLabel(category)))},text={
        Text(stringResource(syncCategoryExplanation(category)),Modifier.heightIn(max=(LocalConfiguration.current.screenHeightDp*0.5f).dp).verticalScroll(rememberScrollState()))
    },confirmButton={TextButton(onClick=onClose){Text(stringResource(R.string.queue_close))}})
}

/** One actual production disclosure, also exercised by native Compose UI tests. No navigation route. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SyncQueueDisclosure(rows:List<SyncQueueEntry>,onRetry:(SyncQueueEntry)->Unit,onDismiss:(SyncQueueEntry)->Unit,error:Boolean=false){
    var category by remember{mutableStateOf<String?>(null)}
    var info by remember{mutableStateOf<String?>(null)}
    var expanded by remember{mutableStateOf(false)}
    SyncCountChips(rows,onOpen={category=it;expanded=false},onInfo={info=it})
    category?.let{c->ModalBottomSheet(onDismissRequest={category=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        SyncQueueCompactContent(c,rows.filter{it.category==c},onRetry,onDismiss,expanded,{expanded=it},{info=c},error)
    }}
    info?.let{c->SyncInfoDialog(c){info=null}}
}

@Composable fun SyncQueueCompactContent(category:String,rows:List<SyncQueueEntry>,onRetry:(SyncQueueEntry)->Unit,onDismiss:(SyncQueueEntry)->Unit,expanded:Boolean,onExpanded:(Boolean)->Unit,onInfo:()->Unit,error:Boolean=false){
    Column(Modifier.fillMaxWidth().testTag("sync-count-sheet").padding(horizontal=20.dp).padding(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Text(stringResource(R.string.queue_count,stringResource(syncCategoryLabel(category)),ltr(rows.size)),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            IconButton(onClick=onInfo){Icon(Icons.Default.Info,stringResource(R.string.queue_info_for,stringResource(syncCategoryLabel(category))))}
        }
        Text(stringResource(syncCategoryExplanation(category)),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(error)Text(stringResource(R.string.queue_action_error),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        if(rows.isEmpty())Text(stringResource(R.string.queue_empty),style=MaterialTheme.typography.bodyMedium)
        else if(expanded){
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=(LocalConfiguration.current.screenHeightDp*0.5f).dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                items(rows,key={it.key}){SyncQueueRow(it,onRetry,onDismiss)}
            }
        }else SyncQueueRow(rows.first(),onRetry,onDismiss)
        if(rows.size>1)TextButton(onClick={onExpanded(!expanded)},modifier=Modifier.align(Alignment.End)){
            Text(if(expanded)stringResource(R.string.queue_collapse)else stringResource(R.string.queue_expand,ltr(rows.size)))
        }
    }
}

@Composable private fun SyncQueueRow(row:SyncQueueEntry,onRetry:(SyncQueueEntry)->Unit,onDismiss:(SyncQueueEntry)->Unit){
    OutlinedCard(Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(bidi(row.course),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
            if(row.title.isNotBlank())Text(bidi(row.title),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
            if(row.start>0)Text(ltr(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(row.start))),style=MaterialTheme.typography.bodySmall)
            row.lecture?.let{LectureStatusChip(it)} ?: StatusChip(stringResource(syncCategoryLabel(row.category)),icon=syncIcon(row.category))
            if(row.category in setOf("wait","notfound","failed"))Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                TextButton(onClick={onRetry(row)}){Text(stringResource(R.string.retry))}
                if(row.category!="failed")TextButton(onClick={onDismiss(row)}){Text(stringResource(R.string.calendar_rule_dismiss))}
            }
        }
    }
}

/** Legacy component retained for earlier screen fixtures, not used for the Settings disclosure. */
@Composable fun SyncQueueList(category:String,rows:List<SyncQueueEntry>,onRetry:(SyncQueueEntry)->Unit,onDismiss:(SyncQueueEntry)->Unit,modifier:Modifier=Modifier){
    LazyColumn(modifier.fillMaxWidth(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text(stringResource(syncCategoryLabel(category)),style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(12.dp));Text(stringResource(syncCategoryExplanation(category)),style=MaterialTheme.typography.bodyMedium)}
        if(rows.isEmpty())item{Text(stringResource(R.string.queue_empty),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(rows,key={it.key}){SyncQueueRow(it,onRetry,onDismiss)}
    }
}
