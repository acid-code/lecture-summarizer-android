package il.ac.tau.lecturesummarizer.autosync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Small dialog opened from the battery notification: pick the % below which background sync stops. */
class BatteryFloorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val b = BatteryGate.read(app)
        val current = BatteryGate.floor(app)
        setContent {
            MaterialTheme {
                var sel by remember { mutableStateOf(current) }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Battery ${b.pct}% · stop sync below?") },
                    text = {
                        Column {
                            Text("Background lecture sync keeps running on battery until it drops below the % you choose (default 30%). · בחר באיזה אחוז לעצור.")
                            Spacer(Modifier.height(12.dp))
                            Row {
                                BatteryGate.PRESETS.forEach { p ->
                                    FilterChip(selected = sel == p, onClick = { sel = p }, label = { Text("$p%") })
                                    Spacer(Modifier.width(6.dp))
                                }
                            }
                            if (!b.charging && b.pct < sel) Text("Battery is already below $sel% — sync will wait for the charger.", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = { TextButton(onClick = { BatteryGate.chooseFloor(app, sel); finish() }) { Text("Save & continue") } },
                    dismissButton = { TextButton(onClick = { BatteryGate.cancelAsk(app); finish() }) { Text("Wait") } })
            }
        }
    }
}
