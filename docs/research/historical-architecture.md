# Fuel Management System — Full Stack Architecture (Historical)

> **Note:** This document describes the retired Fastify/orchestrator topology (pre-Aug 6).
> The orchestrator is now embedded directly in the desktop app. This file is preserved
> as historical reference only. For current architecture, see `docs/` directory.

*Last updated: August 4, 2026*

## Overview

A fuel optimization system for a multi-agent LLM fleet running on Letta Code.
Monitors every provider's fuel level, tracks burn rates, projects when fuel
will run out, and selects the optimal model for each LLM call — then displays
it all in a cross-platform dashboard with 17 selectable color themes.

```
┌─────────────────────────────────────────────────────────────┐
│                    LETTA CODE DESKTOP APP                     │
│                                                              │
│  ┌──────────────┐    ┌──────────────────────────────────┐    │
│  │  Coda, Angus, │    │  fuel-manager.ts (MOD)           │    │
│  │  Beacon, FORGE│    │  ── Per-turn model routing       │    │
│  │  Sinter       │    │  ── Bare name → BYOK rewrite     │    │
│  │  (5 agents)   │    │  ── Subagent model enforcement   │    │
│  └──────┬───────┘    └──────────┬───────────────────────┘    │
│         │                       │ reads                       │
│         │                       ▼                             │
│         │             ~/.letta/.fuel_state.json              │
│         │             ~/.letta/.fuel_display.txt             │
│  ┌──────┴────────────────────────────────────────────────┐   │
│  │  Letta Server (local, port 8283)                      │   │
│  │  ── Agent state, sessions, models                     │   │
│  │  ── /v1/agents/{id}/messages                          │   │
│  │  ── /v1/conversations                                 │   │
│  └──────┬────────────────────────────────────────────────┘   │
└─────────┼─────────────────────────────────────────────────────┘
          │
          │ Agent SDK queries
          ▼
┌─────────────────────────────────────────────────────────────┐
│                FUEL ORCHESTRATOR (systemd service)            │
│                  ~/dev/infra/fuel-orchestrator/               │
│                  TypeScript/ESM, SQLite, Fastify              │
│                                                              │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────────┐      │
│  │  MONITOR   │─▶│  DECIDE      │─▶│  EXECUTE         │      │
│  │            │  │              │  │                  │      │
│  │ Polls:     │  │ Decision     │  │ session.update   │      │
│  │ - z.ai API │  │ engine picks │  │ Model() for SDK  │      │
│  │ - Letta    │  │ optimal      │  │ sessions         │      │
│  │   agents   │  │ model        │  │ canUseTool for   │      │
│  │ - OpenRtr  │  │              │  │ subagent intercep│      │
│  └─────┬──────┘  └──────┬───────┘  └──────────────────┘      │
│        │                │                                     │
│        │                ▼                                     │
│        │         ┌──────────────┐                             │
│        │         │  SQLite DB   │  ← decision log, burn rates │
│        │         └──────────────┘                             │
│        │                                                     │
│        ▼                                                     │
│  writes .fuel_state.json ──▶ (mod reads this, above)        │
│  writes .fuel_display.txt ──▶ (plain-text fallback)          │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐    │
│  │  FASTIFY HTTP API  (127.0.0.1:8321)                  │    │
│  │                                                      │    │
│  │  GET /fuel       — current fuel state                │    │
│  │  GET /decisions  — recent model decisions (SQLite)   │    │
│  │  GET /agents     — managed fleet agents + models     │    │
│  │  GET /alerts     — active fuel alerts                │    │
│  │  GET /health     — uptime, PID, status               │    │
│  └──────────────────────┬───────────────────────────────┘    │
└─────────────────────────┼────────────────────────────────────┘
                          │
                          │ HTTP JSON (polled every 30s)
                          ▼
┌─────────────────────────────────────────────────────────────┐
│              FUEL DASHBOARD (KMP — Compose Multiplatform)     │
│              ~/dev/infra/fuel-dashboard-kmp/                  │
│              Forgejo: coda/fuel-dashboard-kmp                 │
│                                                              │
│  Platforms: Desktop (JVM), Android, iOS (stubbed)            │
│  Package: com.angussoftware.fueldashboard                    │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐    │
│  │  UI Layer (Compose Multiplatform 1.9.0)              │    │
│  │                                                      │    │
│  │  ┌─────────────┐  ┌──────────────┐  ┌────────────┐   │    │
│  │  │ Fuel Bars   │  │ Agent Fleet  │  │ Settings   │   │    │
│  │  │ (animated)  │  │ Panel (5)    │  │ Panel      │   │    │
│  │  ├─────────────┤  ├──────────────┤  ├────────────┤   │    │
│  │  │ Decision    │  │ Alerts Panel │  │ Theme Pick │   │    │
│  │  │ Log         │  │              │  │ (17 themes)│   │    │
│  │  ├─────────────┤  ├──────────────┤  ├────────────┤   │    │
│  │  │ Recommend.  │  │ Countdown    │  │ API URL    │   │    │
│  │  │ Banner      │  │ Timers       │  │ Config     │   │    │
│  │  └─────────────┘  └──────────────┘  └────────────┘   │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐    │
│  │  State Layer                                         │    │
│  │  FuelViewModel ──▶ Ktor client ──▶ HTTP API :8321    │    │
│  │  (30s poll)                                          │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐    │
│  │  Theme Layer (Angus-Software-Theming 0.10.4)         │    │
│  │  17 color themes: Angus, Catppuccin (4), Nord (2),   │    │
│  │  Gruvbox (2), Solarized (2), Dracula, Rose Pine (3)  │    │
│  │  Separate light/dark selectors with mismatch warnings │    │
│  │  Persisted per-platform (Preferences / SharedPrefs)   │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                              │
│  Dependencies:                                               │
│  - Compose Multiplatform 1.9.0 (UI framework)               │
│  - Ktor 3.4.3 (HTTP client)                                 │
│  - Angus-Software-Theming 0.10.4 (themes)                   │
│  - angus-gradle-tools 0.3.0 (build plugins)                 │
│  - ZERO Letta dependencies (pure API consumer)              │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## Components in Detail

### 1. Fuel Providers (the fuel sources)

| Provider | Type | Replenishes | Monitored by |
|---|---|---|---|
| z.ai BYOK | API key (glm-5.2) | Yes — 5h sliding window | z.ai API (TOKENS_PCT) |
| Letta Cloud | Managed credits | No — finite monthly budget | session.listModels() |
| OpenRouter | Paid API key | No — on-demand billing | Static (no real balance API) |
| Ollama | Local model | Unlimited | Static (always 100%) |

**Core problem solved:** Agents don't know what fuel they're burning. A subagent
spawned without an explicit model silently uses Letta Cloud credits (finite,
costs money) instead of z.ai BYOK (replenishing, free). The system ensures
qualified handles (`lc-zai-coding/glm-5.2`) are always used for BYOK routing.

### 2. Fuel Orchestrator (`~/dev/infra/fuel-orchestrator/`)

**Deployment:** systemd service (`fuel-orchestrator.service`)
**Runtime:** Node.js/ESM with tsx, runs as persistent background process
**Database:** SQLite (`fuel-orchestrator.db`) — decision log, burn rates, cost profiles

**Source files (10):**
| File | Role |
|---|---|
| `index.ts` | Entry point, wires all modules, starts API server |
| `orchestrator.ts` | Main loop — monitor → decide → execute → learn |
| `provider-monitor.ts` | Polls z.ai API and Letta API for fuel levels |
| `decision-engine.ts` | Pure function — picks optimal model from fuel state |
| `api.ts` | Fastify HTTP server (port 8321), 6 REST endpoints |
| `database.ts` | SQLite schema + queries |
| `config.ts` | Config loading + validation |
| `subagent.ts` | SDK `canUseTool` callback for subagent interception |
| `types.ts` | FuelState, ProviderReport, Decision types |
| `utils.ts` | Helpers (path expansion, formatting) |

**Writes:**
- `~/.letta/.fuel_state.json` — rich fuel state (the orchestrator↔mod contract)
- `~/.letta/.fuel_display.txt` — plain-text snapshot (readable fallback)

### 3. Fuel Manager Mod (`~/.letta/mods/fuel-manager.ts`)

**Type:** Letta Code mod (runs inside the desktop app process)
**Trigger:** `turn_start` event — fires on every LLM call
**Runtime:** TypeScript, loaded at app startup

**Three roles:**
1. **turn_start** — estimates task complexity from the incoming message, reads
   `.fuel_state.json`, applies the decision algorithm, calls
   `ctx.conversation.updateLlmConfig()` to route to the optimal model
2. **tool_start** — rewrites bare model names (`glm-5.2`) to qualified BYOK
   handles (`lc-zai-coding/glm-5.2`) to prevent Letta Cloud credit drain
3. **Permission overlay** — enforces model specification on `Agent()`/`Task()`
   subagent calls, ensuring spawned subagents use BYOK models

**Fallback:** When the orchestrator isn't running, the mod falls back to
`fuel_status.sh` output for basic fuel state.

**Config:** `~/.letta/fuel-config.json`
**Disable:** `touch ~/.letta/.fuel-manager-disabled`

### 4. Fastify HTTP API (port 8321)

**Purpose:** Decouples dashboards from direct SQLite/file access. Provides a
clean REST data contract that any client (web, mobile, KMP) can consume.

| Endpoint | Returns | Source |
|---|---|---|
| `GET /fuel` | Current fuel state (providers, burn rate, recommendation) | In-memory state |
| `GET /decisions?limit=N` | Recent model decisions | SQLite |
| `GET /agents` | Fleet agents + current models + allocations | Letta API |
| `GET /alerts` | Active fuel alerts (critical thresholds) | Computed |
| `GET /health` | Service status (uptime, PID) | Runtime |
| `GET /` | Endpoint listing | Static |

CORS enabled for all origins (dashboards may run on different hosts).

### 5. Fuel Dashboard KMP (`~/dev/infra/fuel-dashboard-kmp/`)

**Repo:** https://github.com/coda-rho-bot/fuel-dashboard-kmp
**Platforms:** Desktop (JVM — primary), Android (ready), iOS (stubbed)
**Package:** `com.angussoftware.fueldashboard`
**Build:** Gradle 9.6.1, Kotlin 2.3.21, Compose Multiplatform 1.9.0

**Architecture:** MVVM (ViewModel + StateFlow + Compose)

```
composeApp/src/
├── commonMain/           # Shared across all platforms
│   ├── model/            # FuelModels.kt — API response data classes
│   ├── network/          # FuelApiClient.kt — Ktor HTTP client
│   ├── presentation/     # FuelViewModel.kt — state + polling
│   ├── settings/         # ThemeController.kt, SettingsStore.kt (expect)
│   ├── ui/               # App.kt — main composable, two-column layout
│   │   ├── components/   # FuelBar, DecisionLog, SettingsPanel, etc.
│   │   └── theme/        # DashboardTheme.kt (expect)
│   └── util/             # TimeProvider.kt (expect)
├── desktopMain/          # Desktop (JVM) specifics
│   ├── main.kt           # Application entry point, Window, 1200x800
│   ├── settings/         # SettingsStore.kt (java.util.prefs)
│   ├── ui/theme/         # DashboardTheme.kt — AngusTheme + system dark detection
│   └── util/             # TimeProvider.kt
├── androidMain/          # Android specifics
│   ├── MainActivity.kt   # Activity + setContent
│   ├── FuelDashboardApplication.kt
│   ├── settings/         # SettingsStore.kt (SharedPreferences)
│   ├── ui/theme/         # DashboardTheme.kt — AngusTheme + Material You
│   └── util/             # TimeProvider.kt
└── iosMain/              # iOS specifics (stubbed)
    ├── MainViewController.kt
    └── util/             # TimeProvider.kt
