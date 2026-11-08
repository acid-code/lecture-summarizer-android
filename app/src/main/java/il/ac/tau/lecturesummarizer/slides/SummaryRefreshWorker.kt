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
        val token=inputData.getString("variantToken");val variantState=SummaryVariantState(app)
        val densities=inputData.getStringArray("densities")?.toList() ?: app.settings.summaryDensities.sorted()
        fun mark(state:String,progress:Int=0){if(token!=null)densities.forEach{variantState.update(id,it,token,state,progress)}}
        if(rev!=f.revision(l)){f.state(id,"changed");mark("error");return Result.success()}
        f.prepare(id,rev)
        if(l.state !in setOf(LectureState.DONE,LectureState.FAILED)){f.state(id,"queued");return Result.retry()}
        val api=ApiProvider.get(app.settings)
        try {
            f.state(id,"updating")
            if(l.slideRanges.keys.any{it in l.allSlides} && !api.supportsSlideRanges(remote)){f.state(id,"api_required");mark("error");return Result.failure()}
            // First attempt syncs all current decks. Later attempts resume already-started density jobs.
            if(densities.none{f.pendingJob(id,it,rev)!=null}) {
                if(l.allSlides.isEmpty())api.clearSlides(remote)
                l.allSlides.forEachIndexed{index,raw->
                    val uri=Uri.parse(raw)
                    val localName=displayName(app,raw)
                    val uploadName=slideUploadName(l,raw){displayName(app,it)}
                    val deck=DeckUpload.open(app,uri,localName)
                    val remoteName=when{
                        deck.mime=="application/pdf" && uploadName.endsWith(".pptx",true)->uploadName.replace(Regex("\\.pptx$",RegexOption.IGNORE_CASE),".pdf")
                        deck.mime=="application/pdf" && !uploadName.endsWith(".pdf",true)->deck.name
                        else->uploadName
                    }
                    deck.stream.use{api.uploadSlides(remote,remoteName,deck.mime,it,replaceAll=index==0,range=l.slideRanges[raw])}
                }
                app.repo.updateLecture(id){it.copy(uploadedSlides=it.allSlides)}
            }
            val until=System.currentTimeMillis()+7*60_000
            for(density in densities) {
                val dest=File(app.repo.pdfDir(),"$id-$density.pdf")
                if(!inputData.getBoolean("force",false) && !f.outdated(app.repo.lecture(id)!!,density) && dest.exists()){
                    if(token!=null)variantState.update(id,density,token,"ready",100)
                    continue
                }
                if(token!=null)variantState.update(id,density,token,"updating",0)
                var job=f.pendingJob(id,density,rev)
                if(job==null){val started=api.resummarizeJob(source,density);job=started.jobId ?: source;f.pendingJob(id,density,rev,job)}
                while(true) {
                    if(app.repo.lecture(id)?.let{f.revision(it)}!=rev){f.state(id,"changed");mark("error");return Result.success()}
                    val status=api.jobStatus(job)
                    if(token!=null)variantState.update(id,density,token,"updating",status.progress,status.stage)
                    if(status.state=="failed"){f.pendingJob(id,density,rev,null);error("summary_refresh_failed")}
                    if(status.state=="done")break
                    if(System.currentTimeMillis()>until)return Result.retry()
                    delay(3000)
                }
                api.downloadSummaryPdf(remote,dest,density)
                if(app.repo.lecture(id)?.let{f.revision(it)}!=rev){f.state(id,"changed");mark("error");return Result.success()}
                try{val study=il.ac.tau.lecturesummarizer.study.StudyStore(app);if(!study.delivered(id,dest,density,job))study.refresh(api,id,remote,density,job)}catch(e:CancellationException){throw e}catch(_:Exception){/* Retain the old glossary until a successful refresh. */}
                f.built(id,density,rev)
                // Warm the render before making the newly generated chip ready.
                il.ac.tau.lecturesummarizer.ui.PdfPreviewCache.preload(dest)
                if(token!=null)variantState.update(id,density,token,"ready",100)
                runCatching{StudyIndex(app).put(id,"summary:$density",SlideText.read(app,dest))}
                app.repo.updateLecture(id){cur->if(cur.pdfPath==null || cur.pdfPath.endsWith("-$density.pdf") || densities.last()==density && densities.none{cur.pdfPath.endsWith("-$it.pdf")})cur.copy(pdfPath=dest.absolutePath)else cur}
            }
            densities.forEach{f.pendingJob(id,it,rev,null)};f.recordChange(id,rev,l.allSlides.size,densities);f.state(id,"ready")
            return Result.success()
        }catch(e:CancellationException){throw e}
        catch(_:Exception){f.state(id,"error");if(runAttemptCount>=3)mark("error");return if(runAttemptCount<3)Result.retry()else Result.failure()}
    }
    companion object {
        fun enqueue(app:LectureApp,id:String,additionalDensity:String?=null){
            enqueueWork(app,id,(app.settings.summaryDensities+listOfNotNull(additionalDensity)).sorted(),force=true)
        }
        /** Missing-version generation never re-generates the other existing versions. */
        fun enqueueDensity(app:LectureApp,id:String,density:String){
            if(SummaryDensity.of(density)==null||SummaryVariantState(app).entry(id,density)?.pending==true)return
            enqueueWork(app,id,listOf(density),force=SummaryVariantState(app).entry(id,density)?.state=="error")
        }
        private fun enqueueWork(app:LectureApp,id:String,densities:List<String>,force:Boolean){
            val l=app.repo.lecture(id) ?: return
            if(l.remoteId==null||l.jobId==null)return
            val f=SummaryFreshness(app);val rev=f.revision(l)
            if(force)f.ensureDirty(id)
            f.state(id,"queued")
            val token=java.util.UUID.randomUUID().toString();SummaryVariantState(app).claim(id,densities,token)
            WorkManager.getInstance(app).enqueueUniqueWork("summary-material-refresh-$id",ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SummaryRefreshWorker>().setInputData(workDataOf("lecture" to id,"revision" to rev,"densities" to densities.toTypedArray(),"force" to force,"variantToken" to token))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
}
