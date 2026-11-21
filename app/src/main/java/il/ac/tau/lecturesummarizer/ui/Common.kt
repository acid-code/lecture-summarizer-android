package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

/**
 * True only while this nav destination is resumed AND a short settle has passed.
 * Stops "invisible" taps on the previous screen while Compose/Nav is still catching up,
 * and click-through onto the new screen from the finger that triggered navigation.
 */
@Composable
fun rememberNavInputEnabled(settleMs: Long = 300L): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = state.isAtLeast(Lifecycle.State.RESUMED)
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(resumed) {
        settled = false
        if (resumed) {
            delay(settleMs)
            settled = true
        }
    }
    return resumed && settled
}

/** Swallow every pointer while [enabled] is false (does not wait for child clickables). */
fun Modifier.blockInputWhen(blocked: Boolean): Modifier =
    if (!blocked) this
    else this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    /** Show the app logo in place of the back arrow (home screen). */
    logo: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    fab: (@Composable () -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val inputEnabled = rememberNavInputEnabled()
    Scaffold(
        modifier = Modifier.blockInputWhen(!inputEnabled),
        topBar = {
            androidx.compose.material3.CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                navigationIcon = {
                    if (onBack != null) IconButton(enabled = inputEnabled, onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.nav_back))
                    }
                    else if (logo) androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(il.ac.tau.lecturesummarizer.R.drawable.ic_logo), androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.logo_description),
                        androidx.compose.ui.Modifier.padding(start = 12.dp).size(36.dp))
                },
                actions = actions,
            )
        },
        floatingActionButton = { if (inputEnabled) fab?.invoke() },
        bottomBar = bottomBar,
        contentWindowInsets = WindowInsets.safeDrawing,
        content = content,
    )
}

fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}
