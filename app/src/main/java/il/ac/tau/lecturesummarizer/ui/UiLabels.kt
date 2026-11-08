package il.ac.tau.lecturesummarizer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.calendar.ClassKind
import il.ac.tau.lecturesummarizer.data.SummaryDensity

/** Isolates are display-only. Never store them in titles, paths, account IDs, or protocol fields. */
fun bidi(value: Any?)="\u2068${value ?: ""}\u2069"
fun ltr(value: Any?)="\u2066${value ?: ""}\u2069"
@Composable fun densityLabel(d:SummaryDensity)=stringResource(d.labelRes)
@Composable fun classKindLabel(k:ClassKind)=stringResource(when(k){ClassKind.LECTURE->R.string.kind_lecture;ClassKind.RECITATION->R.string.kind_recitation;ClassKind.UNKNOWN->R.string.kind_class})

@Composable fun coverPatternLabel(p:CoverPattern)=when(p){
CoverPattern.SOLID->androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.cover_solid)
CoverPattern.LINED->androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.cover_lined)
CoverPattern.GRID->androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.cover_grid)
CoverPattern.GRADIENT->androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.cover_gradient)
CoverPattern.DOTS->androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.cover_dots)
}
@Composable fun fileTypeLabel(t:MoodleFileType)=when(t){
MoodleFileType.IMAGE->androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.file_image)
MoodleFileType.TEXT->androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.file_text)
MoodleFileType.VIDEO->androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.file_video)
MoodleFileType.AUDIO->androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.file_audio)
MoodleFileType.CODE->androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.file_code)
MoodleFileType.LINK->androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.file_link)
MoodleFileType.FOLDER->androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.file_folder)
MoodleFileType.OTHER->androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.file_other)
else->t.label
}
