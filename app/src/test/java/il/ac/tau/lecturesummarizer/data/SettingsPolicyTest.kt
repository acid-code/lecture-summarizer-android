package il.ac.tau.lecturesummarizer.data

import org.junit.Assert.*
import org.junit.Test

class SettingsPolicyTest {
    @Test fun staleTruePreferenceCannotEnableMockInRelease() {
        assertFalse(effectiveMockMode(false, true))
        assertFalse(effectiveMockMode(false, false))
        assertTrue(effectiveMockMode(true, true))
        assertFalse(effectiveMockMode(true, false))
    }
}
