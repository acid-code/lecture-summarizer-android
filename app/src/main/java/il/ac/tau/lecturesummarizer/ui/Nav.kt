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
import il.ac.tau.lecturesummarizer.LectureApp

@Composable
fun AppNav(app: LectureApp, shared: MutableState<List<Uri>>, openLecture: MutableState<String?> = mutableStateOf(null)) {
    val nav = rememberNavController()
    LaunchedEffect(openLecture.value) {
        val id = openLecture.value ?: return@LaunchedEffect
        openLecture.value = null
        val courseId = app.repo.lectures.value.firstOrNull { it.id == id }?.courseId
        nav.navigate("courses") { popUpTo(0) { inclusive = true }; launchSingleTop = true }
        if (courseId != null) nav.navigate("lectures/$courseId")
        nav.navigate("job/$id")
    }
    LaunchedEffect(shared.value) { if (shared.value.isNotEmpty()) nav.navigate("share") { launchSingleTop = true } }
    NavHost(nav, startDestination = "courses") {
        composable("courses") {
            CoursesScreen(app, onOpen = { nav.navigate("lectures/$it") }, onSettings = { nav.navigate("settings") },
                onRecordLecture = { nav.navigate("record/$it") })
        }
        composable("lectures/{courseId}") { e -> CourseTheme(app, e.arguments!!.getString("courseId")!!) {
            LecturesScreen(app, e.arguments!!.getString("courseId")!!, onBack = { nav.popBackStack() },
                onRecord = { nav.navigate("record/$it") }, onAttach = { nav.navigate("attach/$it") },
                onJob = { nav.navigate("job/$it") })
        }
        }
        composable("record/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) {
            val id = e.arguments!!.getString("id")!!
            RecordScreen(app, id, onBack = { nav.popBackStack() }, onDone = { nav.navigate("attach/$id") { popUpTo("record/$id") { inclusive = true } } }, onMoodle = { nav.navigate("moodle/$id") })
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
            AttachUploadScreen(app, id, onBack = { nav.popBackStack() }, onRecord = { nav.navigate("record/$id") }, onRecordAnother = { nav.navigate("recordAnother/$id") }, onListen = { nav.navigate("listen/$id") }, onMoodle = { nav.navigate("moodle/$id") }, onPanopto = { nav.navigate("panopto/$id") }, onUploaded = { nav.navigate("job/$id") { popUpTo("attach/$id") { inclusive = true } } })
        }
        }
        composable("moodle/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) { MoodleAttachScreen(app, e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
        }
        composable("panopto/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) { val id = e.arguments!!.getString("id")!!
            PanoptoImportScreen(app, id, onBack = { nav.popBackStack() }, onStarted = { nav.navigate("job/$id") { popUpTo("panopto/$id") { inclusive = true } } }) }
        }
        composable("job/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) { val id = e.arguments!!.getString("id")!!; JobScreen(app, id, onBack = { nav.popBackStack() }, onAttach = { nav.navigate("attach/$id") }, onListen = { nav.navigate("listen/$id") }, onRecordAnother = { nav.navigate("recordAnother/$id") }, onMoodle = { nav.navigate("moodle/$id") }, onPanopto = { nav.navigate("panopto/$id") }) }
        }
        composable("listen/{id}") { e -> LectureTheme(app, e.arguments!!.getString("id")!!) { ListenScreen(app, e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
        }
        composable("share") {
            val uris = shared.value
            ShareImportScreen(app, uris, onCancel = { shared.value = emptyList(); nav.popBackStack() },
                onDone = { id -> shared.value = emptyList(); nav.navigate("job/$id") { popUpTo("share") { inclusive = true } } })
        }
        composable("settings") { SettingsScreen(app, onBack = { nav.popBackStack() }) }
    }
}
