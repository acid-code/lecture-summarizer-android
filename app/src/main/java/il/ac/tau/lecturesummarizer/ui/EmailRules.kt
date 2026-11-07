package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.email.*

/** P2 is opt-in and OFF by default. Never implies an inbox connection or silently reads mail. */
@Composable
fun EmailRulesSection(app: LectureApp, enabled: Boolean = app.settings.emailRulesEnabled) {
    if (!enabled) return
    var open by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().clickable { open = true }) {
        Text(stringResource(R.string.email_rules), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
    }
    if (open) androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        EmailRulesScreen(app) { open = false }
    }
}

@Composable
fun EmailRulesScreen(app: LectureApp, onBack: () -> Unit) {
    val store = remember { EmailRuleStore(app) }
    var rules by remember { mutableStateOf(store.read()) }
    fun save(v: List<EmailRule>) { rules = v; store.save(v) }
    var editing by remember { mutableStateOf<EmailRule?>(null) }
    var connectInfo by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf<Pair<Int, EmailRule>?>(null) }
    AppScaffold(stringResource(R.string.email_rules), onBack = onBack, fab = {
        ExtendedFloatingActionButton(onClick = { editing = EmailRule(java.util.UUID.randomUUID().toString(), "") },
            icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.email_new_rule)) })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EmptyState(stringResource(R.string.email_unconnected), stringResource(R.string.email_unconnected_body)) {
                Button(onClick = { connectInfo = true }) { Text(stringResource(R.string.email_connect)) }
            }
            Text(stringResource(R.string.email_privacy), style = MaterialTheme.typography.bodySmall)
            rules.forEachIndexed { index, rule ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { editing = rule }, modifier = Modifier.weight(1f)) { Text(rule.name) }
                            Switch(rule.enabled, { save(rules.map { if (it.id == rule.id) it.copy(enabled = !rule.enabled) else it }) })
                            IconButton(onClick = { removed = index to rule; save(rules.filterNot { it.id == rule.id }) }) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.slides_remove)) }
                        }
                        Text((rule.senders + rule.include + rule.exclude.map { "−$it" } + listOfNotNull(rule.course.takeIf { it.isNotBlank() })).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.email_no_matches), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { connectInfo = true }) { Text(stringResource(R.string.email_preview)) }
                    }
                }
            }
            removed?.let { (index, rule) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.name, Modifier.weight(1f))
                    TextButton(onClick = { save(rules.toMutableList().apply { add(index.coerceAtMost(size), rule) }); removed = null }) { Text(stringResource(R.string.undo)) }
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    }
    if (connectInfo) AlertDialog(onDismissRequest = { connectInfo = false }, title = { Text(stringResource(R.string.email_connect)) },
        text = { Text(stringResource(R.string.email_no_connection)) }, confirmButton = { TextButton(onClick = { connectInfo = false }) { Text(stringResource(R.string.close)) } })
    editing?.let { original ->
        var name by remember(original.id) { mutableStateOf(original.name) }
        var senders by remember(original.id) { mutableStateOf(original.senders.joinToString(", ")) }
        var include by remember(original.id) { mutableStateOf(original.include.joinToString(", ")) }
        var exclude by remember(original.id) { mutableStateOf(original.exclude.joinToString(", ")) }
        var course by remember(original.id) { mutableStateOf(original.course) }
        fun split(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.email_new_rule)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.email_rule_name)) })
                OutlinedTextField(senders, { senders = it }, label = { Text(stringResource(R.string.email_senders)) })
                OutlinedTextField(include, { include = it }, label = { Text(stringResource(R.string.email_include)) })
                OutlinedTextField(exclude, { exclude = it }, label = { Text(stringResource(R.string.email_exclude)) })
                OutlinedTextField(course, { course = it }, label = { Text(stringResource(R.string.email_course)) })
            }
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            val value = original.copy(name = name.trim(), senders = split(senders), include = split(include), exclude = split(exclude), course = course.trim())
            save(if (rules.any { it.id == value.id }) rules.map { if (it.id == value.id) value else it } else rules + value); editing = null
        }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
