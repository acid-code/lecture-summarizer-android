package il.ac.tau.lecturesummarizer.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import il.ac.tau.lecturesummarizer.LectureApp

@Composable
fun AppNav(app: LectureApp) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "courses") {
        composable("courses") {
            CoursesScreen(app, onOpen = { nav.navigate("lectures/$it") }, onSettings = { nav.navigate("settings") })
        }
        composable("lectures/{courseId}") { e ->
            LecturesScreen(app, e.arguments!!.getString("courseId")!!, onBack = { nav.popBackStack() },
                onRecord = { nav.navigate("record/$it") }, onAttach = { nav.navigate("attach/$it") },
                onJob = { nav.navigate("job/$it") })
        }
        composable("record/{id}") { e ->
            val id = e.arguments!!.getString("id")!!
            RecordScreen(app, id, onBack = { nav.popBackStack() }, onDone = { nav.navigate("attach/$id") { popUpTo("record/$id") { inclusive = true } } })
        }
        composable("attach/{id}") { e ->
            val id = e.arguments!!.getString("id")!!
            AttachUploadScreen(app, id, onBack = { nav.popBackStack() }, onUploaded = { nav.navigate("job/$id") { popUpTo("attach/$id") { inclusive = true } } })
        }
        composable("job/{id}") { e -> val id = e.arguments!!.getString("id")!!; JobScreen(app, id, onBack = { nav.popBackStack() }, onAttach = { nav.navigate("attach/$id") }) }
        composable("settings") { SettingsScreen(app, onBack = { nav.popBackStack() }) }
    }
}
