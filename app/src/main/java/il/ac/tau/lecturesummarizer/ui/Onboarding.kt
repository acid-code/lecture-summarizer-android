package il.ac.tau.lecturesummarizer.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.data.SummaryDensity
import il.ac.tau.lecturesummarizer.moodle.MoodleStore
import il.ac.tau.lecturesummarizer.moodle.Panopto

/**
 * First-launch onboarding (drafts/app-polish/onboarding.md): calendar → Moodle/Panopto → summary levels.
 * Connect actions reuse the existing flows: READ_CALENDAR permission (CalendarResolver) and the WebView
 * cookie logins (MoodleLoginView) — no new OAuth.
 */
@Composable
fun OnboardingScreen(app: LectureApp, onMoodle: () -> Unit, onPanopto: () -> Unit, onFinish: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    Scaffold { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // step dots
            Row(Modifier.padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { i -> Surface(Modifier.size(if (i == step) 24.dp else 8.dp, 8.dp), shape = MaterialTheme.shapes.small,
                    color = if (i == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) {} }
            }
            Text(stringResource(R.string.onboarding_step,step+1),style=MaterialTheme.typography.labelLarge)
            if(step>0)TextButton(onClick={step--}){Text(stringResource(R.string.onboarding_back))}
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                when (step) {
                    0 -> CalendarStep(onNext = { step = 1 })
                    1 -> ConnectStep(ctx, onMoodle, onPanopto, onNext = { step = 2 })
                    else -> LevelsStep(app, onFinish = { app.settings.onboardingDone = true; onFinish() })
                }
            }
        }
    }
}

@Composable
private fun Title(text: String, body: String?) {
    EmptyIllustration(Modifier.size(120.dp))
    Spacer(Modifier.height(24.dp))
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    body?.let { Spacer(Modifier.height(8.dp)); Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ColumnScope.CalendarStep(onNext: () -> Unit) {
    val ctx = LocalContext.current
    Title(stringResource(R.string.ob1_title), stringResource(R.string.ob1_body))
    CalendarImportButton(ctx.applicationContext as LectureApp,onImported=onNext)
    TextButton(onClick = onNext) { Text(stringResource(R.string.skip)) }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.ob1_small), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.ConnectStep(ctx: android.content.Context, onMoodle: () -> Unit, onPanopto: () -> Unit, onNext: () -> Unit) {
    // Re-read when we come back from a login route (this destination is recomposed on return).
    var tick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { tick++ }
    val moodle = remember(tick) { Connections.moodle(ctx) }
    val panopto = remember(tick) { Connections.panopto() }
    Title(stringResource(R.string.ob2_title), stringResource(R.string.ob2_body))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConnectionRow(stringResource(R.string.ob2_moodle), moodle, onConnect = onMoodle)
        ConnectionRow(stringResource(R.string.ob2_panopto), panopto, onConnect = onPanopto)
    }
    if (tick > 1 && (!moodle || !panopto)) Text(stringResource(R.string.connection_pending), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.ob2_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(20.dp))
    Button(onClick = onNext, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.continue_)) }
    TextButton(onClick = onNext) { Text(stringResource(R.string.skip)) }
}

@Composable
private fun ColumnScope.LevelsStep(app: LectureApp, onFinish: () -> Unit) {
    // Both ON by default: מלא = detailed, רגיל = standard.
    var full by rememberSaveable { mutableStateOf(true) }
    var regular by rememberSaveable { mutableStateOf(true) }
    Title(stringResource(R.string.ob3_title), null)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(full, { if (it || regular) full = it }); Text(stringResource(R.string.ob3_full)) }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(regular, { if (it || full) regular = it }); Text(stringResource(R.string.ob3_regular)) }
    }
    Text(stringResource(R.string.onboarding_levels_hint),style=MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(20.dp))
    Button(onClick = {
        app.settings.summaryDensities = buildSet { if (full) add(SummaryDensity.DETAILED.api); if (regular) add(SummaryDensity.STANDARD.api) }
        onFinish()
    }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.ob3_cta)) }
    TextButton(onClick = { app.settings.summaryDensities = setOf("standard", "detailed"); onFinish() }) { Text(stringResource(R.string.skip)) }
}

/** Standalone Moodle sign-in (same WebView cookie login as Attach from Moodle). */
@Composable
fun MoodleConnectScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    AppScaffold(stringResource(R.string.moodle_sign_in), onBack = onDone) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) { MoodleLoginView(MoodleStore(ctx.applicationContext).baseUrl) { onDone() } }
    }
}

/** Standalone Panopto sign-in (same probe as PanoptoLoginScreen: .ASPXAUTH cookie). */
@Composable
fun PanoptoConnectScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    AppScaffold(stringResource(R.string.panopto_sign_in), onBack = onDone) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            MoodleLoginView(MoodleStore(ctx.applicationContext).baseUrl, startUrl = Panopto.HOST,
                isDone = { u -> u.contains("panopto", true) && (u.contains("Viewer.aspx", true) || u.contains("/Sessions/List.aspx", true)) },
                sessionReady = { Panopto.hasSessionCookie() }) { onDone() }
        }
    }
}
