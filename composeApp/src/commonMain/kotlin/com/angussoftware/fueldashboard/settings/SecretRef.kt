package com.angussoftware.fueldashboard.settings

/**
 * How a stored credential should be obtained.
 *
 * Provider keys are stored in plain settings (`java.util.prefs` on desktop,
 * a plaintext XML file). For anyone who keeps credentials in a vault, a
 * keychain or a secrets manager, copying them into this app's settings defeats
 * that entirely. A [SecretRef] lets the setting hold a *reference* instead of
 * a value, resolved when it is needed and never written back.
 *
 * Pasting a key directly remains the default and is completely unchanged: a
 * string with no recognised scheme is the key. Nothing here runs for a literal
 * key — no process is spawned, no file is read, no cache is consulted. This is
 * purely additive.
 *
 * The schemes deliberately avoid naming any particular secrets product. They
 * are the generic primitives every such system can already be driven through —
 * and [Command] in particular mirrors Claude Code's own `apiKeyHelper`, so an
 * existing helper script works unchanged.
 */
sealed interface SecretRef {

    /** The setting is the credential itself — the default, and the status quo. */
    data class Literal(val value: String) : SecretRef

    /** `env:NAME` — read an environment variable. */
    data class Environment(val name: String) : SecretRef

    /** `file:/path` — read a file's contents, trimmed. Docker/systemd secrets. */
    data class FileContents(val path: String) : SecretRef

    /**
     * `cmd:some-helper --flag` — run a command and take its stdout.
     *
     * This is the general escape hatch: any vault, keychain or broker that can
     * be read from a shell can be driven through it. Split on whitespace and
     * executed directly with no shell, so a settings string is not an
     * injection surface; point it at a script if you need shell features.
     */
    data class Command(val command: String) : SecretRef

    companion object {
        const val ENV_PREFIX = "env:"
        const val FILE_PREFIX = "file:"
        const val CMD_PREFIX = "cmd:"

        /**
         * Parses a stored setting.
         *
         * Only an exact, non-empty scheme match indirects. `env:` with nothing
         * after it, or a key that merely happens to contain a colon, stays a
         * literal — misreading a real credential as a broken reference would
         * take a working provider offline.
         */
        fun parse(stored: String): SecretRef {
            fun after(prefix: String): String? =
                stored.removePrefix(prefix).trim().takeIf { stored.startsWith(prefix) && it.isNotEmpty() }

            after(ENV_PREFIX)?.let { return Environment(it) }
            after(FILE_PREFIX)?.let { return FileContents(it) }
            after(CMD_PREFIX)?.let { return Command(it) }
            return Literal(stored)
        }

        /** True when [stored] is a reference rather than the credential itself. */
        fun isReference(stored: String): Boolean = parse(stored) !is Literal

        /**
         * A form of [stored] that is safe to display or log.
         *
         * References are shown verbatim — that is the point of using one, and
         * they name a location rather than a secret. A literal is masked.
         */
        fun describe(stored: String): String = when (val ref = parse(stored)) {
            is Literal -> if (ref.value.isEmpty()) "" else "•".repeat(8)
            is Environment -> "$ENV_PREFIX${ref.name}"
            is FileContents -> "$FILE_PREFIX${ref.path}"
            is Command -> "$CMD_PREFIX${ref.command}"
        }
    }
}

/**
 * Resolves a [SecretRef] to the credential.
 *
 * Returns null when a reference cannot be resolved. Null must never be treated
 * as an empty credential: the caller surfaces it as a provider error, because
 * silently polling with no key produces a confusing 401 instead of "your vault
 * is locked".
 */
internal expect fun resolveSecretRef(ref: SecretRef): String?

/** Whether this platform can resolve [SecretRef.Command] references. */
internal expect val secretCommandsSupported: Boolean
