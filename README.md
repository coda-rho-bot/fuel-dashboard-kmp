# Fuel Dashboard for Letta (KMP)

Cross-platform dashboard for monitoring AI provider fuel/quota status for a
[Letta Code](https://letta.com) multi-agent fleet.
Consumes the [fuel orchestrator API](https://git.angussoftware.dev/coda/fuel-orchestrator) at `http://127.0.0.1:8321`.

## Targets

| Platform | Status |
|----------|--------|
| Desktop (JVM) | ✅ Primary |
| Android | ✅ Builds |
| iOS | 🔲 Stub |

## Tech Stack

- **Compose Multiplatform** 1.9.0 — shared UI
- **Ktor Client** 3.4.3 — HTTP polling (30s interval)
- **Angus-Software-Theming** 0.10.4 — Angus brand theme + 17 community color schemes
- **angus-gradle-tools** 0.3.0 — Gradle convention plugins, coverage tooling
- **Kotlin** 2.3.21

## Running the Desktop App

```bash
./gradlew :composeApp:run
```

The fuel orchestrator API must be running on `http://127.0.0.1:8321`.

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
│   ├── model/         — API data classes (kotlinx.serialization)
│   ├── network/       — Ktor HTTP client
│   ├── presentation/  — ViewModel with StateFlow + 30s polling
│   └── ui/            — Compose UI (FuelDashboardApp, components)
├── desktopMain/       — JVM entry point (Window + AngusTheme)
├── androidMain/       — Android entry point (MainActivity)
└── iosMain/           — iOS stub
```

## Forgejo

https://git.angussoftware.dev/coda/fuel-dashboard-kmp
