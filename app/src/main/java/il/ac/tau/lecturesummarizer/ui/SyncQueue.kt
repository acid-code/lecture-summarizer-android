package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.autosync.SyncQueueEntry
import java.text.DateFormat
import java.util.Date

fun syncCategoryLabel(category:String)=when(category){"wait"->R.string.queue_wait;"queued"->R.string.queue_queued;"done"->R.string.queue_done;"skip"->R.string.queue_existing;"notfound"->R.string.queue_missing;"failed"->R.string.queue_failed;else->R.string.queue_dismissed}
fun syncCategoryExplanation(category:String)=when(category){"wait"->R.string.queue_wait_help;"queued"->R.string.queue_queued_help;"done"->R.string.queue_done_help;"skip"->R.string.queue_existing_help;"notfound"->R.string.queue_missing_help;"failed"->R.string.queue_failed_help;else->R.string.queue_dismissed_help}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun SyncCountChips(rows:List<SyncQueueEntry>,onOpen:(String)->Unit){
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        listOf("wait","queued","done","skip","notfound","failed").forEach{category->
            AssistChip(onClick={onOpen(category)},label={Text(stringResource(R.string.queue_count,stringResource(syncCategoryLabel(category)),ltr(rows.count{it.category==category})))},leadingIcon={Icon(when(category){"wait"->Icons.Default.Schedule;"queued"->Icons.Default.CloudDownload;"done"->Icons.Default.CheckCircle;"notfound"->Icons.Default.SearchOff;"failed"->Icons.Default.ErrorOutline;else->Icons.Default.LibraryBooks},null,Modifier.size(18.dp))},trailingIcon={Icon(Icons.Default.Info,stringResource(R.string.queue_info),Modifier.size(16.dp))})
        }
    }
}
@Composable fun SyncQueueList(category:String,rows:List<SyncQueueEntry>,onRetry:(SyncQueueEntry)->Unit,onDismiss:(SyncQueueEntry)->Unit,modifier:Modifier=Modifier){
    LazyColumn(modifier.fillMaxWidth(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text(stringResource(syncCategoryLabel(category)),style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(12.dp));Text(stringResource(syncCategoryExplanation(category)),style=MaterialTheme.typography.bodyMedium)}
        if(rows.isEmpty())item{Text(stringResource(R.string.queue_empty),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(rows,key={it.key}){row->OutlinedCard(Modifier.fillMaxWidth()){
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(bidi(row.course),style=MaterialTheme.typography.titleMedium)
                if(row.title.isNotBlank())Text(bidi(row.title),style=MaterialTheme.typography.bodyMedium)
                if(row.start>0)Text(ltr(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(row.start))),style=MaterialTheme.typography.bodySmall)
                row.lecture?.let{LectureStatusChip(it)} ?: StatusChip(stringResource(syncCategoryLabel(row.category)),icon=when(row.category){"wait"->Icons.Default.Schedule;"notfound"->Icons.Default.SearchOff;"queued"->Icons.Default.CloudDownload;"done"->Icons.Default.CheckCircle;else->Icons.Default.LibraryBooks})
                if(row.category in setOf("wait","notfound","failed"))Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    TextButton(onClick={onRetry(row)}){Text(stringResource(R.string.retry))}
                    if(row.category!="failed")TextButton(onClick={onDismiss(row)}){Text(stringResource(R.string.calendar_rule_dismiss))}
                }
            }
        }}
    }
}
