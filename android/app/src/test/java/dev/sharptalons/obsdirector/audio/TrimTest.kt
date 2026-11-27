package dev.sharptalons.obsdirector.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimTest {
    @Test fun windowAroundTheClip() {
        assertEquals(45_000L..135_000L, Trim.window(60_000, 120_000, 600_000))
        assertEquals(0L..20_000L, Trim.window(2_000, 5_000, 600_000))
        assertEquals(575_000L..600_000L, Trim.window(590_000, 599_000, 600_000))
    }

    @Test fun edgesStayInBoundsAndApart() {
        assertEquals(9_000L, Trim.moveStart(5_000, 10_000, 12_000))
        assertEquals(0L, Trim.moveStart(5_000, 10_000, -3_000))
        assertEquals(6_000L, Trim.moveEnd(5_000, 10_000, 1_000, 60_000))
        assertEquals(60_000L, Trim.moveEnd(5_000, 10_000, 99_000, 60_000))
    }

    @Test fun movingTheWholeClipKeepsItsLength() {
        assertEquals(8_000L, Trim.moveBoth(5_000, 10_000, 3_000, 60_000))
        assertEquals(0L, Trim.moveBoth(5_000, 10_000, -9_000, 60_000))
        assertEquals(55_000L, Trim.moveBoth(5_000, 10_000, 90_000, 60_000))
    }

    @Test fun snapsToTheNearbyPause() {
        // speech at 0.8, a pause at 1.3-1.5 s
        val env = List(40) { i -> if (i in 13..14) 0.05f else 0.8f }
        assertEquals(1_350L, Trim.snap(1_100, env))
        // nothing quiet within half a second: stays put
        assertEquals(2_500L, Trim.snap(2_500, env))
        // already in the pause: stays put
        assertEquals(1_320L, Trim.snap(1_320, env))
        // no envelope (audio not saved): stays put
        assertEquals(1_100L, Trim.snap(1_100, emptyList()))
    }
}
