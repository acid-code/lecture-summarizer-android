package il.ac.tau.lecturesummarizer.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import il.ac.tau.lecturesummarizer.LectureApp

@Composable
fun AppNav(app: LectureApp, shared: MutableState<List<Uri>>, openLecture: MutableState<String?> = mutableStateOf(null)) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route=entry?.destination?.route ?: "home"
    fun recordCourse(courseId:String){val l=app.repo.addLecture(courseId,app.getString(il.ac.tau.lecturesummarizer.R.string.generated_lecture,java.text.DateFormat.getDateInstance().format(java.util.Date())));nav.navigate("record/${l.id}")}

    LaunchedEffect(openLecture.value) {
        val id = openLecture.value ?: return@LaunchedEffect
        openLecture.value = null
        val courseId = app.repo.lectures.value.firstOrNull { it.id == id }?.courseId
        nav.navigate("courses") { popUpTo(0) { inclusive = true }; launchSingleTop = true }
        if (courseId != null) nav.navigate("lectures/$courseId")
        nav.navigate("job/$id")
    }
    LaunchedEffect(shared.value) { if (shared.value.isNotEmpty()) nav.navigate("share") { launchSingleTop = true } }
    // Existing installs that already have notebooks skip onboarding.
    val start = androidx.compose.runtime.remember { if (app.settings.onboardingDone || app.repo.courses.value.isNotEmpty()) "home" else "onboarding" }
    Scaffold(bottomBar={if(route in setOf("home","courses","search"))MainTabs(route){target->nav.navigate(target){popUpTo("home"){saveState=true};launchSingleTop=true;restoreState=true}}}){outer->
    NavHost(nav, startDestination = start,modifier=Modifier.padding(PaddingValues(bottom=outer.calculateBottomPadding()))) {
        composable("home"){HomeScreen(app,onOpen={nav.navigate("job/$it")},onSettings={nav.navigate("settings")},onCourses={nav.navigate("courses")},onRecord={recordCourse(it)},onPanopto={course,start->nav.navigate("panoptoCourse/$course?start=${start ?: 0L}")})}
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
            CoursesScreen(app, onOpen = { nav.navigate("lectures/$it") }, onSettings = { nav.navigate("settings") },
                onRecordLecture = { nav.navigate("record/$it") })
        }
        composable("lectures/{courseId}") { e -> CourseTheme(app, e.arguments!!.getString("courseId")!!) {
            LecturesScreen(app, e.arguments!!.getString("courseId")!!, onBack = { nav.popBackStack() },
                onRecord = { nav.navigate("record/$it") }, onNew = { nav.navigate("start/$it") }, onAttach = { nav.navigate("attach/$it") },
                onJob = { nav.navigate("job/$it") }, onPanopto = { nav.navigate("panoptoCourse/$it") },
                onMoodleCourse = { nav.navigate("moodleCourse/${e.arguments!!.getString("courseId")!!}") })
        }
        }
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
            AttachUploadScreen(app, id, onBack = { nav.popBackStack() }, onRecord = { nav.navigate("record/$id") }, onRecordAnother = { nav.navigate("recordAnother/$id") }, onListen = { nav.navigate("listen/$id") }, onMoodle = { nav.navigate("moodle/$id") }, onPanopto = { if (app.repo.lecture(id)?.fromPanopto == true) nav.navigate("panoptoNew/$id") else nav.navigate("panopto/$id") }, onPanoptoLogin = { nav.navigate("panoptoLogin/$id") }, onUploaded = { nav.navigate("job/$id") { popUpTo("attach/$id") { inclusive = true } } })
        }
        }
        composable("moodle/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) { MoodleAttachScreen(app, e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
        }
        composable("panopto/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) { val id = e.arguments!!.getString("id")!!
            PanoptoImportScreen(app, id, onBack = { nav.popBackStack() }, onStarted = { nav.navigate("job/$id") { popUpTo("panopto/$id") { inclusive = true } } }) }
        }
        composable("job/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) { val id = e.arguments!!.getString("id")!!; JobScreen(app, id, onBack = { nav.popBackStack() }, onAttach = { nav.navigate("attach/$id") }, onListen = { nav.navigate("listen/$id") }, onRecordAnother = { nav.navigate("recordAnother/$id") }, onMoodle = { nav.navigate("moodle/$id") }, onPanopto = { nav.navigate("panopto/$id") },onMemory={nav.navigate("memory/$id")},onTutor={nav.navigate("tutor/$id")}) }
        }
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
