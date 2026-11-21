package il.ac.tau.lecturesummarizer.slides

import android.content.Context
import android.net.Uri
import androidx.work.*
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.ui.displayName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit user-approved rebuild. Retains every old PDF, persists each density/job across process death. */
class SummaryRefreshWorker(ctx: Context,params: WorkerParameters):CoroutineWorker(ctx,params) {
    private class RevisionChanged : Exception()

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
            val force=inputData.getBoolean("force",false)
            // Phase 1: start every needed density job (no polling yet).
            val active=linkedMapOf<String,String>()
            for(density in densities) {
                val dest=File(app.repo.pdfDir(),"$id-$density.pdf")
                if(!force && !f.outdated(app.repo.lecture(id)!!,density) && dest.exists()){
                    if(token!=null)variantState.update(id,density,token,"ready",100)
                    continue
                }
                if(token!=null)variantState.update(id,density,token,"updating",0)
                var job=f.pendingJob(id,density,rev)
                if(job==null){
                    val started=runCatching{api.resummarizeJob(source,density)}.getOrNull()
                    if(started==null){if(token!=null)variantState.update(id,density,token,"error",0);continue}
                    job=started.jobId ?: source; f.pendingJob(id,density,rev,job)
                }
                active[density]=job
            }
            // Phase 2: concurrent round-robin poll. One density failing never stops the others.
            val failed=mutableSetOf<String>()
            while(active.isNotEmpty()) {
                if(app.repo.lecture(id)?.let{f.revision(it)}!=rev){f.state(id,"changed");mark("error");return Result.success()}
                val snap=active.toList()
                val statuses=coroutineScope{
                    snap.map{(density,job)->async{Triple(density,job,runCatching{api.jobStatus(job)}.getOrNull())}}.awaitAll()
                }
                for((density,job,status) in statuses){
                    if(status==null)continue
                    if(token!=null)variantState.update(id,density,token,"updating",status.progress,status.stage)
                    when(status.state){
                        "failed"->{
                            f.pendingJob(id,density,rev,null);failed+=density;active.remove(density)
                            if(token!=null)variantState.update(id,density,token,"error",0)
                        }
                        "done"->{
                            active.remove(density)
                            try{finishDensity(app,id,remote,density,job,rev,token,variantState,densities)}
                            catch(_:RevisionChanged){f.state(id,"changed");mark("error");return Result.success()}
                        }
                    }
                }
                if(active.isEmpty())break
                if(System.currentTimeMillis()>until)return Result.retry()
                delay(3000)
            }
            densities.filter{it !in failed}.forEach{f.pendingJob(id,it,rev,null)}
            if(failed.isNotEmpty()){f.state(id,"error");return Result.success()}
            f.recordChange(id,rev,l.allSlides.size,densities);f.state(id,"ready")
            return Result.success()
        }catch(e:CancellationException){throw e}
        catch(_:Exception){f.state(id,"error");if(runAttemptCount>=3)mark("error");return if(runAttemptCount<3)Result.retry()else Result.failure()}
    }

    private suspend fun finishDensity(
        app:LectureApp,id:String,remote:String,density:String,job:String,rev:String,
        token:String?,variantState:SummaryVariantState,densities:List<String>,
    ) {
        val f=SummaryFreshness(app)
        val dest=File(app.repo.pdfDir(),"$id-$density.pdf")
        ApiProvider.get(app.settings).downloadSummaryPdf(remote,dest,density)
        SummaryPdfCache(app.repo.pdfDir(),id).setToken(density,"server:$job")
        if(app.repo.lecture(id)?.let{f.revision(it)}!=rev)throw RevisionChanged()
        try{
            val study=il.ac.tau.lecturesummarizer.study.StudyStore(app)
            if(!study.delivered(id,dest,density,job))study.refresh(ApiProvider.get(app.settings),id,remote,density,job)
        }catch(e:CancellationException){throw e}catch(_:Exception){/* Retain the old glossary until a successful refresh. */}
        f.built(id,density,rev)
        il.ac.tau.lecturesummarizer.ui.PdfPreviewCache.preload(dest)
        if(token!=null)variantState.update(id,density,token,"ready",100)
        runCatching{StudyIndex(app).put(id,"summary:$density",SlideText.read(app,dest))}
        app.repo.updateLecture(id){cur->
            if(cur.pdfPath==null || cur.pdfPath.endsWith("-$density.pdf") || densities.last()==density && densities.none{cur.pdfPath.endsWith("-$it.pdf")})
                cur.copy(pdfPath=dest.absolutePath) else cur
        }
    }

    companion object {
        fun enqueue(app:LectureApp,id:String,additionalDensity:String?=null){
            enqueueWork(app,id,(app.settings.summaryDensities+listOfNotNull(additionalDensity)).sorted(),force=true)
        }
        /** Missing-version generation never re-generates the other existing versions. Pass force=true for explicit re-summarize. */
        fun enqueueDensity(app:LectureApp,id:String,density:String,force:Boolean=false){
            if(SummaryDensity.of(density)==null)return
            // force=true (restart / chip retry) must not no-op on a stale pending/legacy claim.
            if(!force && SummaryVariantState(app).entry(id,density)?.pending==true)return
            enqueueWork(app,id,listOf(density),force=force||SummaryVariantState(app).entry(id,density)?.state=="error")
        }
        /** Adopt server-spawned density jobs (from options.densities) so they get polled + downloaded. */
        fun adopt(app:LectureApp,id:String,jobs:Map<String,String>){
            if(jobs.isEmpty())return
            val l=app.repo.lecture(id) ?: return
            val f=SummaryFreshness(app);val rev=f.revision(l)
            f.prepare(id,rev) // must run BEFORE seeding; prepare() clears job:* keys on a new revision
            jobs.forEach{(d,j)->f.pendingJob(id,d,rev,j)}
            enqueueWork(app,id,jobs.keys.sorted(),force=false)
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
