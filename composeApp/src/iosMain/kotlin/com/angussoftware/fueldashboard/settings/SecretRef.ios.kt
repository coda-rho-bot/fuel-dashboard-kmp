package com.angussoftware.fueldashboard.settings

/**
 * iOS resolves only literals. Spawning helper processes is not possible here,
 * so an indirect reference resolves to null and the provider surfaces it as an
 * error rather than polling with no credential.
 */
internal actual fun resolveSecretRef(ref: SecretRef): String? = when (ref) {
    is SecretRef.Literal -> ref.value
    is SecretRef.Environment -> null
    is SecretRef.FileContents -> null
    is SecretRef.Command -> null
}

internal actual val secretCommandsSupported: Boolean = false
