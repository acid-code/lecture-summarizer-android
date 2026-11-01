package il.ac.tau.lecturesummarizer.ui

import androidx.compose.ui.graphics.Color

/** Visual file type for the Moodle picker: emoji + short label + accent color. */
enum class MoodleFileType(val emoji: String, val label: String, val color: Color) {
    PDF("📕", "PDF", Color(0xFFD93025)),
    WORD("📘", "Word", Color(0xFF2B579A)),
    SLIDES("📙", "PowerPoint", Color(0xFFD24726)),
    SHEET("📗", "Excel", Color(0xFF217346)),
    IMAGE("🖼️", "Image", Color(0xFF8E24AA)),
    ARCHIVE("🗜️", "ZIP", Color(0xFF795548)),
    TEXT("📝", "Text", Color(0xFF546E7A)),
    VIDEO("🎬", "Video", Color(0xFFE65100)),
    AUDIO("🎧", "Audio", Color(0xFF00897B)),
    CODE("💻", "Code", Color(0xFF3949AB)),
    LINK("🔗", "Link", Color(0xFF1E88E5)),
    FOLDER("📁", "Folder", Color(0xFFF9A825)),
    OTHER("📄", "File", Color(0xFF607D8B));

    companion object {
        fun of(name: String, module: String? = null): MoodleFileType {
            val ext = name.substringAfterLast('.', "").lowercase().trim()
            return when (ext) {
                "pdf" -> PDF
                "doc", "docx", "odt", "rtf" -> WORD
                "ppt", "pptx", "pps", "ppsx", "odp", "key" -> SLIDES
                "xls", "xlsx", "csv", "ods", "numbers" -> SHEET
                "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "svg" -> IMAGE
                "zip", "rar", "7z", "tar", "gz", "tgz" -> ARCHIVE
                "txt", "md", "tex", "log" -> TEXT
                "mp4", "mov", "mkv", "avi", "webm", "m4v" -> VIDEO
                "mp3", "wav", "m4a", "aac", "ogg", "flac" -> AUDIO
                "py", "java", "kt", "c", "cpp", "h", "js", "ts", "ipynb", "m", "r", "html", "json", "sql" -> CODE
                else -> when (module?.lowercase()) {
                    "url" -> LINK; "folder" -> FOLDER; else -> OTHER
                }
            }
        }
    }
}