```

**Dependencies:**
| Dependency | Version | Role |
|---|---|---|
| Compose Multiplatform | 1.9.0 | UI framework |
| Kotlin | 2.3.21 | Language |
| Ktor | 3.4.3 | HTTP client (CIO engine on desktop) |
| kotlinx-coroutines | 1.11.0 | Async polling (30s interval) |
| kotlinx-serialization | 1.10.0 | JSON parsing |
| kotlinx-datetime | 0.7.1 | Timestamp formatting |
| Angus-Software-Theming | 0.10.4 | 17 color themes, brand identity |
| angus-gradle-tools | 0.3.0 | Build plugins (coverage, bundling) |
| AndroidX Lifecycle | 2.10.0 | ViewModel |

**Notable:** The dashboard has ZERO Letta dependencies. It's a pure API consumer
that talks to the Fastify HTTP endpoint. It doesn't know about agents, mods,
or the Letta platform — just fuel data JSON.

## Data Flow

```
User types message in Letta Code
        │
        ▼
Desktop app fires turn_start event
        │
        ▼
fuel-manager.ts mod intercepts
        │
        ├─ reads ~/.letta/.fuel_state.json
        ├─ estimates complexity from message
        ├─ applies decision algorithm
        └─ calls updateLlmConfig() → routes to optimal model
        │
        ▼
