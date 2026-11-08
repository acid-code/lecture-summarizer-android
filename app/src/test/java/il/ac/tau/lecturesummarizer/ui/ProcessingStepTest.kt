package il.ac.tau.lecturesummarizer.ui
import il.ac.tau.lecturesummarizer.data.*
import org.junit.Assert.*
import org.junit.Test
class ProcessingStepTest{
 private val l=Lecture("fixture","course","lecture",0)
 @Test fun unknownProcessingIsNotPresentedAsFinishedTranscription(){assertEquals(1,processingStep(l.copy(state=LectureState.PROCESSING)))}
 @Test fun uploadAndSummaryHaveDistinctStages(){assertEquals(0,processingStep(l.copy(state=LectureState.UPLOADING)));assertEquals(3,processingStep(l.copy(state=LectureState.PROCESSING,stage="summary")))}
 @Test fun readyMeansPipelineFinished(){assertEquals(4,processingStep(l.copy(state=LectureState.DONE)))}
}
