package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.ac.tau.lecturesummarizer.data.Course

val coverPalette = listOf(
    0xFF1E5AA8, 0xFFB23A48, 0xFF2E7D5B, 0xFF7B4FA0, 0xFFD07A1F, 0xFF00796B,
    0xFF5D4037, 0xFF3949AB, 0xFFE0A100, 0xFFC2185B, 0xFF455A64, 0xFF111111,
)
val studyEmojis = listOf(
    "📘", "📗", "📕", "📙", "📓", "📒", "🧮", "🧪", "🧬", "🔬", "🔭", "⚛️",
    "💻", "🖥️", "📐", "📏", "📊", "📈", "🧠", "⚖️", "🏛️", "🌍", "🗺️", "🎨",
    "🎼", "🎧", "📜", "✍️", "💡", "🔢", "∑", "🩺", "💊", "⚙️", "🔌", "🌱",
    "🚀", "🎓", "🗣️", "📚", "🕍", "🇮🇱",
)
enum class CoverPattern(val label: String) { SOLID("Solid"), LINED("Lined"), GRID("Grid"), GRADIENT("Gradient"), DOTS("Dots") }

fun patternOf(s: String?) = CoverPattern.entries.firstOrNull { it.name == s } ?: CoverPattern.SOLID
fun Course.coverColorValue(): Color = color?.let { Color(it) } ?: coverColor(name)
fun Course.coverEmoji(): String = emoji ?: "📓"

/** Draws a notebook cover background (color + pattern). [light] = pale version for lecture rows/headers. */
@Composable
fun CoverBackground(color: Color, pattern: CoverPattern, light: Boolean, modifier: Modifier = Modifier) {
    val base = if (light) color.copy(alpha = 0.12f) else color
    val ink = if (light) color.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.16f)
    Canvas(modifier) {
        if (pattern == CoverPattern.GRADIENT) {
            drawRect(Brush.linearGradient(listOf(base, if (light) color.copy(alpha = 0.03f) else lerpWhite(color))))
        } else drawRect(base)
        val step = 14.dp.toPx(); val sw = 1.dp.toPx()
        when (pattern) {
            CoverPattern.LINED -> { var y = step * 2; while (y < size.height) { drawLine(ink, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), sw); y += step } }
            CoverPattern.GRID -> {
                var y = step; while (y < size.height) { drawLine(ink, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), sw); y += step }
                var x = step; while (x < size.width) { drawLine(ink, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), sw); x += step }
            }
            CoverPattern.DOTS -> { var y = step; while (y < size.height) { var x = step; while (x < size.width) { drawCircle(ink, 1.6.dp.toPx(), androidx.compose.ui.geometry.Offset(x, y)); x += step }; y += step } }
            else -> {}
        }
    }
}

private fun lerpWhite(c: Color) = Color(
    red = c.red + (1 - c.red) * 0.35f, green = c.green + (1 - c.green) * 0.35f, blue = c.blue + (1 - c.blue) * 0.35f)

/** Apple-Notes/Study-style notebook cover tile for the courses grid. */
@Composable
fun NotebookCover(title: String, subtitle: String, emoji: String, color: Color, pattern: CoverPattern,
                  modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp)
    Box(modifier.aspectRatio(0.78f).shadow(6.dp, shape).clip(shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        CoverBackground(color, pattern, light = false, modifier = Modifier.matchParentSize())
        // spine
        Box(Modifier.width(12.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.18f)))
        Box(Modifier.padding(start = 12.dp).width(2.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.25f)))
        Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)) {
            Text(emoji, fontSize = 40.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth()) {
                Column {
                    Text(title, color = Color(0xFF1B1B1F), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotEmpty()) Text(subtitle, color = Color(0xFF555560), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Create / edit notebook sheet: live preview, name, emoji grid, colour chips, background styles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookEditor(initial: Course?, onDismiss: () -> Unit, onSave: (name: String, emoji: String, color: Long, pattern: String) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.coverEmoji() ?: studyEmojis.first()) }
    var color by remember { mutableLongStateOf(initial?.color ?: initial?.let { coverPalette[Math.floorMod(it.name.hashCode(), 8)] } ?: coverPalette.first()) }
    var pattern by remember { mutableStateOf(patternOf(initial?.pattern)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Text(if (initial == null) "New notebook" else "Edit notebook", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), emoji, color, pattern.name) }) { Text(if (initial == null) "Create" else "Done", fontWeight = FontWeight.Bold) }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                NotebookCover(name.ifBlank { "Course name" }, "", emoji, Color(color), pattern, Modifier.width(140.dp))
            }
            OutlinedTextField(name, { name = it }, label = { Text("Course name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp))
            SectionLabel("Color")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                coverPalette.take(6).forEach { c -> ColorChip(c, c == color) { color = c } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                coverPalette.drop(6).forEach { c -> ColorChip(c, c == color) { color = c } }
            }
            SectionLabel("Cover style")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CoverPattern.entries.forEach { p ->
                    Column(Modifier.weight(1f).clickable { pattern = p }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
                            .border(if (p == pattern) BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))) {
                            CoverBackground(Color(color), p, false, Modifier.matchParentSize())
                        }
                        Text(p.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            SectionLabel("Icon")
            LazyVerticalGrid(GridCells.Fixed(7), Modifier.fillMaxWidth().heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(studyEmojis) { e ->
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (e == emoji) Color(color).copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(if (e == emoji) 2.dp else 0.dp, if (e == emoji) Color(color) else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { emoji = e }, contentAlignment = Alignment.Center) { Text(e, fontSize = 22.sp) }
                }
            }
        }
    }
}

@Composable
fun SectionLabel(t: String) = Text(t.uppercase(), style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)

@Composable
private fun ColorChip(c: Long, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape)
        .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
        .padding(if (selected) 5.dp else 0.dp).clip(CircleShape).background(Color(c)).clickable(onClick = onClick))
}

/** Pale header band for a course's lecture list, inheriting the cover look. */
@Composable
fun CourseHeader(course: Course, subtitle: String, onEdit: () -> Unit) {
    val col = course.coverColorValue()
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
        CoverBackground(col, patternOf(course.pattern), light = true, modifier = Modifier.matchParentSize())
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(col), contentAlignment = Alignment.Center) {
                Text(course.coverEmoji(), fontSize = 30.sp)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(course.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onEdit) { Text("Cover") }
        }
    }
}