LLM call goes to z.ai BYOK (or Letta Cloud, or OpenRouter)
        │
        ▼
Meanwhile (async, every 5 min):
  Orchestrator polls z.ai API → updates fuel state
  Orchestrator writes .fuel_state.json → mod reads next turn
  Orchestrator logs decision to SQLite
  Orchestrator updates in-memory API state
        │
        ▼
Dashboard (every 30s):
  Ktor client → GET /fuel, /agents, /decisions, /alerts
  ViewModel updates StateFlow
  Compose recomposes → animated fuel bars, agent panel, etc.
```

## Deployment

| Component | Deployment | Auto-start |
|---|---|---|
| Orchestrator | systemd service (`fuel-orchestrator.service`) | Yes (enabled) |
| Mod | Letta Code desktop app (loaded at startup) | Yes (when app runs) |
| API | Embedded in orchestrator (port 8321) | Yes (with orchestrator) |
| Dashboard | `./gradlew :composeApp:run` or `fuel-dashboard` command | No (manual launch) |

## Configuration Files

| File | Purpose |
|---|---|
| `~/.letta/fuel-config.json` | Mod config (providers, models, tiers, priorities) |
| `~/.letta/.fuel_state.json` | Shared state (orchestrator → mod contract) |
| `~/.letta/.fuel_display.txt` | Plain-text fallback (human-readable) |
| `~/dev/infra/fuel-orchestrator/fuel-orchestrator.db` | SQLite (decisions, burn rates) |
| `/etc/systemd/system/fuel-orchestrator.service` | systemd unit |

## Related Documentation

- [SPEC.md](./SPEC.md) — Detailed orchestrator + mod design spec (46KB, living)
- [Angus-Software-Theming](https://git.angussoftware.dev/rhomancer/angus-software-theming) — Theme library source
- [Fuel Dashboard KMP](https://github.com/coda-rho-bot/fuel-dashboard-kmp) — Dashboard repo
