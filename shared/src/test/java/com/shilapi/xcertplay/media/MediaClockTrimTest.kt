package com.shilapi.xcertplay.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class MediaClockTrimTest {
    @Before fun forgetLearnedTrim() { MediaClockTrim.learnedTrim = 0.0 }
    @After fun cleanUp() { MediaClockTrim.learnedTrim = 0.0 }

    /**
     * A head unit whose audio clock runs [drift] fast: the iPhone sends 352-frame packets at 48 kHz, with
     * gaps of 50–150 ms followed by a catch-up burst; the head unit plays 1024-frame periods.
     */
    private class Drive(val drift: Double, val trimmed: Boolean, seed: Int = 7) {
        val target = 14_400L // 300 ms at 48 kHz, as #610's music track started
        val trim = MediaClockTrim(48_000, target)
        val random = Random(seed)
        var queued = target.toDouble()
        var starved = 0
        var lowest = queued
        val rates = mutableListOf<Int>()

        fun run(seconds: Int) {
            var rate = 48_000
            var nextPacketMs = 0.0
            var pending = 0
            var gapUntilMs = -1.0
            var played = 0.0
            for (ms in 0 until seconds * 1000) {
                val nowNs = ms * 1_000_000L
                if (random.nextDouble() < 0.4 / 1000) gapUntilMs = ms + 50 + random.nextDouble() * 100
                while (nextPacketMs <= ms) { pending += 352; nextPacketMs += 352 / 48.0 }
                if (ms >= gapUntilMs && pending > 0) {
                    queued += pending
                    pending = 0
                    if (trimmed) {
                        trim.sample(queued.toLong(), nowNs)
                        trim.rateDue(nowNs)?.let { rate = it; rates += it }
                    }
                }
                played += rate * (1 + drift) / 1000.0
                while (played >= 1024) {
                    played -= 1024
                    if (queued >= 1024) queued -= 1024 else { starved++; queued = 0.0 }
                }
                lowest = minOf(lowest, queued)
            }
        }
    }

    @Test fun withoutTheTrimA076PercentFastHeadUnitRunsDryEveryMinuteOrSo() {
        val drive = Drive(0.0076, trimmed = false)
        drive.run(300)
        assertTrue("starved ${drive.starved}", drive.starved >= 3)
    }

    @Test fun theTrimSettlesAtTheHeadUnitsDriftAndMusicNeverRunsDry() {
        val drive = Drive(0.0076, trimmed = true)
        drive.run(300)
        assertEquals(0, drive.starved)
        assertTrue("lowest ${drive.lowest}", drive.lowest > 0.2 * drive.target)
        // 48000 / 1.0076: the track plays that much slower, so the fast clock consumes 48 kHz.
        assertEquals(47_638.0, drive.trim.rate.toDouble(), 15.0)
        assertEquals(-0.0075, MediaClockTrim.learnedTrim, 0.0003)
    }

    @Test fun aSlowHeadUnitIsTrimmedTheOtherWay() {
        val drive = Drive(-0.0076, trimmed = true)
        drive.run(300)
        assertEquals(0, drive.starved)
        assertEquals(48_368.0, drive.trim.rate.toDouble(), 15.0)
    }

    @Test fun anAccurateClockIsLeftAlone() {
        val drive = Drive(0.0, trimmed = true)
        drive.run(300)
        assertEquals(0, drive.starved)
        assertEquals(48_000.0, drive.trim.rate.toDouble(), 5.0)
    }

    @Test fun theTrimStaysWithinThreePercent() {
        val trim = MediaClockTrim(48_000, 14_400)
        // Nothing queued for minutes: the trim stops at its bound.
        for (second in 0..600) {
            val nowNs = second * 1_000_000_000L
            trim.sample(0, nowNs)
            trim.rateDue(nowNs)
        }
        assertEquals(46_560, trim.rate)
    }

    @Test fun withoutNewAudioTheTrimHolds() {
        val trim = MediaClockTrim(48_000, 14_400)
        // Half the buffer missing is a supply problem, not drift: the error is clamped to 0.25.
        trim.sample(7_200, 0)
        assertEquals(47_640, trim.rateDue(0))
        // The iPhone paused: no write for 2 s, so no update even though the last level was low.
        assertNull(trim.rateDue(2_000_000_000L))
        assertEquals(47_640, trim.rate)
        // Music resumes at the target level: the average restarts there.
        trim.sample(14_400, 2_100_000_000L)
        assertEquals(48_000, trim.rateDue(2_100_000_000L))
    }

    @Test fun aLongPauseDoesNotCountAsDrift() {
        val trim = MediaClockTrim(48_000, 14_400)
        trim.sample(14_400, 0)
        trim.rateDue(0)
        // Five minutes later music resumes with a short first burst: one step, not 300 s, is integrated.
        val resumeNs = 300_000_000_000L
        trim.sample(10_080, resumeNs)
        assertEquals(47_616, trim.rateDue(resumeNs))
        assertEquals(-0.0005, MediaClockTrim.learnedTrim, 1e-9)
    }

    @Test fun aNewStreamStartsFromTheLearnedTrim() {
        MediaClockTrim.learnedTrim = -0.0075
        val trim = MediaClockTrim(48_000, 14_400)
        trim.sample(14_400, 0)
        assertEquals(47_640, trim.rateDue(0))
    }

    @Test fun updatesComeAtMostOnceASecond() {
        val trim = MediaClockTrim(48_000, 14_400)
        trim.sample(7_200, 0)
        assertEquals(47_640, trim.rateDue(0))
        trim.sample(14_400, 500_000_000L)
        assertNull(trim.rateDue(500_000_000L))
    }

    @Test fun aLargeCompressedQueueMustNotTrickClockControlIntoSlowingDownFurther() {
        val trim = MediaClockTrim(48_000, 14_400)
        // The #806 recording had 150-192 pending AAC packets while Android's track writes
        // reported a small hardware PCM queue. Those packets are not 'missing' music.
        for (second in 1..12) {
            trim.sample(4_800, second * 1_000_000_000L, compressedFrames = 96 * 1024L)
            trim.rateDue(second * 1_000_000_000L)
        }
        assertTrue("slow playback grows a 2-second packet backlog; rate=${trim.rate}", trim.rate >= 48_000)
        // Decoder congestion is not a characteristic of the vehicle's playback clock. A future
        // song should not inherit a pitch error caused by a temporary stall.
        assertEquals(0.0, MediaClockTrim.learnedTrim, 1e-8)
    }

    /**
     * Wireless CarPlay: the iPhone sends about 0.4 s of music at once, every 0.4 s by its clock give
     * or take 30 ms (#807, #976). The head unit's clock is accurate; playback starts once the target,
     * and at least one burst, is queued.
     */
    private class BurstDrive(targetMs: Int, seed: Int = 7) {
        val target = 48L * targetMs
        val trim = MediaClockTrim(48_000, target)
        val random = Random(seed)
        var queued = maxOf(target, 19_200L).toDouble()
        val starvedSeconds = mutableListOf<Int>()
        val ratesAfterAMinute = mutableListOf<Int>()

        fun run(seconds: Int) {
            var rate = 48_000
            var burst = 1
            var nextBurstMs = 400.0
            var played = 0.0
            for (ms in 0 until seconds * 1000) {
                val nowNs = ms * 1_000_000L
                if (ms >= nextBurstMs) {
                    queued += 19_200
                    burst++
                    nextBurstMs = burst * 400 + (random.nextDouble() - 0.5) * 60
                    trim.sample(queued.toLong(), nowNs)
                    trim.rateDue(nowNs)?.let { rate = it }
                    if (ms >= 60_000) ratesAfterAMinute += rate
                }
                played += rate / 1000.0
                while (played >= 1024) {
                    played -= 1024
                    if (queued >= 1024) queued -= 1024 else { starvedSeconds += ms / 1000; queued = 0.0 }
                }
            }
        }
    }

    @Test fun overWiFiTheDefaultBufferIsNotPlayedFast() {
        for (seed in 1..3) {
            MediaClockTrim.learnedTrim = 0.0
            val drive = BurstDrive(300, seed)
            drive.run(300)
            assertEquals("seed $seed starved at ${drive.starvedSeconds}", 0, drive.starvedSeconds.count { it >= 60 })
            assertEquals("seed $seed", 48_000.0, drive.ratesAfterAMinute.average(), 20.0)
            assertTrue("seed $seed max ${drive.ratesAfterAMinute.max()}", drive.ratesAfterAMinute.max() < 48_200)
        }
    }

    @Test fun overWiFiALongerBufferAlsoSettlesAtTheIphonesClock() {
        val drive = BurstDrive(1000)
        drive.run(300)
        assertEquals(emptyList<Int>(), drive.starvedSeconds)
        assertEquals(48_000.0, drive.ratesAfterAMinute.average(), 20.0)
    }

    /**
     * Audio that arrived while a write was blocked counts from its own arrival (#1074).
     *
     * The two runs differ only in what [MediaClockTrim.sample] is told about those arrivals. With
     * them the mean since the last write is fuller, so the controller sees less of a shortfall and
     * slows music down far less than it would if the arrivals were charged to the sample time.
     */
    @Test fun audioThatArrivedDuringABlockedWriteCountsFromItsArrival() {
        fun rateAfterABlockedWrite(credit: Boolean): Int {
            // A 5 s target keeps the level well inside the ±0.25 control bound, so the arrival
            // credit decides instead of the clamp.
            val trim = MediaClockTrim(48_000, 240_000)
            trim.sample(240_000, 1_000_000_000L)
            trim.rateDue(1_000_000_000L)
            // The write blocks for a second while 14_400 frames wait 0.9 s and another 14_400 wait
            // 0.5 s behind it. arrivedFrameNs is frames times nanoseconds.
            val waited = 14_400.0 * 0.9 * 1e9 + 14_400.0 * 0.5 * 1e9
            trim.sample(240_000, 2_000_000_000L, arrivedFrameNs = if (credit) waited else 0.0)
            return trim.rateDue(2_000_000_000L) ?: 48_000
        }
        val uncredited = rateAfterABlockedWrite(credit = false)
        val credited = rateAfterABlockedWrite(credit = true)
        assertTrue("ignoring the arrivals must slow music: $uncredited", uncredited < 48_000)
        assertTrue("credited=$credited uncredited=$uncredited", credited > uncredited + 15)
    }

    /**
     * The queue level a head unit really holds is not the start threshold: buffered music is fed a
     * second ahead of real time, so playback starts partway through a burst (#1031/#1068). The
     * controller measures the level instead, and only adopts it once two halves agree.
     */
    @Test fun theTargetIsMeasuredFromTheLevelTheQueueSettlesAt() {
        val samples = MediaClockTrim(48_000, 14_400, measuresTarget = true)
        // 600 ms queued steadily, sampled every 100 ms. The start threshold was only a guess.
        for (tenth in 1..200) {
            samples.sample(28_800, tenth * 100_000_000L)
            samples.rateDue(tenth * 100_000_000L)
        }
        assertEquals(26_400, samples.targetFramesForTest)
    }

    @Test fun aDroppedPacketRestartsTheTargetMeasurement() {
        val samples = MediaClockTrim(48_000, 14_400, measuresTarget = true)
        for (tenth in 1..40) samples.sample(28_800, tenth * 100_000_000L)
        assertEquals(26_400, samples.targetFramesForTest)
        // A dropped packet leaves a hole: what was learned before it is not the level to hold.
        samples.measureAgain()
        samples.sample(28_800, 4_100_000_000L)
        samples.sample(28_800, 4_200_000_000L)
        assertEquals("the target is kept until a new one is measured", 26_400, samples.targetFramesForTest)
    }

    @Test fun aSaturatedTrimNeverLearnsMoreThanItsBound() {
        val trim = MediaClockTrim(48_000, 48_000)
        var rate = 48_000
        for (second in 1..600) {
            val nowNs = second * 1_000_000_000L
            // Music is always queued short: the controller must slow down, but only to its bound.
            trim.sample(0, nowNs)
            rate = trim.rateDue(nowNs) ?: rate
        }
        assertEquals((48_000 * (1.0 - MediaClockTrim.MAX_TRIM)).toInt(), rate)
        assertEquals(-MediaClockTrim.MAX_TRIM, MediaClockTrim.learnedTrim, 1e-9)
    }
}
