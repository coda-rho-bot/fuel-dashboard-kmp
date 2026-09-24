package com.angussoftware.fueldashboard.model

/**
 * Where Claude Code is currently sending its requests.
 *
 * The dashboard can show a Claude plan gauge and a z.ai gauge side by side and
 * still leave you guessing which one your sessions are actually burning. This
 * is the missing sensor: Claude Code stores its routing in
 * `~/.claude/settings.json`, and an absent `ANTHROPIC_BASE_URL` means the
 * default Anthropic endpoint while any other value means a compatible
 * provider has been substituted.
 *
 * Deliberately generic — no provider is named here. The endpoint is reported
 * as-is and matched against the user's own configured providers by host, so
 * this works for anyone who points Claude Code at an Anthropic-compatible API,
 * not just the setup that motivated it.
 */
data class ClaudeCodeRoute(
    /**
     * The configured `ANTHROPIC_BASE_URL`, or null when unset — which is
     * itself meaningful: unset is how Claude Code says "stock Anthropic".
     */
    val baseUrl: String? = null,
    /**
     * Id of the configured provider this endpoint belongs to, matched on
     * host, or null when nothing in the user's provider list matches.
     */
    val matchedProviderId: String? = null,
    /** `permissions.defaultMode`, which commonly changes with the endpoint. */
    val permissionMode: String? = null,
) {
    /** True when requests go to Anthropic's own endpoint. */
    val isDefaultAnthropic: Boolean get() = baseUrl.isNullOrBlank()

    /** Host of [baseUrl], for display and matching. */
    val host: String? get() = baseUrl?.let { hostOf(it) }

    /**
     * Short label for the UI: the provider's own display name when it could
     * be matched, otherwise the bare host, otherwise "Anthropic".
     */
    fun label(providers: List<ProviderConfig>): String {
        if (isDefaultAnthropic) return "Anthropic"
        val matched = providers.firstOrNull { it.id == matchedProviderId }
        return matched?.resolvedDisplayName() ?: host ?: "custom endpoint"
    }

    companion object {
        /**
         * Host portion of a URL, lowercased, without scheme, port or path.
         *
         * Hand-rolled because commonMain has no URL parser, and the input is
         * a user-edited settings string that may well be malformed — this
         * returns null rather than throwing on anything it cannot read.
         */
        fun hostOf(url: String): String? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return null
            val afterScheme = trimmed.substringAfter("://", trimmed)
            val authority = afterScheme.substringBefore('/').substringBefore('?')
            // Drop userinfo and port.
            val hostPart = authority.substringAfterLast('@').substringBefore(':')
            return hostPart.lowercase().ifEmpty { null }
        }

        /**
         * Matches an endpoint against configured providers by host.
         *
         * Host rather than full URL because the routing URL carries a path
         * the provider's own base URL does not — z.ai's Anthropic-compatible
         * endpoint is `https://api.z.ai/api/anthropic` while the provider is
         * configured as `https://api.z.ai`. Comparing whole strings would
         * never match.
         */
        fun matchProvider(baseUrl: String?, providers: List<ProviderConfig>): String? {
            val host = baseUrl?.let { hostOf(it) }
            if (host == null) {
                // No override means Claude Code is on Anthropic's own endpoint,
                // which for a Max/Pro session is the subscription — so the
                // plan provider is the one serving. Without this the default
                // (and most common) case matched nothing and the UI could not
                // say which provider was live at all.
                return providers.firstOrNull { it.kind == ProviderKind.CLAUDE_CODE }?.id
                    ?: providers.firstOrNull { it.kind == ProviderKind.ANTHROPIC }?.id
            }
            return providers.firstOrNull { hostOf(it.resolvedServerUrl()) == host }?.id
        }
    }
}
