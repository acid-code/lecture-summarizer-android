package il.ac.tau.lecturesummarizer.autosync

import il.ac.tau.lecturesummarizer.data.Lecture
import il.ac.tau.lecturesummarizer.data.LectureState

data class SyncQueueEntry(val key:String,val category:String,val course:String,val title:String,val start:Long,val lecture:Lecture?=null)
object SyncCategories {
    fun category(raw:String,lecture:Lecture?):String = when(raw.substringBefore(':')){
        "claim"->"queued"
        "done"->when(lecture?.state){LectureState.DONE,null->"done";LectureState.FAILED->"failed";else->"queued"}
        else->raw.substringBefore(':')
    }
}
