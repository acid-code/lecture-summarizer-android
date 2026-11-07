package il.ac.tau.lecturesummarizer.data

/** Enforced at the data boundary, not merely by hiding a switch. */
fun effectiveMockMode(debugBuild: Boolean, persistedOrDefault: Boolean): Boolean = debugBuild && persistedOrDefault
