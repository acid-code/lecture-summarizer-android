package il.ac.tau.lecturesummarizer.data

object ApiProvider {
    private val mock = MockLectureApi()  // singleton so fake job progress survives across calls
    fun get(settings: Settings): LectureApi =
        if (settings.mockMode) mock else HttpLectureApi(settings.baseUrl, settings.apiToken)
}
