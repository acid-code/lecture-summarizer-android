package il.ac.tau.lecturesummarizer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.moodle.MoodleStore
import il.ac.tau.lecturesummarizer.slides.SlideChoices
import il.ac.tau.lecturesummarizer.upload.JobPollWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlidesCard(app: LectureApp, l: Lecture, onMoodle: () -> Unit) {
    val ctx = LocalContext.current
    val choices = remember { SlideChoices(app) }
    val work by remember(l.id){androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("slides-match-${l.id}")}.collectAsStateWithLifecycle(initialValue=emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val prefs = app.getSharedPreferences("slide_choices", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val auto = remember(tick) { choices.isAuto(l.id) }
    val candidates = remember(tick, l.id) { runCatching {
        val a = JSONArray(choices.candidates(l.id) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getLong("id").toString() !in choices.rejected(l.id) }
    }.getOrDefault(emptyList()) }
    val bundle=remember(tick,l.id){runCatching{val a=JSONArray(choices.bundle(l.id) ?: "[]");(0 until a.length()).map{a.getJSONObject(it)}}.getOrDefault(emptyList()).takeIf{items->items.none{it.getLong("id").toString() in choices.rejected(l.id)}}.orEmpty()}
    val lectures by app.repo.lectures.collectAsStateWithLifecycle()
    val previous=lectures.filter{it.courseId==l.courseId && it.id!=l.id && it.recordedAtMs<l.recordedAtMs && il.ac.tau.lecturesummarizer.moodle.MoodleMatch.kindFromTitle(it.title)==il.ac.tau.lecturesummarizer.moodle.MoodleMatch.kindFromTitle(l.title)}.maxByOrNull{it.recordedAtMs}
    var why by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var offer by remember { mutableStateOf(false) }
    var orderFor by remember{mutableStateOf<String?>(null)}
    var rangeFor by remember{mutableStateOf<String?>(null)}
    val editable=l.state !in setOf(LectureState.UPLOADING,LectureState.PROCESSING)
    val undoText = stringResource(R.string.undo)
    val removedText = stringResource(R.string.slides_removed)
    fun changed(uris:List<String>,ranges:Map<String,SlideRange> = l.slideRanges){
        choices.manual(l.id)
        app.repo.updateLecture(l.id){it.copy(slidesUri=uris.firstOrNull(),extraSlides=uris.drop(1).distinct(),slideRanges=ranges.filterKeys{key->key in uris},uploadedSlides=emptyList())}
        if(l.jobId!=null && l.remoteId!=null)offer=true
    }
    fun attach(items:List<org.json.JSONObject>){
        val pairs=items.map{item->val file=MoodleStore(app).let{it.cached(item.getLong("id"),it.lectureBase(l.id))}
            file?.let{FileProvider.getUriForFile(app,app.packageName+".files",it).toString() to item}}
        if(pairs.any{it==null}){onMoodle();return}
        val ranges=l.slideRanges.toMutableMap();pairs.filterNotNull().forEach{(uri,item)->item.optJSONObject("range")?.let{range->runCatching{slideRangeFromJson(range)}.getOrNull()?.let{ranges[uri]=it}}}
        changed((l.allSlides+pairs.filterNotNull().map{it.first}).distinct(),ranges)
    }
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty()){uris.forEach{persist(ctx,it)};changed((l.allSlides+uris.map{it.toString()}).distinct())}}
    OutlinedCard(Modifier.fillMaxWidth()){
      Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(stringResource(R.string.slides_title),style=MaterialTheme.typography.titleMedium)
        l.allSlides.forEach{uri->
            OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
                Text(bidi(displayName(ctx,uri)),style=MaterialTheme.typography.bodyMedium)
                val range=l.slideRanges[uri]
                Text(if(range==null)stringResource(R.string.slides_full_deck)else stringResource(if(range.approximate)R.string.slides_range_approx else R.string.slides_range_exact,ltr(range.start),ltr(range.end)),style=MaterialTheme.typography.labelMedium)
                choices.order(l.courseId,displayName(ctx,uri))?.let{Text(stringResource(R.string.slides_order_value,ltr(it)),style=MaterialTheme.typography.labelSmall)}
                Row{TextButton(enabled=editable,onClick={orderFor=uri}){Text(stringResource(R.string.slides_order))};TextButton(enabled=editable,onClick={rangeFor=uri}){Text(stringResource(R.string.slides_edit_range))}
                    TextButton(enabled=editable,onClick={
                        val old=l;val freshness=il.ac.tau.lecturesummarizer.slides.SummaryFreshness(app);val before=freshness.checkpoint(l.id)
                        val remaining=l.allSlides-uri;choices.manual(l.id);val generation=freshness.checkpoint(l.id).generation
                        app.repo.updateLecture(l.id){it.copy(slidesUri=remaining.firstOrNull(),extraSlides=remaining.drop(1),slideRanges=it.slideRanges-uri,uploadedSlides=emptyList())}
                        scope.launch{val result=withTimeoutOrNull(8_000){snackbar.showSnackbar(removedText,undoText,duration=SnackbarDuration.Indefinite)};snackbar.currentSnackbarData?.dismiss()
                            if(result==SnackbarResult.ActionPerformed && freshness.checkpoint(l.id).generation==generation && app.repo.lecture(l.id)?.allSlides==remaining){app.repo.updateLecture(l.id){it.copy(slidesUri=old.slidesUri,extraSlides=old.extraSlides,slideRanges=old.slideRanges,uploadedSlides=old.uploadedSlides)};freshness.restore(l.id,before,generation)}
                            else if(result!=SnackbarResult.ActionPerformed && l.jobId!=null && l.remoteId!=null)offer=true
                        }
                    }){Text(stringResource(R.string.slides_remove))}}
            }}
        }
        if(auto && l.allSlides.isNotEmpty()){StatusChip(stringResource(R.string.slides_auto));TextButton(onClick={why=true}){Text(stringResource(R.string.slides_why))}}
        val carry=previous?.allSlides?.lastOrNull()?.takeIf{it !in l.allSlides}
        if(carry!=null)OutlinedButton(enabled=editable,onClick={changed(l.allSlides+carry)}){Column{Text(stringResource(R.string.slides_continue_previous));Text(bidi(displayName(ctx,carry)),style=MaterialTheme.typography.labelSmall)}}
        val phase=remember(tick,l.id){choices.phase(l.id)}
        val (online,_)=rememberOnline()
        val metered=(app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
        val battery=app.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
        val lowBattery= !battery.isCharging && battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..14
        val queued=work.any{it.state==androidx.work.WorkInfo.State.ENQUEUED || it.state==androidx.work.WorkInfo.State.BLOCKED} || phase=="queued"
        val stateText=when{
            !il.ac.tau.lecturesummarizer.autosync.AutoSyncStore(app).autoAttach -> R.string.slides_disabled
            queued && (!online || metered)->R.string.slides_wait_wifi
            queued && lowBattery->R.string.slides_wait_battery
            queued->R.string.slides_queued
            phase=="checking"->R.string.slides_checking
            phase=="partial"->R.string.slides_partial
            phase=="auth"->R.string.slides_signin
            phase=="error"->R.string.slides_search_error
            phase=="no_course"->R.string.slides_choose_course
            phase=="no_match"->R.string.slides_missing
            else->R.string.slides_not_checked
        }
        if(phase!="suggestions" && (l.allSlides.isEmpty() || queued || phase in setOf("checking","partial","auth","error")))Text(stringResource(stateText),color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(phase=="auth")TextButton(onClick=onMoodle){Text(stringResource(R.string.connect))}
        if(l.state==LectureState.DONE)TextButton(enabled=il.ac.tau.lecturesummarizer.autosync.AutoSyncStore(app).autoAttach,onClick={il.ac.tau.lecturesummarizer.slides.SlideMatchWorker.enqueue(app,l.id,again=true)}){Text(stringResource(R.string.slides_check_again))}
        if(bundle.isNotEmpty()){
            Text(stringResource(R.string.slides_coverage_suggestion),style=MaterialTheme.typography.titleSmall)
            Text(bundle.joinToString(" + "){bidi(it.getString("name"))},style=MaterialTheme.typography.bodyMedium)
            bundle.forEach{item->item.optJSONObject("range")?.let{r->Text(stringResource(R.string.slides_named_range,bidi(item.getString("name")),ltr(r.optInt("start")),ltr(r.optInt("end"))),style=MaterialTheme.typography.bodySmall)};if(item.optBoolean("continued"))Text(stringResource(R.string.slides_continue_previous),style=MaterialTheme.typography.labelMedium)}
            Text(stringResource(R.string.slides_range_disclaimer),style=MaterialTheme.typography.bodySmall)
            Row{TextButton(enabled=editable,onClick={attach(bundle)}){Text(stringResource(R.string.slides_attach_selection))};TextButton(onClick={bundle.forEach{choices.reject(l.id,it.getLong("id"))}}){Text(stringResource(R.string.slides_reject))}}
        }
        if(candidates.isNotEmpty()){
            Text(stringResource(R.string.slides_suggest),style=MaterialTheme.typography.titleSmall)
            candidates.take(3).forEach{candidate->Text(bidi(candidate.getString("name")),maxLines=2);Text(stringResource(R.string.slides_provenance,candidate.optInt("termCount")),style=MaterialTheme.typography.bodySmall)
                Row{TextButton(enabled=editable,onClick={attach(listOf(candidate))}){Text(stringResource(R.string.slides_attach))};TextButton(onClick={choices.reject(l.id,candidate.getLong("id"))}){Text(stringResource(R.string.slides_reject))}}
            }
        }
        Row{TextButton(enabled=editable,onClick={picker=true}){Text(stringResource(if(l.allSlides.isEmpty())R.string.slides_upload else R.string.slides_add_deck))};TextButton(enabled=editable,onClick=onMoodle){Text(stringResource(R.string.slides_moodle))}}
        SnackbarHost(snackbar)
      }
    }
    if(picker)ModalBottomSheet(onDismissRequest={picker=false}){
      Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(stringResource(R.string.slides_add_deck),style=MaterialTheme.typography.titleLarge)
        candidates.take(5).forEach{candidate->TextButton(onClick={picker=false;attach(listOf(candidate))}){Text(bidi(candidate.getString("name")),maxLines=2)}}
        OutlinedButton(onClick={picker=false;onMoodle()}){Text(stringResource(R.string.slides_moodle))}
        OutlinedButton(onClick={picker=false;filePicker.launch(SLIDE_MIMES)}){Text(stringResource(R.string.slides_upload))}
      }
    }
    orderFor?.let{uri->val name=displayName(ctx,uri);DeckOrderDialog(choices.order(l.courseId,name),{orderFor=null}){value->choices.order(l.courseId,name,value);orderFor=null}}
    rangeFor?.let{uri->SlideRangeDialog(l.slideRanges[uri],{rangeFor=null}){range->val ranges=l.slideRanges.toMutableMap();if(range==null)ranges.remove(uri)else ranges[uri]=range;changed(l.allSlides,ranges);rangeFor=null}}
    if (why) ModalBottomSheet(onDismissRequest = { why = false }) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.slides_why), style = MaterialTheme.typography.titleLarge)
            choices.reasons(l.id).take(3).forEach { reason -> Text(when {
                reason == "same_course" -> stringResource(R.string.slides_same_course)
                reason == "lecture_number" -> stringResource(R.string.slides_number)
                reason.startsWith("terms:") -> stringResource(R.string.slides_terms, reason.substringAfter(':').toIntOrNull() ?: 0)
                else -> ""
            }) }
        }
    }
    if (offer) AlertDialog(onDismissRequest = { offer = false }, title = { Text(stringResource(R.string.slides_update_question)) },
        text = { Text(stringResource(R.string.slides_no_asr)) }, confirmButton = { TextButton(onClick = {
            offer = false
            il.ac.tau.lecturesummarizer.slides.SummaryRefreshWorker.enqueue(app,l.id)

        }) { Text(stringResource(R.string.slides_update)) } }, dismissButton = { TextButton(onClick = { offer = false }) { Text(stringResource(R.string.skip)) } })
}

