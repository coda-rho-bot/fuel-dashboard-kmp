package com.angussoftware.fueldashboard.network

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Choosing how to open an interactive login.
 *
 * The launch itself cannot be unit tested — it opens a window and waits for a
 * person — but picking what to run can be, and that is where the mistakes are:
 * a terminal invoked with the wrong flag exits instantly and the operator sees
 * nothing at all.
 */
class ClaudeLoginTest {

    @Test
    fun linuxOffersSeveralTerminalsInPreferenceOrder() {
        val cmds = terminalCommands("Linux")
        assertTrue(cmds.size > 1, "one candidate is not enough — installs vary")
        // Candidates are tried in order and a missing binary just throws, so
        // the list doubles as the preference order.
        assertEquals("konsole", cmds.first().first())
        assertTrue(
            cmds.any { it.first() == "x-terminal-emulator" },
            "the Debian alternatives entry is the portable fallback, and this ships as a .deb",
        )
    }

    @Test
    fun everyLinuxCandidateRunsThroughALoginShell() {
        // claude commonly lives in ~/.local/bin, which is on a login shell's
        // PATH but not necessarily on the PATH inherited from a desktop entry.
        // A candidate that skips the login shell works from a terminal and
        // fails from the app icon, which is the worst way to fail.
        for (cmd in terminalCommands("Linux")) {
            assertTrue(
                cmd.any { it == "-lc" } || cmd.any { it.contains("bash -lc") },
                "${cmd.first()} does not use a login shell: $cmd",
            )
        }
    }

    @Test
    fun everyLinuxCandidateRunsTheLoginCommand() {
        for (cmd in terminalCommands("Linux")) {
            assertTrue(
                cmd.any { it.contains(CLAUDE_LOGIN_COMMAND) },
                "${cmd.first()} does not actually run the login: $cmd",
            )
        }
    }

    @Test
    fun noCandidateCarriesTheAccountDirectory() {
        // The account directory reaches the child through its environment, not
        // its argv. If it ever appears in a command line it has become a shell
        // injection surface, since the value comes from a settings field.
        for (osName in listOf("Linux", "Windows 11")) {
            for (cmd in terminalCommands(osName)) {
                assertTrue(
                    cmd.none { it.contains("CLAUDE_CONFIG_DIR") },
                    "${cmd.first()} splices the config dir into argv: $cmd",
                )
            }
        }
    }

    @Test
    fun theDefaultAccountGetsTheVariableUnset() {
        // Set to ~/.claude, Claude Code reads ~/.claude/.claude.json instead of
        // the default account's ~/.claude.json and boots as a fresh install.
        val home = "/home/someone"
        assertNull(configDirEnvValue(File("$home/.claude"), home))
        assertNull(configDirEnvValue(File("$home/.claude/"), home), "a trailing slash is the same directory")
        assertNull(configDirEnvValue(File("$home/x/../.claude"), home), "so is an unnormalised path")
    }

    @Test
    fun anyOtherAccountGetsItsDirectory() {
        val home = "/home/someone"
        assertEquals(
            "$home/.claude-accounts/work",
            configDirEnvValue(File("$home/.claude-accounts/work"), home),
        )
        assertEquals("/srv/claude", configDirEnvValue(File("/srv/claude"), home))
    }

    @Test
    fun theHintNeverTellsTheDefaultAccountToSetTheVariable() {
        val home = "/home/someone"
        assertEquals(
            "Run this yourself: env -u CLAUDE_CONFIG_DIR $CLAUDE_LOGIN_COMMAND",
            unsupportedHint(File("$home/.claude"), home),
        )
        assertEquals(
            "Run this yourself: CLAUDE_CONFIG_DIR=$home/.claude-accounts/work $CLAUDE_LOGIN_COMMAND",
            unsupportedHint(File("$home/.claude-accounts/work"), home),
        )
    }

    @Test
    fun windowsKeepsItsWindowOpen() {
        // The entire reason a terminal is opened is so a failure is readable.
        // cmd without /k closes on exit and the operator sees a flash.
        val cmd = terminalCommands("Windows 11").single()
        assertTrue(cmd.contains("/k"), "a window that closes on failure shows nothing: $cmd")
        assertTrue(cmd.any { it.contains(CLAUDE_LOGIN_COMMAND) })
    }

    @Test
    fun macHasNoCandidatesRatherThanAGuessedOne() {
        // Not a shipped target (build.gradle.kts packages a Deb and a Windows
        // portable). An untested invocation would report "opened a login" for a
        // window that never appeared; no candidates means the operator is given
        // the command to run instead.
        assertTrue(terminalCommands("Mac OS X").isEmpty())
        assertTrue(terminalCommands("Darwin").isEmpty())
    }

    @Test
    fun aNewAccountDirectoryIsCreatedOwnerOnly() {
        // It is about to hold a credentials file, and the prevailing umask
        // (commonly 022) would otherwise leave it world-readable.
        val parent = Files.createTempDirectory("claude-login").toFile()
        try {
            val dir = File(parent, "accounts/work")
            assertTrue(createPrivateDir(dir))
            assertTrue(dir.isDirectory)
            assertEquals(
                "rwx------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath())),
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun anExistingDirectoryIsLeftAsItIs() {
        val dir = Files.createTempDirectory("claude-login").toFile()
        try {
            assertTrue(createPrivateDir(dir), "an existing directory is success, not a failure")
        } finally {
            dir.deleteRecursively()
        }
    }
}
