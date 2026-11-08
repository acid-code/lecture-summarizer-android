package il.ac.tau.lecturesummarizer.slides
import il.ac.tau.lecturesummarizer.data.*
import java.security.MessageDigest
/** API retries replace by filename. Disambiguate distinct local decks with the same friendly filename. */
fun slideUploadName(lecture:Lecture,uri:String,nameOf:(String)->String):String{
 val name=nameOf(uri)
 if(lecture.allSlides.count{nameOf(it).equals(name,true)}<2)return name
 val key=MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).take(6).joinToString(""){"%02x".format(it)}
 return "$key-$name"
}
