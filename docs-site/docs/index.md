# Fuel Dashboard

Cross-platform dashboard for monitoring AI provider fuel/quota status.
Monitor and manage provider fuel state across your AI fleet.

---

## What It Does

The Fuel Dashboard is a **Compose Multiplatform** desktop application that:

- **Monitors 9 provider types** simultaneously, each with its own adapter and fuel budgeting strategy
- **Recommends models** across providers based on tier selection and current fuel state
- **Exposes an embedded HTTP server** (port 8322) serving fuel data to mobile devices on the LAN
- **Runs an MCP server** allowing external AI agents to self-register, report status, and manage providers
- **Discovers agents** via the ACP (Agent Communication Protocol)
- **Syncs to mobile** via QR code credential transfer
- **Ships with 17 color themes**

## Multi-Provider System

The dashboard supports 9 provider types simultaneously. The embedded orchestrator
recommends models across providers based on tier selection and current fuel state.

| Provider | Limit Type | Status |
|----------|------------|--------|
| z.ai | Window Credit | Built |
| Letta Cloud | Window Credit | Built |
| OpenAI | Spend Budget + Rate Limit | Built |
| Anthropic | Spend Budget + Rate Limit | Built |
| DeepSeek | Spend Budget | Built |
| Groq | Rate Limit | Built |
| Mistral AI | Spend Budget + Rate Limit | Built |
| Junie | Spend Budget (manual check) | Built |
| Remote Dashboard (Agent Backend) | — | Built |

## Targets

| Platform | Status |
|----------|--------|
| Desktop (JVM) | :material-check: Primary |
| Android | :material-check: Builds |
| iOS | :material-circle-outline: Stub |

## Tech Stack

- **Compose Multiplatform** 1.9.0 — shared UI
- **Ktor Client** 3.4.3 — HTTP polling
- **Ktor Server** (CIO) — embedded HTTP API on port 8322
- **MCP Kotlin SDK** — Model Context Protocol server
- **Angus-Software-Theming** 0.10.4 — 17 community color schemes
- **angus-gradle-tools** 0.3.0 — Gradle convention plugins, coverage tooling
- **Kotlin** 2.3.21

## Quick Start

```bash
# Clone
git clone https://git.angussoftware.dev/coda/fuel-dashboard-kmp.git
cd fuel-dashboard-kmp

# Run the desktop app
./gradlew :composeApp:run
```

Configure provider credentials and dashboard settings on first launch.

## Key Features

### Embedded Server

The desktop app **is** the server. An embedded Ktor server on port **8322** serves
fuel state, decisions, agent fleet data, and alerts to any device on your LAN.
All endpoints require Bearer token authentication (except `/health`).

[:octicons-arrow-right-16: HTTP API Reference](api-reference/http-endpoints.md)

### MCP Server

Agents can self-register, update their model/status, add/remove providers, and read
fuel state through the standard Model Context Protocol at `POST /mcp`.

[:octicons-arrow-right-16: MCP Tools Reference](api-reference/mcp-tools.md)

### Decision Engine

The decision engine selects the optimal model by evaluating each provider's
**utilization ratio** (actual burn rate vs. optimal burn rate) and applying tier
upgrades or downgrades accordingly.

[:octicons-arrow-right-16: Decision Engine](architecture/decision-engine.md)

### QR Sync

Mobile devices can sync settings by scanning a QR code from the desktop app.
The QR code encodes the server URL and API key for instant credential transfer.

[:octicons-arrow-right-16: QR Sync Guide](guides/qr-sync.md)

### ACP Agent Discovery

Agents discover each other through the ACP protocol. Registered agents appear in
the fleet panel with their current model, status, and framework.

[:octicons-arrow-right-16: Agent Setup Guide](guides/agent-setup.md)
