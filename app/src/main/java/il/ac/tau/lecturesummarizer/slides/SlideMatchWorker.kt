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
        if(l.allSlides.isNotEmpty() || l.state!=LectureState.DONE)return Result.success()
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
                    val text=SlideText.read(app,file)
                    if(text.isBlank()){partial=true;null}else SlideMatcher.Deck(f.cmid,file.name,text)
                }catch(e:CancellationException){throw e}catch(e:MoodleAuthRequired){throw e}catch(_:Exception){partial=true;null}
            }
            val ranked=SlideMatcher.rank(decks,transcript.lines.joinToString(" "){it.text}).filter{it.terms.isNotEmpty()}
            choices.candidates(id,JSONArray().apply{ranked.take(5).forEach { m ->
                put(JSONObject().put("id",m.deck.id).put("name",m.deck.name).put("terms",JSONArray(m.terms.take(12))).put("termCount",m.terms.size))
            }}.toString())
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
            if(!again && (c.isManual(id) || c.phase(id) in setOf("suggestions","no_match","done")))return
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
