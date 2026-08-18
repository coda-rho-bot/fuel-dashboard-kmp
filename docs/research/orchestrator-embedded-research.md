# Research: Can the Fuel Orchestrator Be Embedded in the KMP Desktop App?

*August 6, 2026*

## TL;DR - Yes, Fully Feasible

Every technical prerequisite is met. The desktop app is JVM-based, which
means it can run Ktor Server, SQLDelight, and coroutine-based background
loops - all proven patterns. The app already has direct provider adapters
that replicate the orchestrator's polling logic in native Kotlin. What
remains is wiring the pieces together: embed an HTTP server, port the
decision engine, and add SQLite persistence.

**The separate Node.js/Docker orchestrator can be eliminated.**

---

## Current Architecture (What We're Replacing)

```
+----------------------+         +----------------------+
|  Fuel Orchestrator   |         |  Fuel Dashboard KMP  |
|  (Node.js/Fastify)   |<--HTTP--|  (Compose Desktop)   |
|  systemd service     |  :8321  |  Ktor client polls   |
|  SQLite (decisions)  |  30s    |  every 30 seconds    |
|  Provider polling    |         |                      |
|  Decision engine     |         |  9+ direct provider  |
|  Burns fuel_state    |         |  adapters (already   |
|  .json for the mod   |         |  exist in Kotlin)    |
+----------------------+         +----------------------+
```

**The orchestrator does 5 things:**

1. **Polls providers** - shells out to bash/curl commands, parses JSON
2. **Computes burn rate** - two-point linear interpolation with reset-jump filtering
3. **Runs the decision engine** - picks optimal model from fuel state (pure function)
4. **Serves an HTTP API** - 6 REST endpoints on port 8321
5. **Writes fuel_state.json** - shared contract with the fuel-manager mod

**The desktop app already has capabilities 1 and 2** (direct provider
adapters + BurnRateCalculator). Capabilities 3-5 are what need porting.

---

## 1. Embedded HTTP Server (Ktor Server in Compose Desktop)

### Can we embed Ktor Server in the desktop app?

**YES.** The desktop target is JVM. Ktor Server is a JVM library. Multiple
real-world projects embed Ktor Server in Compose Desktop apps:

- **bog-walk/bootrack** (KotlinConf 2025 sample) - Compose Desktop app with
  embedded Ktor server + Exposed ORM. Server starts on app launch, shuts
  down on exit. https://github.com/bog-walk/bootrack
- **ktorio/ktor-chat** - official Ktor sample with Compose Desktop frontend
  and embedded Ktor server. https://github.com/ktorio/ktor-chat
- **Ktor full-stack KMP guide** - JetBrains' tutorial shows a Ktor server
  running alongside Compose Desktop.
  https://ktor.io/docs/full-stack-development-with-kotlin-multiplatform.html

### Key technical detail: start(wait = false)

```kotlin
val server = embeddedServer(CIO, port = 8321, host = "0.0.0.0") {
    routing {
        get("/fuel") { call.respond(fuelState) }
    }
}
server.start(wait = false)  // Non-blocking - UI thread continues
```

`start(wait = false)` launches the server on background threads without
blocking the Compose UI thread. The server runs for the lifetime of the
JVM process. Confirmed by Ktor ApplicationEngine API docs and a
StackOverflow answer about embedding Ktor in a desktop JVM app for mobile
client connections. https://stackoverflow.com/questions/65779892/

### Engine choice: CIO vs Netty

| Engine | HTTP/2 | Footprint | Recommendation |
|--------|--------|-----------|----------------|
| **CIO** | No | Lighter, coroutine-based | **Preferred** - already used for Ktor client |
| **Netty** | Yes | Heavier | Only if HTTP/2 needed (unlikely) |

CIO is the natural choice: JVM-native, coroutine-based, and the app
already uses the CIO engine for its Ktor client. CIO does NOT support
HTTP/2, but for a localhost API serving JSON to mobile clients, HTTP/2 is
unnecessary.

### Would it serve on localhost:8321?

**YES.** Same port, same endpoints. The orchestrator's api.ts maps 1:1 to
Ktor routes:

| Fastify endpoint | Ktor equivalent |
|------------------|-----------------|
| GET /fuel | get("/fuel") { call.respond(state) } |
| GET /decisions | get("/decisions") { ... } |
| GET /agents | get("/agents") { ... } |
| POST /agents/register | post("/agents/register") { ... } |
| GET /alerts | get("/alerts") { ... } |
| GET /health | get("/health") { ... } |

