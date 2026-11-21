package il.ac.tau.lecturesummarizer.slides

import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Read-only reconciliation, coalesced across header, Home and course-list observers. */
object SummaryJobsSync {
 private val locks=mutableMapOf<String,Mutex>();private val checked=java.util.concurrent.ConcurrentHashMap<String,Long>()
 suspend fun refresh(app:LectureApp,l:Lecture)=withContext(Dispatchers.IO){
  val remote=l.remoteId ?: return@withContext
  val lock=synchronized(locks){locks.getOrPut(l.id){Mutex()}}
  lock.withLock {
   val now=System.currentTimeMillis();if(now-(checked[l.id]?:0)<15000)return@withLock
   checked[l.id]=now
   val api=ApiProvider.get(app.settings)
   try{
    val remoteStates=api.summaryJobStates(remote)
    reconcile(SummaryVariantState(app),l.id,remoteStates)
    // Chips alone don't advance orphaned derive jobs — kick them if nothing local is polling.
    val orphans=remoteStates.filter{(_,s)->s.state=="updating"&&s.jobId.isNotBlank()}
     .filter{(d,_)->
      val local=SummaryVariantState(app).entry(l.id,d)
      // Adopt orphans even under legacy synthetic pending tokens that block regenerate.
      local==null||local.token.startsWith("server:")||local.token=="legacy"||local.state=="error"||!local.pending
     }
     .mapValues{it.value.jobId}
    if(orphans.isNotEmpty()){
     runCatching{api.advanceAllJobs(remote)}
     SummaryRefreshWorker.adopt(app,l.id,orphans)
    }
   }
   catch(e:CancellationException){throw e}catch(_:Exception){/* Network errors never masquerade as Done or overwrite an active worker. */}
  }
 }
 fun reconcile(store:SummaryVariantState,id:String,remote:Map<String,SummaryJobState>){
  remote.forEach{(density,job)->
   val local=store.entry(id,density)
   if(local?.pending==true&&!local.token.startsWith("server:"))return@forEach // Locally queued work may not have reached the server yet.
   val token="server:${job.jobId}"
   if(local?.token!=token)store.claim(id,listOf(density),token)
   store.update(id,density,token,job.state,if(job.state=="ready")100 else 0,job.stage)
  }
 }
}
