# Fuel Dashboard (KMP)

Cross-platform dashboard for monitoring AI provider fuel/quota status.
Monitor and manage provider fuel state across your AI fleet.

## Multi-Provider System

The dashboard supports 9 provider types simultaneously, each with its own adapter and fuel budgeting strategy. The embedded orchestrator recommends models across providers based on tier selection and current fuel state.

## Major Features

### MCP Server

The dashboard exposes an MCP server on port 8321, allowing external AI agents to manage providers, register themselves, and query fuel state.

### ACP Agent Discovery

Agents can discover each other through the ACP protocol.

### Embedded Orchestrator

The embedded orchestrator recommends models across providers according to tier selection and current fuel state.

### QR Sync

Mobile devices can sync settings by scanning a QR code from the desktop app.

### Help System

A built-in help system is available from the UI.

### Junie Credits

The dashboard can check the available Junie credit balance.

### 17 Themes

The app ships with 17 color themes.

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

Configure provider credentials and dashboard settings on first launch.

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
│   ├── network/       — Ktor HTTP client + provider adapters (z.ai, OpenAI, Anthropic, DeepSeek, Groq, Mistral, Letta Cloud, Junie, orchestrator)
│   ├── presentation/  — ViewModel with StateFlow + polling
│   ├── settings/      — ThemeController + fuel settings storage
│   ├── storage/       — Local history + burn rate calculator
│   └── ui/            — Compose UI (dashboard, components, settings panel)
├── desktopMain/       — JVM entry point (Window + system dark mode detection)
├── androidMain/       — Android entry point (MainActivity)
└── iosMain/           — iOS stub
```

## Provider Adapters

| Provider | Limit Type | Status |
|----------|------------|--------|
| z.ai | WINDOW_CREDIT | Built |
| Letta Cloud | WINDOW_CREDIT | Built |
| OpenAI | SPEND_BUDGET + RATE_LIMIT | Built |
| Anthropic | SPEND_BUDGET + RATE_LIMIT | Built |
| DeepSeek | SPEND_BUDGET | Built |
| Groq | RATE_LIMIT | Built |
| Mistral | SPEND_BUDGET + RATE_LIMIT | Built |
| Junie | SPEND_BUDGET (manual check) | Built |
| Remote Dashboard (Agent Backend) | - | Built |

## Forgejo

https://git.angussoftware.dev/coda/fuel-dashboard-kmp
