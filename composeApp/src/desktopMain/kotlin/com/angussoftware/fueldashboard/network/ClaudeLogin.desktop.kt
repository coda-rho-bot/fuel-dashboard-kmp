package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.resolveClaudeConfigDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * Opens `claude /login` in a terminal window, for the account whose
 * configuration directory is [configDir].
 *
 * ## Why a terminal and not a process
 *
 * The login is an interactive OAuth round trip: it prints a URL, opens a
 * browser, and waits for the operator to paste a code back. A bare
 * [ProcessBuilder] with no tty gives it nowhere to print and nowhere to read
 * from, so it needs a real terminal — which is also where the operator sees it
 * fail, if it does.
 *
 * ## Why a login shell
 *
 * `claude` is commonly installed under `~/.local/bin`, which is on a login
 * shell's PATH but not necessarily on the PATH this app inherits when it is
 * started from a desktop entry. `bash -lc` is what closes that gap, and it is
 * the same discrepancy the switch-command runner already warns about.
 *
 * The command it runs is a fixed constant, so the shell is not an injection
 * surface. The one variable — the account directory — is handed to the child
 * through its environment rather than spliced into the string, which is what
 * keeps a settings field from becoming one.
 */
internal actual suspend fun launchClaudeLogin(configDir: String?): ClaudeLoginLaunch =
    withContext(Dispatchers.IO) {
        val dir = resolveClaudeConfigDir(configDir)

        // Claude Code writes into this directory, so a login for a brand-new
        // account needs it to exist first. Created 0700 where the filesystem
        // supports it: it is about to hold a credentials file, and the
        // prevailing umask does not guarantee that on its own.
        if (!dir.isDirectory && !createPrivateDir(dir)) {
            return@withContext ClaudeLoginLaunch(
                launched = false,
                message = "Could not create ${dir.absolutePath}.",
            )
        }

        val attempts = terminalCommands()
        if (attempts.isEmpty()) {
            return@withContext ClaudeLoginLaunch(
                launched = false,
                message = unsupportedHint(dir),
            )
        }

        val failures = mutableListOf<String>()
        for (argv in attempts) {
            val started = runCatching {
                ProcessBuilder(argv)
                    .apply {
                        // Removed, not just left alone, for the default
                        // account: this app may itself have been started from a
                        // shell that has another account's value set.
                        val value = configDirEnvValue(dir)
                        if (value == null) environment().remove(CLAUDE_CONFIG_DIR_ENV)
                        else environment()[CLAUDE_CONFIG_DIR_ENV] = value
                    }
                    .start()
            }.getOrElse { e ->
                // Almost always "no such terminal on PATH", which is why the
                // candidates are tried in order rather than probed up front.
                failures += "${argv.first()}: ${e.message}"
                null
            } ?: continue

            // A terminal that dies instantly did not open anything — most
            // likely it rejected its own arguments. Give it a moment and check,
            // so the card can say so instead of claiming a window appeared.
            // Deliberately short: a successful login takes as long as a person
            // takes, and waiting for that would block the UI.
            val diedImmediately = started.waitFor(600, TimeUnit.MILLISECONDS) &&
                started.exitValue() != 0
            if (diedImmediately) {
                failures += "${argv.first()}: exited ${started.exitValue()} immediately"
                continue
            }

            return@withContext ClaudeLoginLaunch(
                launched = true,
                message = "Opened a login for ${dir.absolutePath} — finish it in the terminal, " +
                    "then this gauge fills on the next poll.",
            )
        }

        ClaudeLoginLaunch(
            launched = false,
            message = "Could not open a terminal (${failures.joinToString("; ").take(160)}). " +
                unsupportedHint(dir),
        )
    }

/**
 * Creates [dir] and its parents with owner-only permissions, returning whether
 * it now exists as a directory.
 *
 * POSIX permissions are requested as a file attribute rather than applied
 * afterwards, so there is no window in which the directory exists with a wider
 * mode than intended. Windows has no POSIX view and throws, which is not a
 * failure — it has its own inheritance rules — so it falls back to a plain
 * create.
 */
