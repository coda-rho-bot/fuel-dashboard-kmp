# Architecture Overview

## The Big Picture

The Fuel Dashboard is a **single desktop application** that combines three roles
that were previously separate processes:

1. **Poller** — directly queries 9 LLM provider APIs for fuel/quota status
2. **Orchestrator** — runs the decision engine and serves the HTTP API
3. **Dashboard UI** — Compose Multiplatform interface for visualizing fuel state

!!! info "Key Architectural Change"
    Previous versions used a separate Node.js/Fastify orchestrator process
    (`fuel-orchestrator`) that the dashboard polled over HTTP. **This has been
    eliminated.** The orchestrator is now embedded directly in the desktop app.
    There is no external service, no systemd unit, no Docker container — just one
    JVM process.

## System Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                     FUEL DASHBOARD DESKTOP APP                       │
│                   (Single JVM Process, Kotlin)                      │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │  UI Layer (Compose Multiplatform 1.9.0)                       │ │
│  │                                                               │ │
│  │  Fuel Bars | Agent Fleet | Decision Log | Settings Panel      │ │
│  │  Alerts     | Countdowns  | Recommendations | Theme Picker    │ │
│  └────────────────────────────┬──────────────────────────────────┘ │
│                               │ StateFlow                           │
│                               ▼                                     │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │  Presentation Layer                                           │ │
│  │  FuelViewModel → StateFlow → Compose recomposition            │ │
│  │  Polls every 30 seconds                                       │ │
│  └──────┬──────────────────────────────────────────┬─────────────┘ │
│         ▼                                        ▼                 │
│  ┌──────────────────────┐         ┌──────────────────────────────┐ │
│  │  Network Layer        │         │  Embedded Server (Ktor CIO) │ │
│  │  Ktor Client 3.4.3    │         │  Port 8322, host 0.0.0.0    │ │
│  │                       │         │                              │ │
│  │  9 Provider Adapters: │         │  HTTP API:                   │ │
│  │  z.ai, Letta Cloud,   │         │    GET /fuel                 │ │
│  │  OpenAI, Anthropic,   │         │    GET /decisions            │ │
│  │  DeepSeek, Groq,      │         │    GET /agents               │ │
│  │  Mistral, Junie,      │         │    GET /alerts               │ │
│  │  Remote Dashboard     │         │    GET /health (no auth)     │ │
│  │                       │         │    POST /agents/register     │ │
│  │  Polls provider APIs  │         │    POST /agents/{id}/state   │ │
│  │  for fuel status      │         │    DELETE /agents/{id}       │ │
│  └──────────┬────────────┘         │    POST /mcp (MCP server)    │ │
│             │                      │                              │ │
│             │                      │  MCP Server (7 tools,        │ │
│             │                      │  2 resources)                │ │
│             │                      └──────────────┬───────────────┘ │
│             │                                     │                 │
│             ▼                                     ▼                 │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │  Decision Engine                                               │ │
│  │  assessProvider() → selectTier() → findModelForTier()         │ │
│  │  Uses utilization ratio (burn rate vs optimal rate)            │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │  Persistence (SQLite)                                          │ │
│  │  Decision log | Agent registry | Burn rates                    │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │  Theme Layer (Angus-Software-Theming 0.10.4)                  │ │
│  │  17 color themes | System dark mode detection                  │ │
│  └───────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
         │                                              │
         ▼ HTTP/HTTPS                                   ▼ HTTP/HTTPS
┌─────────────────────┐                    ┌─────────────────────────┐
│  LLM Provider APIs  │                    │  Mobile / LAN Clients   │
│  (z.ai, OpenAI,     │                    │  (phones, tablets,      │
│  Anthropic, etc.)   │                    │  other dashboards)      │
└─────────────────────┘                    └─────────────────────────┘
```

## Layers

### 1. UI Layer

Built with **Compose Multiplatform 1.9.0**. Key components:

- **Fuel Bars** — animated progress bars showing each provider's remaining fuel
- **Agent Fleet Panel** — shows ACP-discovered and self-registered agents
- **Decision Log** — recent model routing decisions from the decision engine
- **Settings Panel** — provider configuration, server settings, theme picker
- **Alerts Panel** — active fuel alerts when providers hit critical thresholds
- **Countdown Timers** — shows time until rate limit windows reset
- **Recommendation Banner** — current recommended model with reasoning

### 2. Presentation Layer

`FuelViewModel` drives the UI using Kotlin `StateFlow`. It:

- Polls all configured provider adapters every 30 seconds
- Pushes updates to the `EmbeddedServer`'s volatile state fields
- Triggers Compose recomposition on state changes

### 3. Network Layer

**Ktor Client 3.4.3** with the CIO engine. Contains 9 provider adapters, one per
`ProviderKind`:

- Each adapter knows how to query its provider's quota/usage API
- Adapters normalize responses into the common `FuelSource` / `ProviderStateInfo` model
- The Remote Dashboard adapter can connect to another Fuel Dashboard instance

### 4. Embedded Server

**Ktor Server (CIO engine)** on port **8322**, bound to `0.0.0.0`.

- Serves fuel state, decisions, agents, and alerts over HTTP JSON
- Provides MCP (Model Context Protocol) endpoint at `POST /mcp`
- All endpoints (except `/health`) require Bearer token authentication
- CORS is enabled for all origins (safe because credentials are not enabled)

See [HTTP API Reference](../api-reference/http-endpoints.md) and
[MCP Tools Reference](../api-reference/mcp-tools.md).

### 5. Decision Engine

A pure-function engine that selects the optimal model for a given task. It
evaluates each provider's **utilization ratio** — the ratio of actual burn rate
to the optimal burn rate needed to deplete fuel exactly as the window resets.

See [Decision Engine](decision-engine.md) for the full algorithm.

### 6. Persistence

**SQLite** stores:

- **Decision log** — every model routing decision with timestamp, provider, tier, reason
- **Agent registry** — self-registered agents (persisted across restarts)
- **Burn rates** — historical burn rate data for projection calculations

## Key Design Principles

### Zero External Dependencies

The desktop app has **no dependency on Letta** or any external service. It is a
pure API consumer that talks directly to LLM provider APIs. The embedded server
serves data to mobile clients, not to itself.

### Single Source of Truth

The agent registry is shared between the HTTP API and the MCP server. Agents
registered via `POST /agents/register` or the `register_agent` MCP tool appear in
both `GET /agents` and the MCP resources.

### Cross-Platform by Design

All business logic lives in `commonMain/`. Platform-specific code is minimal:

- **Desktop:** Window creation, system dark mode detection, `java.util.prefs`
- **Android:** Activity lifecycle, `SharedPreferences`, Material You
- **iOS:** Stub (not yet implemented)

## Technology Stack

| Dependency | Version | Role |
|------------|---------|------|
| Compose Multiplatform | 1.9.0 | UI framework |
| Kotlin | 2.3.21 | Language |
| Ktor Client | 3.4.3 | HTTP client (CIO engine) |
| Ktor Server | 3.4.3 | Embedded HTTP server (CIO engine) |
| MCP Kotlin SDK | — | Model Context Protocol server |
| kotlinx-coroutines | 1.11.0 | Async polling (30s interval) |
| kotlinx-serialization | 1.10.0 | JSON parsing |
| kotlinx-datetime | 0.7.1 | Timestamp formatting |
| Angus-Software-Theming | 0.10.4 | 17 color themes |
| angus-gradle-tools | 0.3.0 | Build plugins (coverage, bundling) |
| AndroidX Lifecycle | 2.10.0 | ViewModel |
