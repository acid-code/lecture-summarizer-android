package il.ac.tau.lecturesummarizer.slides

import android.content.Context
import android.net.Uri
import androidx.work.*
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.ui.displayName
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit user-approved rebuild. Retains every old PDF, persists each density/job across process death. */
class SummaryRefreshWorker(ctx: Context,params: WorkerParameters):CoroutineWorker(ctx,params) {
    override suspend fun doWork():Result {
        val app=applicationContext as LectureApp;val id=inputData.getString("lecture") ?: return Result.failure()
        val l=app.repo.lecture(id) ?: return Result.success();val remote=l.remoteId ?: return Result.failure();val source=l.jobId ?: return Result.failure()
        val rev=inputData.getString("revision") ?: return Result.failure();val f=SummaryFreshness(app)
        if(rev!=f.revision(l)){f.state(id,"changed");return Result.success()}
        f.prepare(id,rev)
        val densities=inputData.getStringArray("densities")?.toList() ?: app.settings.summaryDensities.sorted()
        if(l.state !in setOf(LectureState.DONE,LectureState.FAILED)){f.state(id,"queued");return Result.retry()}
        val api=ApiProvider.get(app.settings)
        try {
            f.state(id,"updating")
            if(l.slideRanges.keys.any{it in l.allSlides} && !api.supportsSlideRanges(remote)){f.state(id,"api_required");return Result.failure()}
            // First attempt syncs all current decks. Later attempts resume already-started density jobs.
            if(densities.none{f.pendingJob(id,it,rev)!=null}) {
                if(l.allSlides.isEmpty())api.clearSlides(remote)
                l.allSlides.forEachIndexed{index,raw->val uri=Uri.parse(raw);app.contentResolver.openInputStream(uri)!!.use{
                    api.uploadSlides(remote,slideUploadName(l,raw){displayName(app,it)},app.contentResolver.getType(uri) ?: "application/pdf",it,replaceAll=index==0,range=l.slideRanges[raw])
                }}
                app.repo.updateLecture(id){it.copy(uploadedSlides=it.allSlides)}
            }
            val until=System.currentTimeMillis()+7*60_000
            for(density in densities) {
                if(!f.outdated(app.repo.lecture(id)!!,density))continue
                var job=f.pendingJob(id,density,rev)
                if(job==null){val started=api.resummarizeJob(source,density);job=started.jobId ?: source;f.pendingJob(id,density,rev,job)}
                while(true) {
                    if(app.repo.lecture(id)?.let{f.revision(it)}!=rev){f.state(id,"changed");return Result.success()}
                    val status=api.jobStatus(job)
                    if(status.state=="failed"){f.pendingJob(id,density,rev,null);error("summary_refresh_failed")}
                    if(status.state=="done")break
                    if(System.currentTimeMillis()>until)return Result.retry()
                    delay(3000)
                }
                val dest=File(app.repo.pdfDir(),"$id-$density.pdf")
                api.downloadSummaryPdf(remote,dest,density)
                if(app.repo.lecture(id)?.let{f.revision(it)}!=rev){f.state(id,"changed");return Result.success()}
                try{il.ac.tau.lecturesummarizer.study.StudyStore(app).refresh(api,id,remote,density,job)}catch(e:CancellationException){throw e}catch(_:Exception){/* Retain the old glossary until a successful refresh. */}
                f.built(id,density,rev)
                runCatching{StudyIndex(app).put(id,"summary:$density",SlideText.read(app,dest))}
                app.repo.updateLecture(id){cur->if(cur.pdfPath==null || cur.pdfPath.endsWith("-$density.pdf") || densities.last()==density && densities.none{cur.pdfPath.endsWith("-$it.pdf")})cur.copy(pdfPath=dest.absolutePath)else cur}
            }
            densities.forEach{f.pendingJob(id,it,rev,null)};f.recordChange(id,rev,l.allSlides.size,densities);f.state(id,"ready")
            return Result.success()
        }catch(e:CancellationException){throw e}
        catch(_:Exception){f.state(id,"error");return if(runAttemptCount<3)Result.retry()else Result.failure()}
    }
    companion object {
        fun enqueue(app:LectureApp,id:String,additionalDensity:String?=null){
            val l=app.repo.lecture(id) ?: return
            val f=SummaryFreshness(app);val rev=f.revision(l)
            f.ensureDirty(id);f.state(id,"queued")
            WorkManager.getInstance(app).enqueueUniqueWork("summary-material-refresh-$id",ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SummaryRefreshWorker>().setInputData(workDataOf("lecture" to id,"revision" to rev,"densities" to (app.settings.summaryDensities + listOfNotNull(additionalDensity)).sorted().toTypedArray()))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
}
