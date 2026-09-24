package com.angussoftware.fueldashboard.settings

/**
 * Android resolves the references it can. Spawning helper processes is not
 * possible here, so a cmd: reference resolves to null and the provider
 * surfaces it as an error rather than polling with no credential.
 */
internal actual fun resolveSecretRef(ref: SecretRef): String? = when (ref) {
    is SecretRef.Literal -> ref.value
    is SecretRef.Environment -> null
    is SecretRef.FileContents -> null
    is SecretRef.Command -> null
}

internal actual val secretCommandsSupported: Boolean = false
