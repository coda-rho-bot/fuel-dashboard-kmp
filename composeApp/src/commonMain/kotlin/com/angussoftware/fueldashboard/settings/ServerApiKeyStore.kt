package com.angussoftware.fueldashboard.settings

/** Persists the API key used to authorize write access to the embedded server. */
object ServerApiKeyStore {
    fun load(): String = loadStringSetting(FuelSettingsKeys.SERVER_API_KEY, "")

    fun save(key: String) = saveStringSetting(FuelSettingsKeys.SERVER_API_KEY, key)

    fun loadOrCreate(generate: () -> String): String = loadOrCreateApiKey(
        load = ::load,
        save = { saveStringSetting(FuelSettingsKeys.SERVER_API_KEY, it) },
        generate = generate,
    )
}

internal fun loadOrCreateApiKey(
    load: () -> String,
    save: (String) -> Unit,
    generate: () -> String,
): String {
    val existing = load()
    if (existing.isNotBlank()) return existing

    return generate().also(save)
}