package il.ac.tau.lecturesummarizer.sources
object SourceIds {
    fun course(value:String):Long?=value.trim().toLongOrNull()?.takeIf{it>0} ?: runCatching{val uri=java.net.URI(value.trim());require(uri.scheme=="https");uri.rawQuery?.split('&')?.firstOrNull{it.substringBefore('=')=="id"}?.substringAfter('=')?.toLongOrNull()?.takeIf{it>0}}.getOrNull()
    fun courseSite(value:String):String?=runCatching{val u=java.net.URI(value.trim());require(u.scheme=="https" && u.userInfo==null);java.net.URI(u.scheme,null,u.host,u.port,u.path.substringBefore("/course/").trimEnd('/'),null,null).toString()}.getOrNull()
    fun guid(value:String,key:String):String? {
        if(value.isBlank())return null
        val raw=runCatching{java.net.URLDecoder.decode(value.trim(),"UTF-8")}.getOrDefault(value.trim())
        val candidate=if(raw.matches(Regex("[0-9a-fA-F-]{36}")))raw else Regex("(?:[?#&\\\"{]|^)$key[\\\"\\s]*[=:][\\\"\\s]*([0-9a-fA-F-]{36})",RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
        return candidate?.takeIf{it.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))}?.lowercase()
    }
}
