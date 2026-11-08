package il.ac.tau.lecturesummarizer.sources

import il.ac.tau.lecturesummarizer.moodle.*
import il.ac.tau.lecturesummarizer.slides.SlideCandidates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class MoodleDataSource:DataSource {
    override val providerId="moodle"
    override suspend fun items(config:SourceConfig):SourceListing=withContext(Dispatchers.IO){
        require(config.valid && config.courseId!=null)
        val client=MoodleClient.of(config.baseUrl)
        val resources=client.sections(config.courseId).flatMap{it.files}.distinctBy{it.cmid}
        val items=resources.take(100).mapNotNull{f->val name=client.fileName(f) ?: f.name
            if(!SlideCandidates.isDeck(name,f.url))null else SourceItem(providerId,f.cmid.toString(),name,f.url,material=true)}
        SourceListing(items,resources.size>100)
    }
}
class PanoptoDataSource:DataSource {
    override val providerId="panopto"
    override suspend fun items(config:SourceConfig):SourceListing=withContext(Dispatchers.IO){
        require(config.valid)
        val client=MoodleClient.of(config.baseUrl)
        val records=when {
            config.sessionId.isNotBlank()->listOf(PanoptoRecording(config.sessionId,config.sessionId,"${config.baseUrl.trimEnd('/')}/Panopto/Pages/Viewer.aspx?id=${config.sessionId}"))
            config.folderId.isNotBlank()->client.panoptoFolderSessions(config.baseUrl,config.folderId)
            config.courseId!=null->client.panoptoRecordings(config.courseId)
            else->emptyList()
        }
        // Date/time is enriched from the session itself, not guessed from folder order.
        val enriched=records.take(100).map{r->kotlinx.coroutines.currentCoroutineContext().ensureActive();if(r.startMs!=null)r else runCatching{r.copy(startMs=client.deliveryInfo(r).sessionStartMs)}.getOrElse{e->if(e is PanoptoAuthRequired)throw e;r}}
        SourceListing(enriched.map{r->SourceItem(providerId,r.deliveryId,r.title,r.viewerUrl,r.startMs)},records.size>100)
    }
}
class DataSourceRegistry(providers:List<DataSource> = listOf(MoodleDataSource(),PanoptoDataSource())) {
    private val entries=providers.associateBy{it.providerId}
    fun provider(id:String)=entries[id]
    suspend fun preview(event:SourceEvent,configs:List<SourceConfig>):List<SourceMatch> = configs.flatMap{c->
        val provider=entries[c.providerId] ?: error("unsupported_source_provider")
        provider.matcher.rank(event,c,provider.items(c).items)
    }.sortedByDescending{it.score}
}
