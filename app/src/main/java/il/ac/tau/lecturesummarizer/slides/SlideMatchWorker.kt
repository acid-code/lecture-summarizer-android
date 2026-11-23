package il.ac.tau.lecturesummarizer.slides

import android.content.Context
import androidx.core.content.FileProvider
import androidx.work.*
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.autosync.AutoSyncStore
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.moodle.*
import il.ac.tau.lecturesummarizer.ui.displayName
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Post-DONE Moodle deck search. Confident continuation bundles (previous-week reuse + next deck with
 * transcript evidence) auto-attach when the lecture is empty or already auto-managed; weaker term
 * hits stay as suggestions for a tap. Manual edits always win.
 */
class SlideMatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as LectureApp
        val id=inputData.getString("lecture") ?: return Result.failure()
        val choices=SlideChoices(app)
        val l=app.repo.lecture(id) ?: return Result.success()
        if(!AutoSyncStore(app).autoAttach){choices.phase(id,"disabled");return Result.success()}
        if(l.state!=LectureState.DONE)return Result.success()
        if(choices.isManual(id)){choices.phase(id,"manual");return Result.success()}
        val remote=l.remoteId ?: return finish(choices,id,"partial")
        val store=MoodleStore(app)
        val site=store.lectureBase(id)
        val client=MoodleClient.of(site)
        // Resolve Moodle course either from per-lecture link (AutoSync) or from the local Course mapping/rank (recorded lectures).
        val course=store.lectureCourse(id) ?: run {
            val localCourse=app.repo.course(l.courseId) ?: return finish(choices,id,"no_course")
            val mapped=il.ac.tau.lecturesummarizer.sources.SourceMappingStore(app).forName(app,localCourse.name)?.firstOrNull{it.providerId=="moodle"}?.courseId?.takeIf{it>0}
            val resolved=mapped ?: runCatching{
                val ranked=MoodleMatch.rank(client.courses(),localCourse.courseNumber,MoodleMatch.kindFromTitle(l.title),localCourse.name)
                MoodleMatch.confident(ranked)?.id
            }.getOrNull()
            if(resolved!=null)store.setLectureSource(id,resolved,l.title)
            resolved
        } ?: return finish(choices,id,"no_course")
        try {
            choices.phase(id,"checking")
            val transcript=ApiProvider.get(app.settings).transcript(remote)
            if(transcript==null || !transcript.complete) {
                choices.phase(id,"partial");return if(runAttemptCount<3)Result.retry()else Result.success()
            }
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
                file!=null && FileProvider.getUriForFile(app,app.packageName+".files",file).toString()==raw
            }?.id ?: runCatching{val name=displayName(app,raw);decks.filter{deck->deck.name.replace(Regex("^\\d{4,}[_-]"),"").equals(name.replace(Regex("^\\d{4,}[_-]"),""),ignoreCase=true)}.singleOrNull()?.id}.getOrNull()}
            val order=decks.mapNotNull{d->choices.order(l.courseId,d.name)?.let{d.id to it}}.toMap()
            val result=ContinuationMatcher.suggest(decks,transcript.lines,previousIds,java.time.Instant.ofEpochMilli(l.recordedAtMs).atZone(java.time.ZoneId.of("Asia/Jerusalem")).toLocalDate(),order)
            val ranked=result.candidates.filter{it.terms.isNotEmpty()}
            fun json(m:SlideMatcher.Match)=JSONObject().put("id",m.deck.id).put("name",m.deck.name).put("terms",JSONArray(m.terms.take(12))).put("termCount",m.terms.size)
            val bundleJson=JSONArray().apply{result.suggestions.forEach{s->put(json(s.match).put("range",s.range?.toJson()).put("continued",s.continued).put("windows",s.evidenceWindows).put("pages",s.match.deck.pages.size))}}
            choices.candidates(id,JSONArray().apply{ranked.take(5).forEach{put(json(it))}}.toString())
            choices.bundle(id,bundleJson.toString())

            val live=app.repo.lecture(id) ?: return Result.success()
            val canAuto= !choices.isManual(id) && (live.allSlides.isEmpty() || choices.isAuto(id))
            fun applyMatches(matches: List<SlideMatcher.Match>, rangesIn: Map<Long, SlideRange?>, reason: String): Boolean {
                val ranges = live.slideRanges.toMutableMap()
                val uris = live.allSlides.toMutableList()
                val names = mutableListOf<String>()
                var changed = false
                for (m in matches) {
                    val file = store.cached(m.deck.id, site) ?: continue
                    val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file).toString()
                    if (uri !in uris) { uris += uri; changed = true }
                    rangesIn[m.deck.id]?.let { ranges[uri] = it; changed = true }
                    names += file.name
                }
                if (!changed || uris.isEmpty()) return false
                choices.auto(id, names.firstOrNull().orEmpty(), listOf("same_course", reason))
                app.repo.updateLecture(id) {
                    it.copy(
                        slidesUri = uris.first(),
                        extraSlides = uris.drop(1),
                        slideRanges = ranges.filterKeys { key -> key in uris },
                        uploadedSlides = emptyList(),
                    )
                }
                choices.bundle(id, null)
                choices.phase(id, "done")
                SummaryRefreshWorker.enqueue(app, id)
                return true
            }
            if (canAuto && ContinuationMatcher.confidentForAuto(result)) {
                val rangeMap = result.suggestions.associate { it.match.deck.id to it.range }
                val reason = when {
                    result.suggestions.any { it.continued } -> "previous_lecture"
                    else -> "terms:${result.suggestions.maxOf { it.match.terms.size }}"
                }
                if (applyMatches(result.suggestions.map { it.match }, rangeMap, reason)) return Result.success()
            }
            // Ambiguous bundle (or unnumbered/skipped-week deck filtered out of sequence) → auto the single strongest transcript hit when empty or carryover.
            if (canAuto && (live.allSlides.isEmpty() || choices.phase(id) == "carryover")) {
                val pool = if (ranked.isNotEmpty()) ranked else SlideMatcher.rank(decks, transcript.lines.joinToString(" ") { it.text }, java.time.Instant.ofEpochMilli(l.recordedAtMs).atZone(java.time.ZoneId.of("Asia/Jerusalem")).toLocalDate()).filter { it.terms.isNotEmpty() }
                val top = pool.firstOrNull { it.terms.size >= 4 }
                val second = pool.getOrNull(1)?.terms?.size ?: 0
                if (top != null && top.terms.size > second && applyMatches(listOf(top), emptyMap(), "terms:${top.terms.size}")) {
                    return Result.success()
                }
            }

            choices.phase(id,if(partial)"partial" else if(ranked.isEmpty())"no_match" else "suggestions")
            return Result.success()
        }catch(e:CancellationException){throw e}
        catch(_:MoodleAuthRequired){ConnectionAuth.markMoodleNeeded(app);return finish(choices,id,"auth")}
        catch(_:Exception){choices.phase(id,"error");return if(runAttemptCount<3)Result.retry()else Result.success()}
    }
    private fun finish(c:SlideChoices,id:String,state:String):Result{c.phase(id,state);return Result.success()}
    companion object {
        private const val RECENT_WINDOW_MS = 14L * 86_400_000L
        private const val RECHECK_INTERVAL_MS = 12L * 3_600_000L

        /**
         * University-cadence sweep (weekly/biweekly classes): re-check recent DONE lectures (last 14d, at most once/12h)
         * for late-uploaded Moodle slides, and auto-refresh summaries whose slides are genuinely outdated.
         */
        fun enqueuePending(app: LectureApp) {
            if (!AutoSyncStore(app).autoAttach) return
            val now = System.currentTimeMillis()
            val choices = SlideChoices(app)
            val freshness = SummaryFreshness(app)
            val summaryLevels = app.settings.summaryDensities.filter { SummaryDensity.of(it) != null }
            val prefs = app.getSharedPreferences("slide_match_recheck", Context.MODE_PRIVATE)
            var matched = 0
            for (l in app.repo.lectures.value.sortedByDescending { it.recordedAtMs }) {
                if (l.state != LectureState.DONE || l.remoteId == null || l.jobId == null) continue
                // 1) Auto-refresh summaries whose slides changed (equivalent to tapping "Update summary levels").
                if (summaryLevels.any { freshness.outdated(l, it) } && freshness.state(l.id) !in setOf("queued", "updating", "error")) {
                    SummaryRefreshWorker.enqueue(app, l.id)
                }
                // 2) Re-check recent lectures with missing slides or carryover/no_match (throttled to 12h per lecture).
                if (now - l.recordedAtMs > RECENT_WINDOW_MS) continue
                if (choices.isManual(l.id)) continue
                val ph = choices.phase(l.id)
                val needsCheck = (l.allSlides.isEmpty() && ph in setOf(null, "", "no_match", "no_course", "partial", "error")) || ph == "carryover"
                if (!needsCheck) continue
                if (now - prefs.getLong("at:${l.id}", 0L) < RECHECK_INTERVAL_MS) continue
                prefs.edit().putLong("at:${l.id}", now).apply()
                enqueue(app, l.id, again = true)
                if (++matched >= 3) break // bound Moodle downloads per sweep
            }
        }

        fun enqueue(ctx:Context,id:String,again:Boolean=false) {
            val c=SlideChoices(ctx)
            if(!AutoSyncStore(ctx).autoAttach){c.phase(id,"disabled");return}
            if(!again && (c.phase(id) in setOf("suggestions","no_match","done")))return
            c.phase(id,"queued")
            // CONNECTED (not only unmetered): matching + auto-refresh should not wait for Wi‑Fi forever.
            WorkManager.getInstance(ctx).enqueueUniqueWork("slides-match-$id",if(again)ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SlideMatchWorker>().setInputData(workDataOf("lecture" to id))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
}

object SlideCandidates {
    private val ext=Regex("\\.(pdf|pptx?)(?:$|[?#])",RegexOption.IGNORE_CASE)
    fun isDeck(name:String,url:String="")=ext.containsMatchIn(name)||ext.containsMatchIn(url)
}