CORS available via install(CORS) { anyHost() }.

### Where in the KMP source structure?

Ktor Server is **JVM-only** - it cannot go in commonMain (confirmed in
https://github.com/JetBrains/compose-multiplatform/issues/568). It goes
in desktopMain:

```
composeApp/src/
+-- commonMain/          # Shared logic (decision engine, types)
+-- desktopMain/
    +-- main.kt          # Existing entry point
    +-- server/          # NEW: Embedded Ktor server
        +-- EmbeddedApiServer.kt
        +-- routes/      # Route handlers
```

### Verdict

**Fully feasible.** Well-documented, well-trodden path. No blockers.
~200 lines of Kotlin to replicate the 6 Fastify endpoints.

---

## 2. Background Polling While Minimized

### Do JVM desktop apps continue running when minimized?

**YES.** Fundamental JVM behavior. The JVM process continues executing
regardless of window state. Minimizing a window is a window manager
operation - it does not suspend the process.

### Can a coroutine-based polling loop run continuously?

**YES.** The app **already does this.** FuelViewModel has a
CoroutineScope(SupervisorJob() + Dispatchers.IO) with a while(true)
polling loop at 30-second intervals. This runs continuously while the app
is open. No changes needed for the embedded orchestrator.

### System tray support (minimize without quitting)

Compose Desktop has **built-in Tray composable** support, documented in
the official Kotlin Multiplatform docs
(https://kotlinlang.org/docs/multiplatform/compose-desktop-components.html).

```kotlin
fun main() = application {
    var isVisible by remember { mutableStateOf(true) }
    Tray(
        icon = painterResource("fuel-icon.png"),
        tooltip = "Fuel Dashboard",
        menu = {
            Item("Show", onClick = { isVisible = true })
            Item("Quit", onClick = { exitApplication() })
        }
    )
    if (isVisible) {
        Window(onCloseRequest = { isVisible = false }) {
            // Dashboard UI - polling and server continue
        }
    }
}
```

The current main.kt calls exitApplication() on window close. Changing this
to hide the window and show a tray icon is a ~30-line change.

### System sleep / power management

| Event | Behavior | Mitigation |
|-------|----------|------------|
| Display sleep | No effect | None needed |
| System sleep (S3/S4) | Process suspended | On wake, next poll resumes; stale ~30s |
| Hibernate | Same as sleep | Same |
| Lid close | OS-dependent | Same |

When the system wakes, the coroutine delay timer fires the next iteration
naturally. Acceptable - the orchestrator has the same behavior with
setInterval.

For polling during sleep, the OS-scheduler daemon pattern (systemd timer /
launchd / Task Scheduler) exists via worker-kmp
(https://github.com/MobileByteLabs/worker-kmp). Future enhancement.

### Verdict

**Fully feasible.** App already has continuous polling. System tray is a
~30-line change.

---

## 3. SQLite in KMP Desktop (SQLDelight)

### Can SQLDelight provide SQLite in the desktop app?

**YES.** SQLDelight supports JVM/Desktop via the sqlite-driver
(JDBC-based). Documented at
https://sqldelight.github.io/sqldelight/latest/multiplatform_sqlite/
and the KMP tutorial at
https://kotlinlang.org/docs/multiplatform/multiplatform-ktor-sqldelight.html.

### Driver setup

```kotlin
// desktopMain
actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        return JdbcSqliteDriver("jdbc:sqlite:${dbPath}")
            .also { Database.Schema.create(it) }
    }
}
```

### Schema mapping from orchestrator's better-sqlite3

The orchestrator has 4 tables (fuel_readings, model_decisions,
model_consumption, task_outcomes). They map directly to SQLDelight .sq
files. SQLDelight generates type-safe Kotlin from SQL at compile time -
strictly better than the orchestrator's raw SQL strings.

### Would this replace better-sqlite3?

**YES, completely.** Same SQLite file format. Data could be migrated by
copying the .db file.

### What this replaces in the current app

Currently, the app stores fuel history in FuelHistoryStore using
Preferences/SharedPreferences with JSON serialization, capped at 72
entries. SQLDelight provides unlimited history, proper SQL queries, and
the same tables the orchestrator uses. Foundation for the "LEARN" phase.

### Verdict

**Fully feasible.** Drop-in replacement for both better-sqlite3
(orchestrator) and FuelHistoryStore (app).

---

## 4. Burn Rate Computation (Holt's Linear) in Kotlin

### Does a Kotlin implementation exist?

**YES.** A proven Kotlin implementation of double exponential smoothing
(Holt's Linear) exists at
https://gist.github.com/salamanders/b61cb5dfef046d9d304d5f599d6727f3
~30 lines of pure Kotlin, no dependencies. Matches the Holt's Linear
equations from Hyndman and Athanasopoulos, *Forecasting: Principles and
Practice* (https://otexts.com/fpp2/holt.html).

### CRITICAL FINDING: The orchestrator does NOT actually use Holt's Linear

The SPEC.md says "Holt's Linear exponential smoothing" but **the actual
implementation in provider-monitor.ts (getBurnRate()) does NOT use Holt's
Linear.** It uses simple two-point linear interpolation with reset-jump
filtering: (last_pct - first_pct) / time_delta_hours, with reset jumps
(>30% increase) filtered out. This is the actual code at lines 219-233 of
provider-monitor.ts.

### What the app already has is BETTER

The app's BurnRateCalculator.kt uses OLS (Ordinary Least Squares) linear
regression over ALL data points - **more sophisticated** than the
orchestrator's two-point method. OLS is more robust to noise because it
uses every point, not just first and last.

### Upgrade path

1. **Immediate:** The app's existing OLS regression already exceeds the
   orchestrator's actual implementation. No change needed for parity.
2. **Enhancement:** Implement true Holt's Linear (~30 lines of Kotlin).
3. **Full feature:** Add damped trend variant for long-horizon projections.

BurnRateCalculator.kt already says: "MVP: simple OLS linear regression.
Holt's Linear is a future enhancement." The interface is designed for swap.

### Verdict

**Fully feasible.** The app already has superior burn-rate computation.
True Holt's Linear is a ~30-line addition if desired.

---

## 5. What Happens When the Desktop App Is Closed?

### The problem

When the desktop app exits, the embedded HTTP server stops. Mobile devices
connecting to the desktop's API lose connection.

### Is this acceptable?

**YES.** The system is designed for graceful degradation already:

- The orchestrator is not always running (crashes, restarts, downtime)
- The fuel-manager mod has a fuel_status.sh fallback
- **The mobile app already has direct provider adapters** - this is the
  existing "DIRECT" mode the app was built with

### Can the mobile app auto-detect and switch?

**YES.** Recommended pattern:

1. Mobile app tries the relay URL (desktop IP:8321) first
2. On connection failure, falls back to direct provider polling
3. Periodically retries the relay URL
4. UI shows: "Connected to relay" vs "Direct mode"

The app's FuelSource interface already abstracts the data source.

### What about the fuel-manager mod?

The mod reads ~/.letta/.fuel_state.json. When the desktop app is running,
the embedded orchestrator writes this file. When the desktop app is closed,
the mod is not running either (both are in the Letta Code desktop process).
**The lifecycles are naturally aligned.**

### Verdict

**Acceptable tradeoff.** Mobile falls back to direct polling. The mod's
lifecycle is aligned with the desktop app.

---

## 6. Desktop App as Relay for Mobile Devices

### Same-network (WiFi) relay

**YES.** The embedded Ktor server listens on 0.0.0.0:8321. Mobile devices
on the same WiFi connect to the desktop's LAN IP
(e.g., http://192.168.1.42:8321).

This is exactly the pattern from a StackOverflow answer about embedding
Ktor in a desktop JVM app for "listening on LAN for mobile client
connections" where the answer confirms start(wait=false) works.
https://stackoverflow.com/questions/65779892/

The app already has QR code-based settings sync (QrSyncDialog, QrCodeCanvas,
QrScanner). The same mechanism can share the relay URL with mobile devices.

### Remote access (outside WiFi)

**YES, via Cloudflare Tunnel.** The cloudflared binary creates an outbound
TLS tunnel from the desktop to Cloudflare's edge. No public IP, no router
port forwarding, no domain required.

```bash
cloudflared tunnel --url http://localhost:8321
# Generates: https://random.trycloudflare.com -> localhost:8321
```

This pattern is proven in production by multiple apps:

- **UnDercontrol** - Desktop app with SQLite backend + Cloudflare Tunnel.
  iOS connects to tunnel URL. "No public IP, no port forwarding."
  https://dev.to/oatnil/no-server-your-desktop-is-the-server-pf6
- **Locally Uncensored** - Desktop exposes API. Phone connects via LAN or
  tunnel. "Your desktop does the compute. Phone is thin client."
  https://locallyuncensored.com/remote-access/
- **Cloudburrow** - Desktop bridge uses Cloudflare Tunnel for per-device
  pairing. https://github.com/OpenAgentsInc/cloudburrow

For the fuel dashboard, the desktop app could manage cloudflared as a
subprocess, displaying the tunnel URL and QR code in the UI.

### Verdict

**Fully feasible.** LAN relay via 0.0.0.0 binding. Remote via Cloudflare
Tunnel, proven by multiple production apps with identical architecture.

---

## 7. Letta REST API Integration

### Can the app call GET /v1/agents directly?

**YES.** The app already has a LettaCloudProviderAdapter that calls
Letta's REST API (/v1/organizations/self/quotas and
/v1/organizations/self/billing-info). Adding GET /v1/agents is a
straightforward extension using the same Ktor client.

The orchestrator's /agents endpoint returns agent data from the Letta API.
The app can call this directly when a Letta API key is configured.

**No orchestrator needed for agent data - it is a direct API call.**

### Decision logging

Decision logging is local-only (on the desktop's SQLite). The orchestrator
currently logs decisions to SQLite and exposes them via /decisions. With
the embedded approach:

- Decisions are computed locally and logged to the desktop's SQLDelight DB
- The /decisions endpoint serves from this local DB
- Mobile clients read decisions via the API when relay is available

### Verdict

**Fully feasible.** Direct API call, no orchestrator needed.

---

## 8. Provider Monitoring Without the Node.js Orchestrator

### The app already has direct provider adapters

This is the strongest argument for embedding. The app already has Kotlin
implementations of every provider polling path:

| Provider | App adapter | Orchestrator equivalent |
|----------|-------------|------------------------|
| z.ai | ZaiProviderAdapter + ZaiDirectFuelSource | provider-monitor.ts parseZai() |
| Letta Cloud | LettaCloudProviderAdapter | provider-monitor.ts parseLetta() |
| OpenAI | OpenAIProviderAdapter | N/A (orchestrator uses static) |
| Anthropic | AnthropicProviderAdapter | N/A |
| DeepSeek | DeepSeekProviderAdapter | N/A |
| Groq | GroqProviderAdapter | N/A |
| Mistral | MistralProviderAdapter | N/A |
| Connected API | ConnectedApiProviderAdapter | N/A (IS the orchestrator client) |

### The z.ai quota check

The orchestrator's provider-monitor.ts shells out to bash/curl to call
https://api.z.ai/api/monitor/usage/quota/limit, then parses the JSON.

The app's ZaiDirectFuelSource does the same thing in **native Kotlin HTTP**
using Ktor client. Same endpoint, same auth header, same response parsing.
The app already does the z.ai quota check directly - no orchestrator
needed.

### What about the Letta quota check?

Same story. The orchestrator shells out to curl for
/v1/organizations/self/quotas and /v1/organizations/self/billing-info.

The app's LettaCloudProviderAdapter does both calls in native Kotlin. It
even ports the exact same bucket-mapping logic
(empty=0%, low=25%, medium=50%, high=75%, full=100%) and the exact same
exact-percentage-override logic from the billing endpoint.

### What the orchestrator's polling has that the app does not (yet)

The orchestrator persists every reading to SQLite for historical burn-rate
computation. The app stores readings in FuelHistoryStore (JSON, max 72
entries). Moving to SQLDelight (Section 3) closes this gap - unlimited
history, same queries.

The orchestrator also has a configurable poll interval per provider
(currently 2 minutes). The app uses a fixed 30-second interval for all
providers. This is configurable.

### Verdict

**Fully feasible - and mostly already done.** The app has native Kotlin
adapters for every provider. The orchestrator's bash/curl approach is
actually more fragile (depends on shell environment, secrets file sourcing,
etc.). The app's Ktor client approach is cleaner and more portable.

---

## 9. What Do Similar Apps Do?

### LLM observability/monitoring tools

| Tool | Architecture | Self-host? | Setup complexity |
|------|-------------|------------|------------------|
| **Arize Phoenix** | Single process (Python) | Free, full parity | **Easiest** - pip install, one process |
| **Langfuse** | Multi-service (Postgres + ClickHouse + Redis + S3) | Free OSS | Heavy - 4+ services |
| **Helicone** | 5-service compose stack | Helm chart | Medium - Next.js + Jawn + Supabase + ClickHouse + MinIO |
| **LangSmith** | Cloud or Enterprise-only | Enterprise tier only | N/A for self-host |
| **Braintrust** | Cloud or Enterprise | Enterprise only | N/A for self-host |

### Key insight: Phoenix proves the embedded model works

Arize Phoenix is the closest analog to what we want. It is a single
process that serves both the UI and the backend. You install it with pip,
run one command, and it serves a web UI + API on localhost. No separate
database server, no message queue, no multi-service compose stack.

From the research (https://www.morphllm.com/llm-observability-tools):
"Phoenix is the lightest start because it is one process with no metered
events." And: "Phoenix is easiest to host because it looks like 'collector
+ UI' and speaks OTLP."

**This is exactly the model for the embedded fuel orchestrator:** one
process (the desktop app) serves both the UI (Compose Desktop) and the
API (embedded Ktor server). No Docker, no separate service, no systemd.

### Why the others require separate services

Langfuse, Helicone, and LangSmith are designed for **multi-tenant,
high-ingestion-volume, team-collaboration** scenarios. They need:
- ClickHouse for analytics at scale
- Redis for job queues
- S3 for event payloads
- Separate workers for async processing

The fuel dashboard does not have these requirements. It is single-user,
low-volume (polling every 30 seconds), and the data is small (fuel
readings, decisions). SQLite is more than sufficient.

### Architecture pattern matching

| Our component | Phoenix analog |
|---------------|----------------|
| Compose Desktop UI | Phoenix web UI |
| Embedded Ktor server | Phoenix collector/API |
| SQLDelight (SQLite) | Phoenix SQLite/DuckDB backend |
| Provider adapters | OpenInference auto-instrumentors |
| Single desktop process | Single Python process |

### Verdict

The embedded model is validated by the industry. Phoenix (the recommended
lightweight option) uses exactly this pattern: single process, embedded
backend, SQLite storage. The fuel dashboard is even simpler because it
does not need OTLP ingestion or trace processing.

---

## 10. Minimum Viable Embedded Orchestrator

### What is the smallest feature set that eliminates the Node.js service?

The app already has: provider polling, burn-rate computation, UI, direct
provider adapters, multi-provider support, QR sync, 17 themes.

**The minimum additions to eliminate the orchestrator:**

#### Tier 1: Must-have (eliminates the orchestrator dependency)

| Feature | Effort | What it replaces |
|---------|--------|------------------|
| **Embed Ktor Server** (6 endpoints) | ~200 lines Ktor routes | api.ts (134 lines) |
| **Port decision engine** to Kotlin | ~250 lines (pure function) | decision-engine.ts (244 lines) |
| **Write fuel_state.json** | ~50 lines | orchestrator.ts writeFuelState() |
| **System tray** (minimize-to-tray) | ~30 lines | systemd service (keeps running when "closed") |

**Total Tier 1: ~530 lines of Kotlin**

This gives: desktop app runs the HTTP API, computes model recommendations,
writes fuel_state.json for the mod, and stays alive in the system tray.
The separate orchestrator process is no longer needed.

#### Tier 2: Should-have (full feature parity)

| Feature | Effort | What it replaces |
|---------|--------|------------------|
| **SQLDelight database** | Schema + driver factory | better-sqlite3 + database.ts |
| **Decision logging** to SQLite | SQLDelight queries | recordDecision() in database.ts |
| **Fuel readings history** in SQLite | SQLDelight queries | recordFuelReading() in database.ts |
| **GET /v1/agents** via Letta API | ~50 lines Ktor client | orchestrator agent registration |

**Total Tier 2: ~400 lines of Kotlin + .sq files**

#### Tier 3: Nice-to-have (enhanced features)

| Feature | Effort | Benefit |
|---------|--------|---------|
| **Cloudflare Tunnel** subprocess management | ~100 lines | Remote access for mobile |
| **Mobile auto-fallback** (relay -> direct) | ~100 lines | Seamless mobile experience |
| **True Holt's Linear** burn rate | ~30 lines | Better forecasting |
| **Alert webhooks** (Telegram etc.) | ~50 lines | Notifications when closed |
| **Task outcome tracking** | SQLDelight + UI | LEARN phase |

### What can be dropped entirely

The orchestrator has features that the embedded approach makes unnecessary:

| Orchestrator feature | Why it can be dropped |
|---------------------|----------------------|
| systemd service file | Desktop app + system tray replaces it |
| Docker container | JVM desktop app is self-contained |
| Docker Compose | Single process, no orchestration needed |
| Fastify HTTP framework | Replaced by Ktor |
| better-sqlite3 npm package | Replaced by SQLDelight |
| tsx / TypeScript runtime | Kotlin is compiled |
| Health check endpoint | Still useful, but simpler |
| secrets.env file sourcing | App stores keys in SettingsStore |
| bash/curl quota commands | Replaced by native Kotlin HTTP |

### Implementation effort estimate

| Component | Lines of Kotlin | Estimated time |
|-----------|----------------|----------------|
| Ktor server routes | ~200 | 2-3 hours |
| Decision engine port | ~250 | 2-3 hours |
| fuel_state.json writer | ~50 | 30 min |
| System tray | ~30 | 30 min |
| SQLDelight schema + driver | ~150 + .sq files | 2-3 hours |
| Letta /v1/agents integration | ~50 | 1 hour |
| Cloudflare Tunnel management | ~100 | 1-2 hours |
| Mobile fallback logic | ~100 | 1-2 hours |
| **Total** | **~930 lines** | **~11-15 hours** |

This is approximately 2-3 days of focused development work.

### Migration path

1. **Phase 1:** Add Ktor Server to desktopMain with /health and /fuel
   endpoints reading from in-memory state. Desktop app now serves API.
2. **Phase 2:** Port decision engine to commonMain. Add /decisions and
   /agents endpoints. Write fuel_state.json.
3. **Phase 3:** Add SQLDelight. Persist fuel readings and decisions.
4. **Phase 4:** Add system tray. Desktop stays alive when minimized.
5. **Phase 5:** Add Cloudflare Tunnel option for remote mobile access.
6. **Phase 6:** Add mobile auto-fallback logic.

Each phase is independently deployable. Phase 1 alone proves the concept.

---

## Summary: Feasibility Matrix

| Question | Answer | Confidence | Key evidence |
|----------|--------|------------|--------------|
| 1. Embedded HTTP server (Ktor) | **YES** | High | bootrack, ktor-chat, SO answer, Ktor docs |
| 2. Background polling minimized | **YES** | High | App already does it; Tray composable built-in |
| 3. SQLite (SQLDelight) | **YES** | High | Official KMP docs, JDBC driver, proven pattern |
| 4. Holt's Linear in Kotlin | **YES** | High | Existing gist; app OLS is already better |
| 5. App closed -> mobile impact | **Acceptable** | High | Direct mode fallback; mod lifecycle aligned |
| 6. Desktop as relay | **YES** | High | 0.0.0.0 binding; Cloudflare Tunnel proven |
| 7. Letta REST API | **YES** | High | App already calls Letta APIs |
| 8. Provider monitoring | **YES** | High | App already has all adapters in Kotlin |
| 9. Similar apps (Phoenix) | **Validated** | High | Phoenix = single process + SQLite |
| 10. Minimum viable | **~930 lines** | Medium | Feature-by-feature porting estimate |

## Recommendation

**Go for it.** The embedded approach is technically sound, validated by
industry patterns (Phoenix), and requires modest effort (~2-3 days).
The user experience improvement is massive: no Docker, no systemd, no
separate process to manage. Just install the desktop app and everything
works.

The orchestrator codebase becomes unnecessary for the primary use case.
It could be kept as an optional "headless" mode for power users who want
24/7 monitoring without keeping the desktop app open, but for Harry's use
case (simplest possible experience), the embedded approach is the right
answer.

### Key architectural decision

```
BEFORE:                              AFTER:
                                     +----------------------------------+
+------+    +---------+    +------+  |  Fuel Dashboard KMP (Desktop)    |
| Letta|    |Orchestra|    |Fuel  |  |                                  |
| Code |    |tor      |    |Dash  |  |  +------------+  +-------------+  |
| Desktop    |Node.js  |    |KMP   |  |  | Compose UI |  | Ktor Server |  |
|      |    |Fastify  |    |      |  |  | (existing) |  | (embedded)  |  |
| mod  |    |SQLite   |    |Ktor  |  |  +------------+  +-------------+  |
| reads|    |client   |    |client|  |  | SQLDelight (SQLite)         |  |
| .json|    |         |    |      |  |  | Decision Engine             |  |
|      |    |writes   |    |reads |  |  | Provider Adapters (existing)|  |
|      |<---|.json    |<---|API   |  |  | System Tray                 |  |
+------+    +---------+    +------+  |  | fuel_state.json writer      |  |
                                     +----------------------------------+
                                                |
                                    Mobile connects via LAN/tunnel
```

One process. One install. Zero friction.
