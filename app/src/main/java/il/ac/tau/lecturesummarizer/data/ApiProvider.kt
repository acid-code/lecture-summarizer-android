package il.ac.tau.lecturesummarizer.data

object ApiProvider {
    private val mock = MockLectureApi()  // singleton so fake job progress survives across calls
    fun get(settings: Settings): LectureApi =
        if (settings.mockMode) mock else HttpLectureApi(settings.baseUrl, settings.apiToken, settings.deviceId, claimIds)

    /** Remote course ids this phone already knows (for the one-time legacy claim). Set by LectureApp. */
    @Volatile var claimIds: () -> List<String> = { emptyList() }
}