internal fun createPrivateDir(dir: File): Boolean {
    val ownerOnly = runCatching {
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    }.getOrNull()

    if (ownerOnly != null) {
        runCatching { Files.createDirectories(dir.toPath(), ownerOnly) }
            .onSuccess { return true }
        // UnsupportedOperationException on a non-POSIX filesystem, or the
        // directory appearing underneath us; either way try the plain path.
    }
    return runCatching { Files.createDirectories(dir.toPath()) }.isSuccess || dir.isDirectory
}

/**
 * What to run, best candidate first, for this operating system.
 *
 * Only the two platforms this app is actually distributed for are covered —
 * a Deb and a Windows portable zip (see composeApp/build.gradle.kts). Anything
 * else falls through to [unsupportedHint], which prints the command rather than
 * guessing at an invocation nobody has run.
 */
internal fun terminalCommands(
    osName: String = System.getProperty("os.name").orEmpty(),
): List<List<String>> {
    val os = osName.lowercase()
    val login = CLAUDE_LOGIN_COMMAND

    // macOS is tested FIRST and matched precisely, because "darwin" contains
    // "win": a substring test for Windows claims Darwin as well, and would have
    // handed macOS a cmd.exe invocation that reports success for a window that
    // never opened.
    if (os.startsWith("mac") || os.startsWith("darwin")) return emptyList()

    if (os.startsWith("windows")) {
        // /k keeps the window open on failure, which is the whole reason a
        // terminal is being opened at all.
        return listOf(listOf("cmd", "/c", "start", "Claude login", "cmd", "/k", login))
    }

    // --hold / -hold keep the window open after the command exits so an error
    // is readable instead of flashing past.
    return listOf(
        listOf("konsole", "--hold", "-e", "bash", "-lc", login),
        listOf("gnome-terminal", "--", "bash", "-lc", login),
        listOf("xfce4-terminal", "--hold", "-e", "bash -lc '$login'"),
        listOf("x-terminal-emulator", "-e", "bash", "-lc", login),
        listOf("alacritty", "-e", "bash", "-lc", login),
        listOf("kitty", "--hold", "bash", "-lc", login),
        listOf("wezterm", "start", "--", "bash", "-lc", login),
        listOf("xterm", "-hold", "-e", "bash", "-lc", login),
    )
}

/**
 * The command the terminal runs. A constant, which is what makes it safe to
 * hand to a shell: the only variable in this whole path is the account
 * directory, and that travels in the environment.
 */
internal const val CLAUDE_LOGIN_COMMAND = "claude /login"

private const val CLAUDE_CONFIG_DIR_ENV = "CLAUDE_CONFIG_DIR"

/**
 * The `CLAUDE_CONFIG_DIR` value a login for [dir] needs, or null when the
 * variable must be UNSET.
 *
 * Null for the default `~/.claude`, because setting the variable is not the
 * same as leaving it unset: with it set, Claude Code reads
 * `$CLAUDE_CONFIG_DIR/.claude.json`, but the default account's lives at
 * `~/.claude.json`. Pointing it at `~/.claude` therefore starts Claude Code on
 * an empty file — a fresh install, onboarding and all — and the login lands
 * the account's identity in a file nothing else reads.
 */
internal fun configDirEnvValue(
    dir: File,
    home: String = System.getProperty("user.home"),
): String? {
    val default = File(home, ".claude").absoluteFile.normalize()
    return if (dir.absoluteFile.normalize() == default) null else dir.absolutePath
}

internal fun unsupportedHint(
    dir: File,
    home: String = System.getProperty("user.home"),
): String {
    val env = configDirEnvValue(dir, home)
        ?.let { "$CLAUDE_CONFIG_DIR_ENV=$it" }
        ?: "env -u $CLAUDE_CONFIG_DIR_ENV"
    return "Run this yourself: $env $CLAUDE_LOGIN_COMMAND"
}

internal actual val claudeLoginSupported: Boolean = true
