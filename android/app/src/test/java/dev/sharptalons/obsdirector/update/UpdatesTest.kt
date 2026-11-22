package dev.sharptalons.obsdirector.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The update-check spec's acceptance cases for version order and the APK name. */
class UpdatesTest {
    @Test fun newerIsNumericPartByPart() {
        assertTrue(Updates.isNewer("0.10.0", "0.9.9"))
        assertTrue(Updates.isNewer("v0.10.0", "0.9.3"))
        assertTrue(Updates.isNewer("1.0.0", "0.99.99"))
        assertTrue(Updates.isNewer("0.1.1", "0.1.0-debug"))
    }

    @Test fun equalOrOlderIsNeverOffered() {
        assertFalse(Updates.isNewer("1.0.0", "1.0.0"))
        assertFalse(Updates.isNewer("v1.0.0", "1.0.0"))
        assertFalse(Updates.isNewer("0.9.9", "0.10.0"))
        assertFalse(Updates.isNewer("0.1.0", "0.1.0-debug"))
    }

    @Test fun apkUrlMatchesWhatCiPublishes() {
        assertEquals(
            "https://github.com/SharpTalons/OBS-Director/releases/download/v0.2.0/OBS-Director-0.2.0.apk",
            Updates.apkUrl("0.2.0"),
        )
    }
}
