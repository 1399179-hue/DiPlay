package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report dialog shows the newest lines of a report that can be megabytes of hex, keeping the
 * head as context. These cases pin the shape: bounded by the view budget plus the separator.
 */
class ReportTailTest {
    private val max = 200_000
    private val headMax = 20_000
    private val marker = "\n\u2026\n"

    private fun head(report: String) = if (report.length <= headMax) report else report.take(headMax)

    /** Mirrors DiPlayActivity.reportTail, which is an instance method on the activity. */
    private fun tailOf(report: String): String {
        val head = head(report)
        if (report.length <= max) return report
        val budget = (max - head.length).coerceAtLeast(0)
        val from = report.length - budget
        val cut = report.indexOf('\n', from)
        val tail = if (cut in from until report.length) report.substring(cut + 1) else report.substring(from)
        return head + marker + tail
    }

    @Test fun reportOfExactlyTheLimitIsUnchangedAndOneMoreIsCut() {
        val exact = "a".repeat(max)
        assertEquals(exact, tailOf(exact))
        val bigger = tailOf("a".repeat(max + 1))
        // The view budget bounds the body; the separator the dialog adds is on top of it.
        assertTrue("${bigger.length} chars", bigger.length <= max + marker.length)
        assertTrue(bigger.contains(marker))
        assertTrue(bigger.endsWith("a".repeat(1_000)))
    }

    @Test fun smallReportIsShownInFull() {
        val report = "DiPlay 1.0\n--- diplay.log ---\n12:00:00.000  CarPlay connected\n"
        assertEquals(report, tailOf(report))
    }

    @Test fun tailStartsOnALineBoundaryWhenOneExists() {
        val line = "12:00:00.000  line rx=29.8fps shown=29.8fps maxGap=150ms kbps=4000\n"
        val report = line.repeat(6_000)
        val shown = tailOf(report)
        val body = shown.substringAfter(marker)
        assertTrue("body starts mid-line: ${body.take(20)}", body.startsWith("12:00:00.000"))
        assertTrue(report.endsWith(body))
        assertTrue(shown.length <= max + marker.length)
    }

    @Test fun crlfReportStartsOnALineBoundary() {
        val report = "12:00:00.000  line\r\n".repeat(30_000)
        val body = tailOf(report).substringAfter(marker)
        assertTrue(report.endsWith(body))
        assertTrue(body.startsWith("12:00:00.000"))
        assertTrue(body.length <= max)
    }

    @Test fun reportWithoutAnyNewlineStillShowsTheEnd() {
        val report = "x".repeat(50_000) + "y".repeat(max + 100)
        val shown = tailOf(report)
        assertTrue(shown.length <= max + marker.length)
        assertTrue(shown.endsWith("y".repeat(1_000)))
    }
}
