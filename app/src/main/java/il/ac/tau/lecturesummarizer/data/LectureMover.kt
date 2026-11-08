package il.ac.tau.lecturesummarizer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Server first, then an atomic local relationship update. Failure leaves the lecture in its old course. */
object LectureMover {
 private val lock=Mutex()
 suspend fun move(repo:Repository,api:LectureApi,id:String,targetId:String)=withContext(Dispatchers.IO){lock.withLock{
  val lecture=repo.lecture(id) ?: error("lecture_deleted")
  val target=repo.course(targetId) ?: error("course_deleted")
  if(lecture.courseId==targetId)return@withLock
  check(lecture.remoteId!=null||lecture.state!=LectureState.UPLOADING){"lecture_upload_in_progress"}
  if(lecture.remoteId!=null){
   val remote=target.remoteId ?: api.createCourse(target.name).also{rid->repo.updateCourse(target.id){it.copy(remoteId=rid)}}
   api.moveLecture(lecture.remoteId,remote)
  }
  repo.moveLecture(id,targetId) // copy(courseId=...) preserves every attachment, marker, summary and id.
 }}
}
