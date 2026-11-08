package il.ac.tau.lecturesummarizer.slides

import android.content.Context
import androidx.work.*
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.autosync.AutoSyncStore
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.moodle.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Experimental retrieval, not confidence: real Biotech validation found false winners.
 * Search may suggest files, but must NEVER attach or rebuild a summary without a user tap. */
class SlideMatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as LectureApp
        val id=inputData.getString("lecture") ?: return Result.failure()
        val choices=SlideChoices(app)
        val l=app.repo.lecture(id) ?: return Result.success()
        if(!AutoSyncStore(app).autoAttach){choices.phase(id,"disabled");return Result.success()}
        if(l.state!=LectureState.DONE)return Result.success()
        val remote=l.remoteId ?: return finish(choices,id,"partial")
        val store=MoodleStore(app)
        val course=store.lectureCourse(id) ?: return finish(choices,id,"no_course")
        try {
            choices.phase(id,"checking")
            val transcript=ApiProvider.get(app.settings).transcript(remote)
            if(transcript==null || !transcript.complete) {
                choices.phase(id,"partial");return if(runAttemptCount<3)Result.retry()else Result.success()
            }
            val site=store.lectureBase(id)
            val client=MoodleClient.of(site)
            val allResources=client.sections(course).flatMap{it.files}.distinctBy{it.cmid}.filter{it.cmid.toString() !in choices.rejected(id)}
            var bytes=0L;var partial=allResources.size>60
            val resources=allResources.take(60).mapNotNull{f->
                try{val name=client.fileName(f) ?: f.name
                    if(SlideCandidates.isDeck(name,f.url))f.copy(name=name)else{if(!name.substringAfterLast('.',"").matches(Regex("[A-Za-z0-9]{2,8}")))partial=true;null}
                }catch(e:CancellationException){throw e}catch(e:MoodleAuthRequired){throw e}catch(_:Exception){partial=true;null}}
            val decks=resources.take(60).mapNotNull { f ->
                if(bytes>=120L*1024*1024){partial=true;return@mapNotNull null}
                try {
                    val file=store.cached(f.cmid,site) ?: client.download(f,store.cacheDir(site),maxBytes=40L*1024*1024).also{store.putCached(f.cmid,it,site)}
                    bytes+=file.length()
                    if(!SlideCandidates.isDeck(file.name)){partial=true;return@mapNotNull null}
                    val pages=SlideText.pages(app,file)
                    if(pages.size>=250)partial=true
                    val text=if(pages.isEmpty())SlideText.read(app,file)else pages.joinToString("\u000c")
                    if(text.length>=300_000)partial=true
                    if(text.isBlank()){partial=true;null}else SlideMatcher.Deck(f.cmid,file.name,text,SlideMatcher.explicitDate(f.name),pages)
                }catch(e:CancellationException){throw e}catch(e:MoodleAuthRequired){throw e}catch(_:Exception){partial=true;null}
            }
            val previous=app.repo.lectures.value.filter{it.courseId==l.courseId && it.id!=id && it.recordedAtMs<l.recordedAtMs && MoodleMatch.kindFromTitle(it.title)==MoodleMatch.kindFromTitle(l.title)}.maxByOrNull{it.recordedAtMs}
            val previousIds=previous?.allSlides.orEmpty().mapNotNull{raw->decks.firstOrNull{deck->
                val file=store.cached(deck.id,site)
                file!=null && androidx.core.content.FileProvider.getUriForFile(app,app.packageName+".files",file).toString()==raw
            }?.id ?: runCatching{val name=il.ac.tau.lecturesummarizer.ui.displayName(app,raw);decks.filter{deck->deck.name.replace(Regex("^\\d{4,}[_-]"),"").equals(name.replace(Regex("^\\d{4,}[_-]"),""),ignoreCase=true)}.singleOrNull()?.id}.getOrNull()}
            val order=decks.mapNotNull{d->choices.order(l.courseId,d.name)?.let{d.id to it}}.toMap()
            val result=ContinuationMatcher.suggest(decks,transcript.lines,previousIds,java.time.Instant.ofEpochMilli(l.recordedAtMs).atZone(java.time.ZoneId.of("Asia/Jerusalem")).toLocalDate(),order)
            val ranked=result.candidates.filter{it.terms.isNotEmpty()}
            fun json(m:SlideMatcher.Match)=JSONObject().put("id",m.deck.id).put("name",m.deck.name).put("terms",JSONArray(m.terms.take(12))).put("termCount",m.terms.size)
            choices.candidates(id,JSONArray().apply{ranked.take(5).forEach{put(json(it))}}.toString())
            choices.bundle(id,JSONArray().apply{result.suggestions.forEach{s->put(json(s.match).put("range",s.range?.toJson()).put("continued",s.continued).put("windows",s.evidenceWindows).put("pages",s.match.deck.pages.size))}}.toString())
            choices.phase(id,if(partial)"partial" else if(ranked.isEmpty())"no_match" else "suggestions")
            return Result.success()
        }catch(e:CancellationException){throw e}
        catch(_:MoodleAuthRequired){return finish(choices,id,"auth")}
        catch(_:Exception){choices.phase(id,"error");return if(runAttemptCount<3)Result.retry()else Result.success()}
    }
    private fun finish(c:SlideChoices,id:String,state:String):Result{c.phase(id,state);return Result.success()}
    companion object {
        fun enqueue(ctx:Context,id:String,again:Boolean=false) {
            val c=SlideChoices(ctx)
            if(!AutoSyncStore(ctx).autoAttach){c.phase(id,"disabled");return}
            if(!again && (c.phase(id) in setOf("suggestions","no_match","done")))return
            c.phase(id,"queued")
            WorkManager.getInstance(ctx).enqueueUniqueWork("slides-match-$id",if(again)ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SlideMatchWorker>().setInputData(workDataOf("lecture" to id))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
}

object SlideCandidates {
    private val ext=Regex("\\.(pdf|pptx?)(?:$|[?#])",RegexOption.IGNORE_CASE)
    fun isDeck(name:String,url:String="")=ext.containsMatchIn(name)||ext.containsMatchIn(url)
}
