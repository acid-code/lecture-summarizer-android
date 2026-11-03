package il.ac.tau.lecturesummarizer.data

import org.junit.Assert.*
import org.junit.Test

class EntitlementsTest {
    @Test fun allowlist() {
        assertTrue(Entitlements.isAllowlisted("254C58F2-ea7f-44ad-919e-670f79ae5aa8", "x, 254c58f2-ea7f-44ad-919e-670f79ae5aa8"))
        assertFalse(Entitlements.isAllowlisted("abc", "254c58f2-ea7f-44ad-919e-670f79ae5aa8"))
        assertFalse(Entitlements.isAllowlisted("", ""))
    }
}