@Composable private fun SlideRangeDialog(initial:SlideRange?,onDismiss:()->Unit,onSave:(SlideRange?)->Unit){
 var start by remember{mutableStateOf(initial?.start?.toString().orEmpty())};var end by remember{mutableStateOf(initial?.end?.toString().orEmpty())};var approximate by remember{mutableStateOf(initial?.approximate ?: true)}
 val full=start.isBlank() && end.isBlank();val parsed=runCatching{SlideRange(start.toInt(),end.toInt(),approximate)}.getOrNull()
 AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.slides_edit_range))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text(stringResource(R.string.slides_range_help));OutlinedTextField(start,{start=it.filter(Char::isDigit).take(5)},label={Text(stringResource(R.string.slides_range_start))},singleLine=true)
  OutlinedTextField(end,{end=it.filter(Char::isDigit).take(5)},label={Text(stringResource(R.string.slides_range_end))},singleLine=true)
  Row{Checkbox(approximate,{approximate=it});Text(stringResource(R.string.slides_approximate))}
  if(!full && parsed==null)Text(stringResource(R.string.slides_range_invalid),color=MaterialTheme.colorScheme.error)
 }},confirmButton={TextButton(enabled=full || parsed!=null,onClick={onSave(parsed)}){Text(stringResource(R.string.done_save))}},dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.copy_cancel))}})
}

@Composable private fun DeckOrderDialog(initial:Int?,onDismiss:()->Unit,onSave:(Int?)->Unit){
 var value by remember{mutableStateOf(initial?.toString().orEmpty())};val n=value.toIntOrNull()?.takeIf{it in 1..99}
 AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.slides_order))},text={Column{Text(stringResource(R.string.slides_order_help));OutlinedTextField(value,{value=it.filter(Char::isDigit).take(2)},singleLine=true,label={Text(stringResource(R.string.slides_order))})}},confirmButton={TextButton(enabled=value.isBlank() || n!=null,onClick={onSave(n)}){Text(stringResource(R.string.done_save))}},dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.copy_cancel))}})
}
