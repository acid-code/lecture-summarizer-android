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
   try{reconcile(SummaryVariantState(app),l.id,ApiProvider.get(app.settings).summaryJobStates(remote))}
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
