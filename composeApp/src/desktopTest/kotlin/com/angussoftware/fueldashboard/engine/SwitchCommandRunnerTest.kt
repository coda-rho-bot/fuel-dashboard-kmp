package com.angussoftware.fueldashboard.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the REAL desktop runner — the no-shell guarantee has to be
 * verified where it lives, not only through injected fakes. Every other
 * switch-command test injects a runner; these do not.
 */
class SwitchCommandRunnerTest {

    @Test
    fun runsARealCommandAndCapturesOutput() = runBlocking {
        val result = runSwitchCommand("/bin/echo hello-from-switch")
        assertEquals(0, result.exitCode)
        assertEquals("hello-from-switch", result.output.trim())
        assertTrue(result.succeeded)
        assertFalse(result.timedOut)
    }

    @Test
    fun aPipedStringIsASingleArgumentNotShellSyntax() = runBlocking {
        // If anyone ever reintroduces a shell ("sh -c …"), this command would
        // print "real" (pipe to head) instead of failing to find a binary
        // literally named "echo|head" — and either way the test catches it.
        val result = runSwitchCommand("/bin/echo|head -n1")
        // No shell: ProcessBuilder looks for a binary whose name contains the
        // pipe character, which does not exist.
        assertNull(result.exitCode)
        assertFalse(result.succeeded)
        assertFalse(result.timedOut)
    }

    @Test
    fun aMissingBinaryFailsGracefully() = runBlocking {
        val result = runSwitchCommand("definitely-not-a-real-binary-xyz --flag")
        assertNull(result.exitCode)
        assertFalse(result.succeeded)
    }

    @Test
    fun aNonZeroExitIsReportedNotThrown() = runBlocking {
        // The runner splits on whitespace only — so "exit 3" must arrive as
        // ONE argv element. Writing it quoted inside the command string is
        // exactly how a config file would express it after the split… which
        // would NOT happen (split breaks the quotes), so instead exercise the
        // realistic path: a binary with a literal non-zero exit. This is also
        // the no-shell proof in reverse: had a shell parsed this, `exit 3`
        // would terminate it with 3.
        val result = runSwitchCommand("/bin/false")
        assertEquals(1, result.exitCode)
        assertFalse(result.succeeded)
    }

    @Test
    fun aStreamingCommandIsKilledAtTheTimeout() = runBlocking {
        // `yes` never reaches EOF. Before the watchdog fix, readText() would
        // block forever and the timeout would never fire. Keep this test's
        // timeout small so the suite stays fast.
        val started = System.currentTimeMillis()
        val result = runSwitchCommand("yes", timeoutMs = 1_000)
        val elapsed = System.currentTimeMillis() - started
        assertTrue(result.timedOut, "a streaming command must be reported as timed out")
        assertNull(result.exitCode)
        assertTrue(elapsed < 30_000, "the watchdog must fire: took ${elapsed}ms")
    }

    @Test
    fun anEmptyCommandIsRejected() = runBlocking {
        val result = runSwitchCommand("   ")
        assertNull(result.exitCode)
        assertFalse(result.succeeded)
    }
}
