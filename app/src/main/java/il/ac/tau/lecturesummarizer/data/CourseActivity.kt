package il.ac.tau.lecturesummarizer.data

import android.content.Context

/** Content timestamp, not the lecture date. Old installs stay 'not updated yet' until a real write. */
class CourseActivity(ctx:Context){
    val prefs=ctx.getSharedPreferences("course_activity",Context.MODE_PRIVATE)
    fun updated(id:String)=prefs.getLong(id,0)
    fun touch(id:String,now:Long=System.currentTimeMillis()){prefs.edit().putLong(id,now).apply()}
    fun remove(id:String){prefs.edit().remove(id).apply()}
}
