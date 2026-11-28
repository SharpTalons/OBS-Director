package dev.sharptalons.obsdirector.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTest {
    // a session: OBS recorded a.mp4 from 60 s to 120 s of the session, paused, went on from
    // 150 s to 200 s (file time carried on from 60 s), then recorded b.mp4 from 300 s
    private val session = Recording(
        file = "session-1", startedAt = 0, session = true,
        obs = listOf(
            ObsSpan("a.mp4", 60_000, 120_000, 0),
            ObsSpan("a.mp4", 150_000, 200_000, 60_000),
            ObsSpan("b.mp4", 300_000, 400_000, 0),
        ),
        clips = listOf(
            Clip("before", 10_000, 30_000),        // before any recording
            Clip("inside", 70_000, 80_000),        // inside a.mp4
            Clip("across", 50_000, 90_000),        // starts before a.mp4 starts
            Clip("pause", 110_000, 160_000),       // runs through the pause
            Clip("b", 290_000, 310_000),           // runs into b.mp4
        ),
    )

    @Test fun filesItOverlaps() = assertEquals(listOf("a.mp4", "b.mp4"), session.obsFiles())

    @Test fun clipsInEachRecordingsOwnTimes() {
        val a = session.forObsFile("a.mp4").clips.associate { it.id to (it.startMs to it.endMs) }
        assertEquals(setOf("inside", "across", "pause"), a.keys)
        assertEquals(10_000L to 20_000L, a["inside"])
        assertEquals(0L to 30_000L, a["across"])      // only the part inside the recording
        assertEquals(50_000L to 70_000L, a["pause"])  // file time skips the pause
        val b = session.forObsFile("b.mp4").clips
        assertEquals(listOf("b"), b.map { it.id })
        assertEquals(0L to 10_000L, b[0].startMs to b[0].endMs)
    }

    @Test fun entriesFromBeforeSessionsAreTheirOwnRecording() {
        val old = Recording(file = "old.mp4", startedAt = 0, clips = listOf(Clip("x", 5_000, 9_000)), audioFile = "old.m4a", audioOffsetMs = 1_000)
        assertEquals(listOf("old.mp4"), old.obsFiles())
        assertEquals(5_000L, old.forObsFile("old.mp4").clips.single().startMs)
        assertEquals(listOf(AudioSeg("old.m4a", 1_000, Long.MAX_VALUE / 4)), old.audioSegments())
    }
}
