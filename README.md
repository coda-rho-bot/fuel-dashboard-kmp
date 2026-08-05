# Fuel Dashboard (KMP)

Cross-platform dashboard for monitoring AI provider fuel/quota status.
Works standalone with a provider API key, or connected to a fuel orchestrator for fleet monitoring.

## Modes

| Mode | Setup | Features |
|------|-------|----------|
| **Direct** | Enter your provider API key | Fuel bars, burn rates, countdowns |
| **Connected** | Enter orchestrator URL | Full fleet data (agents, decisions, alerts) |

## Targets

| Platform | Status |
|----------|--------|
| Desktop (JVM) | ✅ Primary |
| Android | ✅ Builds |
| iOS | 🔲 Stub |

## Tech Stack

- **Compose Multiplatform** 1.9.0 — shared UI
- **Ktor Client** 3.4.3 — HTTP polling
- **Angus-Software-Theming** 0.10.4 — 17 community color schemes
- **angus-gradle-tools** 0.3.0 — Gradle convention plugins, coverage tooling
- **Kotlin** 2.3.21

## Running the Desktop App

```bash
./gradlew :composeApp:run
```

On first launch, enter your provider API key (direct mode) or orchestrator URL (connected mode).

## Building

```bash
# Desktop distribution (DEB/RPM/DMG)
./gradlew :composeApp:packageDistributionForCurrentOS

# Android APK
./gradlew :composeApp:assembleDebug
```

## Architecture

```
composeApp/src/
├── commonMain/kotlin/.../fueldashboard/
│   ├── model/         — API data classes + FuelSource interface
│   ├── network/       — Ktor HTTP client + provider adapters (z.ai, orchestrator)
│   ├── presentation/  — ViewModel with StateFlow + polling
│   ├── settings/      — ThemeController + fuel settings storage
│   ├── storage/       — Local history + burn rate calculator
│   └── ui/            — Compose UI (dashboard, components, setup screen)
├── desktopMain/       — JVM entry point (Window + system dark mode detection)
├── androidMain/       — Android entry point (MainActivity)
└── iosMain/           — iOS stub
```

## Provider Adapters

| Provider | Status | How it works |
|----------|--------|-------------|
| z.ai | ✅ Built | Direct API polling (`/api/monitor/usage/quota/limit`) |
| Orchestrator | ✅ Built | REST API client (any backend that implements the API) |
| OpenAI | 🔲 Planned | Rate limit headers + usage API |
| Anthropic | 🔲 Planned | Rate limit headers |

## Forgejo

https://git.angussoftware.dev/coda/fuel-dashboard-kmp
