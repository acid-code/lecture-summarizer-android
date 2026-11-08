package il.ac.tau.lecturesummarizer.sources
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class DataSourceTest {
 private val begin=ZonedDateTime.of(2026,11,8,10,0,0,0,ZoneId.of("Asia/Jerusalem")).toInstant().toEpochMilli()
 private val event=SourceEvent("Biotech Lecture 3",begin,begin+3600_000)
 private val config=SourceConfig("panopto","https://college.hosted.panopto.com",courseId=123)
 private val item=SourceItem("panopto","r","Biotech Lecture 3","https://college.hosted.panopto.com/Panopto/Pages/Viewer.aspx?id=r",begin)
 @Test fun noInstitutionSpecificHostIsRequired(){assertTrue(config.valid);assertTrue(config.copy(baseUrl="https://another.example.edu:8443").valid);assertFalse(config.copy(baseUrl="http://insecure.example.edu").valid)}
 @Test fun matchingReportsDateTimeNumberAndTitleReasons(){val r=SourceMatcher.rank(event,config,listOf(item)).single();assertTrue(r.reasons.containsAll(listOf("date","time","number","tokens:1")));assertEquals(item,SourceMatcher.clearRecording(listOf(r)))}
 @Test fun datesAndToleranceUseTheEventCalendarTimezone(){assertTrue(SourceMatcher.rank(event,config.copy(toleranceMinutes=5),listOf(item.copy(start=begin+6*60_000))).isEmpty());assertTrue(SourceMatcher.rank(event,config,listOf(item.copy(start=begin+86_400_000))).isEmpty())}
 @Test fun wrongLectureNumberAndWrongProviderNeverMatch(){assertTrue(SourceMatcher.rank(event,config,listOf(item.copy(title="Lecture 4"))).isEmpty());assertTrue(SourceMatcher.rank(event,config,listOf(item.copy(providerId="future"))).isEmpty())}
 @Test fun disablingDateAndTimeDoesNotSelectArbitraryFolderItem(){assertTrue(SourceMatcher.rank(event,config.copy(matchDate=false,matchTime=false),listOf(item.copy(title="Unrelated",start=null))).isEmpty())}
 @Test fun equalRecordingsAreSuggestionsNotAnAutomaticWinner(){val ranked=SourceMatcher.rank(event,config,listOf(item,item.copy(id="another")));assertNull(SourceMatcher.clearRecording(ranked))}
 @Test fun materialIsNeverSelectedAsARecording(){assertNull(SourceMatcher.clearRecording(listOf(SourceMatch(item.copy(material=true),200,listOf("number")))))}
 @Test fun lectureNumberPatternIsEditableAndBounded(){assertEquals(7,SourceMatcher.number("Week_07", "(?i)week_(\\d+)"));assertFalse(SourceMatcher.safePattern("(a+)+"));assertFalse(SourceMatcher.safePattern("("));assertTrue(SourceMatcher.safePattern(SourceConfig.DEFAULT_NUMBER_PATTERN))}
 @Test fun providerRegistryIsExtensible(){val extra=object:DataSource{override val providerId="future";override suspend fun items(config:SourceConfig)=SourceListing(emptyList())};assertEquals(extra,DataSourceRegistry(listOf(extra)).provider("future"));assertNull(DataSourceRegistry(listOf(extra)).provider("panopto"))}
 @Test fun providerMayOverrideTheMatchingStrategy(){
  val custom=object:SourceItemMatcher{override fun rank(event:SourceEvent,config:SourceConfig,items:List<SourceItem>)=listOf(SourceMatch(item,1,listOf("custom")))}
  val provider=object:DataSource{override val providerId="custom";override val matcher=custom;override suspend fun items(config:SourceConfig)=SourceListing(listOf(item))}
  assertEquals("custom",provider.matcher.rank(event,config,listOf(item)).single().reasons.single())
 }
 @Test fun acceptsCoursePagesAndFolderSessionUrls(){val guid="01234567-89ab-cdef-0123-456789abcdef";assertEquals(123L,SourceIds.course("https://another.example.edu/moodle/course/view.php?id=123"));assertEquals("https://another.example.edu/moodle",SourceIds.courseSite("https://another.example.edu/moodle/course/view.php?id=123"));assertEquals(guid,SourceIds.guid("https://college.hosted.panopto.com/Panopto/Pages/Sessions/List.aspx#folderID=%22$guid%22","folderID"));assertEquals(guid,SourceIds.guid("https://college.hosted.panopto.com/Panopto/Pages/Viewer.aspx?id=$guid","id"));assertNull(SourceIds.guid("not-a-guid","id"))}
}
