package il.ac.tau.lecturesummarizer.ui

import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import il.ac.tau.lecturesummarizer.LectureApp

@Composable
fun AppNav(
    app: LectureApp,
    shared: MutableState<List<Uri>>,
    openLecture: MutableState<String?> = mutableStateOf(null),
    reconnect: MutableState<String?> = mutableStateOf(null),
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route=entry?.destination?.route ?: "home"
    // Swallow click-through from a course cover onto a lecture row (incl. a reused lectures/{id} entry).
    var suppressLectureNavUntil by remember { mutableLongStateOf(0L) }
    fun allowLectureNav(): Boolean = SystemClock.elapsedRealtime() >= suppressLectureNavUntil
    fun recordCourse(courseId:String){val l=app.repo.addLecture(courseId,app.getString(il.ac.tau.lecturesummarizer.R.string.generated_lecture,java.text.DateFormat.getDateInstance().format(java.util.Date())));nav.navigate("record/${l.id}")}

    LaunchedEffect(openLecture.value) {
        val id = openLecture.value ?: return@LaunchedEffect
        openLecture.value = null
        val courseId = app.repo.lectures.value.firstOrNull { it.id == id }?.courseId
        nav.navigate("courses") { popUpTo(0) { inclusive = true }; launchSingleTop = true }
        if (courseId != null) nav.navigate("lectures/$courseId")
        nav.navigate("job/$id")
    }
    LaunchedEffect(reconnect.value) {
        val target = when (reconnect.value) {
            il.ac.tau.lecturesummarizer.MainActivity.RECONNECT_MOODLE -> "moodleConnect"
            il.ac.tau.lecturesummarizer.MainActivity.RECONNECT_PANOPTO -> "panoptoConnect"
            else -> null
        } ?: return@LaunchedEffect
        reconnect.value = null
        nav.navigate(target) { launchSingleTop = true }
    }
    LaunchedEffect(shared.value) { if (shared.value.isNotEmpty()) nav.navigate("share") { launchSingleTop = true } }
    // Existing installs that already have notebooks skip onboarding.
    val start = androidx.compose.runtime.remember { if (app.settings.onboardingDone || app.repo.courses.value.isNotEmpty()) "home" else "onboarding" }
    // Flat NavHost: never saveState/restoreState on tabs — that restores a prior lectures→job stack and
    // looks like "tapping a course opened a random lecture".
    // Block bottom-tab taps briefly after any route change (same "ghost previous screen" issue).
    var tabReady by remember { mutableStateOf(true) }
    LaunchedEffect(route) { tabReady = false; kotlinx.coroutines.delay(300); tabReady = true }
    Scaffold(bottomBar={if(route in setOf("home","courses","search"))MainTabs(route){target->
        if (!tabReady) return@MainTabs
        nav.navigate(target){popUpTo("home"){saveState=false};launchSingleTop=true}
    }}){outer->
    NavHost(nav, startDestination = start,modifier=Modifier.padding(PaddingValues(bottom=outer.calculateBottomPadding()))) {
        composable("home"){HomeScreen(app,onOpen={nav.navigate("job/$it")},onSettings={nav.navigate("settings")},onCourses={nav.navigate("courses"){launchSingleTop=true}},onRecord={recordCourse(it)},onPanopto={course,start->nav.navigate("panoptoCourse/$course?start=${start ?: 0L}")},onLiveCaptions={nav.navigate("liveCaptions")},onMoodleConnect={nav.navigate("moodleConnect")},onPanoptoConnect={nav.navigate("panoptoConnect")})}
        composable("liveCaptions"){LiveCaptionsScreen(app,onBack={nav.popBackStack()})}
        composable("search"){SearchScreen(app){nav.navigate("job/$it")}}
        composable("panoptoCourse/{courseId}?start={start}",arguments=listOf(androidx.navigation.navArgument("start"){type=androidx.navigation.NavType.LongType;defaultValue=0L})){e->val courseId=e.arguments!!.getString("courseId")!!;val sourceTime=e.arguments!!.getLong("start").takeIf{it>0}
            PanoptoImportScreen(app,"",onBack={nav.popBackStack()},onStarted={},newCourseId=courseId,sourceTime=sourceTime,onCreated={id->nav.navigate("job/$id"){popUpTo("panoptoCourse/{courseId}?start={start}"){inclusive=true}}},onRecordInstead={recordCourse(courseId)})
        }

        composable("onboarding") {
            OnboardingScreen(app, onMoodle = { nav.navigate("moodleConnect") }, onPanopto = { nav.navigate("panoptoConnect") },
                onFinish = { nav.navigate("home") { popUpTo("onboarding") { inclusive = true } } })
        }
        composable("moodleConnect") { MoodleConnectScreen(onDone = { nav.popBackStack() }) }
        composable("panoptoConnect") { PanoptoConnectScreen(onDone = { nav.popBackStack() }) }
        composable("courses") {
            CoursesScreen(app, onOpen = { courseId ->
                // Rebuild courses→lectures with nothing above home: leftover job/lectures from another
                // course were still receiving the cover-tap click-through ("opened Genetics from LM").
                suppressLectureNavUntil = SystemClock.elapsedRealtime() + 1_200
                nav.navigate("courses") {
                    popUpTo("home") { inclusive = false; saveState = false }
                    launchSingleTop = true
                }
                nav.navigate("lectures/$courseId")
            }, onSettings = { nav.navigate("settings") },
                onRecordLecture = { nav.navigate("record/$it") })
        }
        composable(
            "lectures/{courseId}",
            arguments = listOf(navArgument("courseId") { type = NavType.StringType }),
        ) { e ->
            val courseId = e.arguments!!.getString("courseId")!!
            CourseTheme(app, courseId) {
            LecturesScreen(app, courseId, onBack = { nav.popBackStack() },
                onRecord = { id -> if (allowLectureNav() && app.repo.lecture(id)?.courseId == courseId) nav.navigate("record/$id") },
                onNew = { id -> if (allowLectureNav() && app.repo.lecture(id)?.courseId == courseId) nav.navigate("start/$id") },
                onAttach = { id -> if (allowLectureNav() && app.repo.lecture(id)?.courseId == courseId) nav.navigate("attach/$id") },
                onJob = { id -> if (allowLectureNav() && app.repo.lecture(id)?.courseId == courseId) nav.navigate("job/$id") },
                onPanopto = { id -> if (allowLectureNav() && id == courseId) nav.navigate("panoptoCourse/$id") },
                onMoodleCourse = { nav.navigate("moodleCourse/$courseId") },
                onExam = { nav.navigate("exam/$courseId") })
        }
        }
        composable("exam/{courseId}") { e -> CourseTheme(app, e.arguments!!.getString("courseId")!!) {
            val cid = e.arguments!!.getString("courseId")!!
            ExamScreen(app, cid, onBack = { nav.popBackStack() },
                onTutor = { nav.navigate("tutor/$it") },
                onMemory = { nav.navigate("examMemory/$cid") })
        } }
        composable("examMemory/{courseId}") { e -> CourseTheme(app, e.arguments!!.getString("courseId")!!) {
            ExamMemoryScreen(app, e.arguments!!.getString("courseId")!!, onBack = { nav.popBackStack() })
        } }
        composable("moodleCourse/{courseId}") { e -> CourseTheme(app, e.arguments!!.getString("courseId")!!) {
            MoodleAttachScreen(app, "", onBack = { nav.popBackStack() }, courseId = e.arguments!!.getString("courseId")!!) }
        }
        composable("start/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) { val id = e.arguments!!.getString("id")!!
            NewEntryScreen(app, id, onBack = { nav.popBackStack() },
                onRecord = { nav.navigate("record/$id") { popUpTo("start/$id") { inclusive = true } } },
                onUpload = { nav.navigate("attach/$id") { popUpTo("start/$id") { inclusive = true } } }) }
        }
        composable("panoptoNew/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) { val id = e.arguments!!.getString("id")!!
            PanoptoImportScreen(app, id, newEntry = true, onBack = {
                    // Backed out of the course-level Panopto picker without choosing: drop the placeholder lecture.
                    app.repo.lecture(id)?.let { if (!it.fromPanopto && it.title == PANOPTO_PLACEHOLDER && it.segments.isEmpty() && it.remoteId == null) app.repo.deleteLecture(id) }
                    nav.popBackStack() },
                onRecordInstead = { nav.navigate("record/$id") { popUpTo("panoptoNew/$id") { inclusive = true } } },
                onStarted = { nav.navigate("attach/$id") { popUpTo("panoptoNew/$id") { inclusive = true } } }) }
        }
        composable("panoptoLogin/{id}") { e -> PanoptoLoginScreen(app, e.arguments!!.getString("id")!!, onDone = { nav.popBackStack() }) }
        composable("record/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) {
            val id = e.arguments!!.getString("id")!!
            RecordScreen(app, id, onBack = { nav.popBackStack() }, onDone = { nav.navigate("job/$id") { popUpTo("record/$id") { inclusive = true } } }, onMoodle = { nav.navigate("moodle/$id") })
        }
        }
        composable("recordAnother/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) {
            val id = e.arguments!!.getString("id")!!
            // "Record another" from Materials: start at once, return to Materials (Moodle button) on stop.
            RecordScreen(app, id, onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() }, autoResume = true, onMoodle = { nav.navigate("moodle/$id") })
        }
        }
        composable("attach/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) {
            val id = e.arguments!!.getString("id")!!
            AttachUploadScreen(app, id, onBack = { nav.popBackStack() }, onRecord = { nav.navigate("record/$id") }, onRecordAnother = { nav.navigate("recordAnother/$id") }, onListen = { nav.navigate("listen/$id") }, onMoodle = { nav.navigate("moodle/$id") }, onPanopto = { if (app.repo.lecture(id)?.fromPanopto == true) nav.navigate("panoptoNew/$id") else nav.navigate("panopto/$id") }, onPanoptoLogin = { nav.navigate("panoptoLogin/$id") }, onUploaded = { nav.navigate("job/$id") { popUpTo("attach/$id") { inclusive = true } } }, onLocalCaptions = { nav.navigate("lectureCaptions/$id") })
        }
        }
        composable("moodle/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) { MoodleAttachScreen(app, e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
        }
        composable("panopto/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) { val id = e.arguments!!.getString("id")!!
            PanoptoImportScreen(app, id, onBack = { nav.popBackStack() }, onStarted = { nav.navigate("job/$id") { popUpTo("panopto/$id") { inclusive = true } } }) }
        }
        composable("job/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) { val id = e.arguments!!.getString("id")!!; JobScreen(app, id, onBack = { nav.popBackStack() }, onAttach = { nav.navigate("attach/$id") }, onListen = { nav.navigate("listen/$id") }, onRecordAnother = { nav.navigate("recordAnother/$id") }, onMoodle = { nav.navigate("moodle/$id") }, onPanopto = { nav.navigate("panopto/$id") },onMemory={nav.navigate("memory/$id")},onTutor={nav.navigate("tutor/$id")}, onLocalCaptions = { nav.navigate("lectureCaptions/$id") }) }
        }
        composable("lectureCaptions/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) {
            LectureCaptionsViewerScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() })
        } }
        composable("memory/{id}"){e->val id=e.arguments!!.getString("id")!!;LectureTheme(app,id){MemoryScreen(app,id){nav.popBackStack()}}}
        composable("tutor/{id}"){e->val id=e.arguments!!.getString("id")!!;LectureTheme(app,id){TutorScreen(app,id){nav.popBackStack()}}}
        composable("listen/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) { ListenScreen(app, e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
        }
        composable("share") {
            val uris = shared.value
            ShareImportScreen(app, uris, onCancel = { shared.value = emptyList(); nav.popBackStack() },
                onDone = { id -> shared.value = emptyList(); nav.navigate("job/$id") { popUpTo("share") { inclusive = true } } })
        }
        composable("settings") { SettingsScreen(app, onBack = { nav.popBackStack() },
            onMoodle = { nav.navigate("moodleConnect") }, onPanopto = { nav.navigate("panoptoConnect") }) }
    }
    }
}
