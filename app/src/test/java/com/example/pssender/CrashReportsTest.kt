package com.example.pssender

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class CrashReportsTest {
    @Test
    fun aReportSaysWhenWhichVersionAndWhat() {
        val e = IllegalStateException("the shelf was empty", RuntimeException("cause"))
        val r = CrashReports.report("1.99", "main", e, Date(0))
        assertTrue(r.startsWith("Crimson Multitool crash report"))
        assertTrue("Version: 1.99" in r)
        assertTrue("Thread: main" in r)
        assertTrue("IllegalStateException: the shelf was empty" in r)
        assertTrue("Caused by: java.lang.RuntimeException: cause" in r)
    }

    @Test
    fun aHugeReportIsCut() {
        var e: Throwable = RuntimeException("x".repeat(5000))
        repeat(20) { e = RuntimeException("y".repeat(2000), e) }
        assertTrue(CrashReports.report("1", "t", e).length <= 20_000)
    }
}
