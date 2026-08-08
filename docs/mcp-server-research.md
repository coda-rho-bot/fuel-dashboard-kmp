# MCP Server Research: Exposing the Fuel Dashboard as an MCP Server

> **Purpose:** Determine the technical approach for exposing the Fuel Dashboard as an MCP (Model Context Protocol)
> server, allowing agents to self-register, report their model/status, and read fuel state via the standardized
> MCP protocol.

---

## Executive Summary

**Yes, this is fully feasible and well-supported.** The official MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk`)
is a mature, JetBrains-co-maintained library on Maven Central. It has first-class Ktor integration, supports HTTP
transports (Streamable HTTP and SSE), and can be embedded directly into our existing Ktor server on port 8321 with
minimal code. No separate port or process is required.

**Key facts:**
- Latest version: **0.8.4** (Feb 2026) -- actively maintained, 19 releases since Dec 2024
- License: **Apache 2.0** (new contributions) / MIT (original code)
- Targets: **JVM, Native, JS, Wasm** (Kotlin Multiplatform) -- we only need JVM/desktop
- Maintained by **Anthropic** in collaboration with **JetBrains**
- GitHub: [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)

---

## 1. Is There a Kotlin/JVM MCP SDK?

**Yes -- it is the official SDK.**

### Maven Central Coordinates

| Artifact | Purpose |
|---|---|
| `io.modelcontextprotocol:kotlin-sdk` | Umbrella (client + server) |
| `io.modelcontextprotocol:kotlin-sdk-client` | Client only |
| `io.modelcontextprotocol:kotlin-sdk-server` | Server only |
| `io.modelcontextprotocol:kotlin-sdk-core` | Protocol types, JSON, transport abstractions |

For our use case, we only need **`kotlin-sdk-server`** (plus `kotlin-sdk-core` comes transitively).

### Version History (notable releases)

| Version | Date |
|---|---|
| 0.1.0 | Dec 17, 2024 |
| 0.3.0 | Jan 07, 2025 |
| 0.5.0 | Apr 30, 2025 |
| 0.6.0 | Jul 21, 2025 |
| 0.8.1 | Dec 04, 2025 |
| 0.8.3 | Jan 21, 2026 |
| **0.8.4** | **Feb 17, 2026 (latest)** |

19 total releases. Active development with roughly monthly cadence.

### Gradle Setup (JVM)

```kotlin
dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.8.4")
    // Ktor server engine (already in our project -- ktor-server-cio)
    implementation("io.ktor:ktor-server-cio:${ktorVersion}")
}
```

> **Important:** The MCP SDK uses Ktor internally but does **not** bring Ktor engine dependencies transitively.
> You must declare the Ktor server engine yourself. We already have `ktor-server-cio`, so this is a non-issue.

### Other Kotlin MCP Libraries

The official SDK is the only serious option. No alternative Kotlin MCP server libraries have meaningful adoption.
The SDK is co-maintained by JetBrains, so it is the canonical choice.

---

## 2. Can MCP Run Over HTTP Instead of stdio?

**Yes -- and HTTP is the recommended transport for remote deployments.**

The SDK supports four transport types:

| Transport | Use Case | Remote? |
|---|---|---|
| **Streamable HTTP** | Remote server, single endpoint, proxy-friendly | **Yes** (recommended) |
| **SSE** (Server-Sent Events) | Legacy HTTP transport, backwards compat | Yes |
| **WebSocket** | Full-duplex, long-running sessions | Yes |
| **stdio** | CLI/editor plugins, local subprocess | No |

### Recommended: Streamable HTTP

The SDK README explicitly states:

> `StreamableHttpClientTransport` and the Ktor `mcpStreamableHttp()` / `mcpStatelessStreamableHttp()` helpers expose
> MCP over a single HTTP endpoint with optional JSON-only or SSE streaming responses. **This is the recommended choice
> for remote deployments** and integrates nicely with proxies or service meshes.

For our Fuel Dashboard, agents connect remotely over the network. **Streamable HTTP is the right choice.** It mounts
at a single endpoint (default `/mcp`) and handles both session management and message exchange.

### SSE Transport (Alternative)

If we need maximum client compatibility (older MCP clients), SSE is available:

- `Application.mcp { }` -- auto-installs SSE + ContentNegotiation, mounts at `/`
- `Route.mcp { }` -- mounts at a specific route path, requires `install(SSE)` first

> **Note:** The SDK docs say "Prefer Streamable HTTP for new projects." SSE is for backwards compatibility only.

---

## 3. What Does an MCP Server Look Like in Kotlin?

### Server Creation Pattern

```kotlin
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

val mcpServer = Server(
    serverInfo = Implementation(
        name = "fuel-dashboard",
        version = "2.0.0"
    ),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = true),
            resources = ServerCapabilities.Resources(listChanged = true, subscribe = true),
        )
    )
)
```

### Defining Tools

Tools are model-invokable functions. The handler receives JSON arguments and returns a `CallToolResult`:

```kotlin
mcpServer.addTool(
    name = "register_agent",
    description = "Agent self-registers with the Fuel Dashboard",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            put("name", buildJsonObject { put("type", "string") })
            put("model", buildJsonObject { put("type", "string") })
            put("framework", buildJsonObject { put("type", "string") })
        }
    )
) { request ->
    val name = request.arguments?.get("name")?.jsonPrimitive?.content
    val model = request.arguments?.get("model")?.jsonPrimitive?.content
    // ... register the agent ...
    CallToolResult(content = listOf(TextContent("Agent '$name' registered with model '$model'")))
}
```

Key API points:
- `addTool(name, description, inputSchema) { request -> CallToolResult }` -- register a tool with a suspend handler
- `removeTool(name)` -- unregister at runtime
- `addTools(listOf(...))` / `removeTools(listOf(...))` -- batch operations
- `listChanged = true` in capabilities enables `notifications/tools/list_changed` when tools change at runtime

### Defining Resources

Resources are URIs that clients can read. The handler returns `ReadResourceResult`:

```kotlin
mcpServer.addResource(
    uri = "fuel://current",
    name = "Current Fuel State",
    description = "Current fuel levels for all providers",
    mimeType = "application/json"
) { request ->
    ReadResourceResult(
        contents = listOf(
            TextResourceContents(
                text = serializeFuelState(),
                uri = request.uri,
                mimeType = "application/json"
            )
        )
    )
}
```

Key API points:
- `addResource(uri, name, description, mimeType) { request -> ReadResourceResult }` -- register a resource
- `addResources(listOf(...))` -- batch register
- `subscribe = true` in capabilities allows clients to subscribe to resource updates
- Server can push updates via `sendResourceUpdated(sessionId, notification)`
- Resource URIs use custom schemes (e.g., `fuel://current`, `note://latest`)

### Starting the Server (Embedded in Ktor)

```kotlin
embeddedServer(CIO, host = "0.0.0.0", port = 8321) {
    mcpStreamableHttp {    // mounts MCP at /mcp
        mcpServer
    }
}.start(wait = true)
```

Or mount at a custom path:

```kotlin
embeddedServer(CIO, port = 8321) {
    mcpStreamableHttp(path = "/api/mcp") {
        mcpServer
    }
}.start(wait = true)
```

---

## 4. Can It Integrate with Our Existing Ktor Server?

**Yes -- seamlessly. This is the single most important finding.**

The MCP Kotlin SDK was built specifically for Ktor integration. It provides extension functions that plug MCP into
an existing `Application` or `Route` with zero boilerplate.

### Current Setup (EmbeddedServer.kt)

Our existing server in `composeApp/src/desktopMain/.../server/EmbeddedServer.kt`:

```kotlin
server = embeddedServer(CIO, host = host, port = port) { configureRouting() }
```

Inside `configureRouting()`, we install `CORS` and `ContentNegotiation`, then define REST routes (`/fuel`, `/agents`,
`/decisions`, `/alerts`, `/health`).

### Integration Approach: Same Port, Same Server

The MCP SDK's `mcpStreamableHttp()` is an `Application` extension function. We can call it inside our existing
`configureRouting()` block. **No separate port, no separate process.**

```kotlin
private fun Application.configureRouting() {
    install(CORS) { anyHost() }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }

    // --- Existing REST routes ---
    routing {
        get("/fuel") { /* ... */ }
        get("/agents") { /* ... */ }
        get("/health") { /* ... */ }
    }

    // --- MCP endpoint (Streamable HTTP at /mcp) ---
    mcpStreamableHttp {
        mcpServer  // pre-configured Server instance
    }
}
```

### Important Caveat: ContentNegotiation

> **Warning:** The `mcpStreamableHttp()` helper automatically installs `ContentNegotiation` with `McpJson`.
> The SDK docs say: "do not install it yourself, or a warning will be logged."
>
> However, our existing server already installs `ContentNegotiation` with a custom `Json` config for REST endpoints.
> We need to verify whether the MCP helper's auto-install conflicts with our existing install. Possible approaches:
>
> 1. **Let MCP install ContentNegotiation** -- remove our manual `install(ContentNegotiation)` and rely on the SDK's
>    auto-install (if our REST endpoints work with `McpJson`).
> 2. **Pre-install before calling the MCP helper** -- Ktor's `install()` is idempotent if already installed, so
>    the SDK may detect the existing install and skip its own.
> 3. **Use a separate Application or route-scoped approach** -- unlikely necessary but available.
>
> This needs testing during implementation, but it is a configuration detail, not a blocker.

### CORS for Browser-Based MCP Clients

If agents connect from browsers (or MCP Inspector is used for testing), CORS must allow MCP-specific headers:

```kotlin
install(CORS) {
    anyHost()
    allowHeader("Mcp-Session-Id")
    allowHeader("Mcp-Protocol-Version")
    exposeHeader("Mcp-Session-Id")
    exposeHeader("Mcp-Protocol-Version")
}
```

### Authentication

The `simple-streamable-server` sample demonstrates optional Bearer token authentication:

```bash
MCP_AUTH_TOKEN=my-secret ./gradlew run --args="--auth"
```

Clients include `Authorization: Bearer <token>`. We could gate the MCP endpoint behind our existing auth or a
simple shared token. The `Route.mcp()` SSE variant accepts `allowedHosts` and `allowedOrigins` parameters for
DNS rebinding protection.

---

## 5. What MCP Tools Should We Expose?

Based on the task requirements and the SDK API, three tools map directly:

### Tool: `register_agent`

```kotlin
mcpServer.addTool(
    name = "register_agent",
    description = "Agent self-registers with the Fuel Dashboard",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            put("name", buildJsonObject {
                put("type", "string")
                put("description", "Human-readable agent name")
            })
            put("model", buildJsonObject {
                put("type", "string")
                put("description", "Model handle (e.g., 'anthropic/claude-sonnet-5')")
            })
            put("framework", buildJsonObject {
                put("type", "string")
                put("description", "Agent framework (e.g., 'letta', 'crewai', 'autogen')")
            })
        }
    )
) { request ->
    val name = request.arguments?.get("name")?.jsonPrimitive?.content ?: "unknown"
    val model = request.arguments?.get("model")?.jsonPrimitive?.content ?: "unknown"
    val framework = request.arguments?.get("framework")?.jsonPrimitive?.content ?: "unknown"
    // TODO: Add to agents list / FleetAgent registry
    CallToolResult(content = listOf(TextContent(
        "Registered: name=$name, model=$model, framework=$framework"
    )))
}
```

### Tool: `update_model`

```kotlin
mcpServer.addTool(
    name = "update_model",
    description = "Agent reports that it has switched to a different model",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            put("model", buildJsonObject {
                put("type", "string")
                put("description", "New model handle (e.g., 'openai/gpt-4o')")
            })
        }
    )
) { request ->
    val model = request.arguments?.get("model")?.jsonPrimitive?.content ?: "unknown"
    // TODO: Update agent's model in registry
    CallToolResult(content = listOf(TextContent("Model updated to: $model")))
}
```

### Tool: `update_status`

```kotlin
mcpServer.addTool(
    name = "update_status",
    description = "Agent reports its current operational status",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            put("status", buildJsonObject {
                put("type", "string")
                put("description", "Current status (e.g., 'idle', 'working', 'waiting', 'error')")
            })
        }
    )
) { request ->
    val status = request.arguments?.get("status")?.jsonPrimitive?.content ?: "unknown"
    // TODO: Update agent's status in registry
    CallToolResult(content = listOf(TextContent("Status updated to: $status")))
}
```

### Design Notes

- All handlers are **suspend functions** -- they can do async work (database writes, network calls).
- Tools can return `isError = true` in `CallToolResult` to signal failures without throwing.
- The `listChanged = true` capability flag lets us dynamically add/remove tools at runtime.
- Tool input schemas use JSON Schema format via `kotlinx.serialization.json.buildJsonObject`.

---

## 6. What MCP Resources Should We Expose?

Resources are read-only data sources that agents can discover and fetch:

### Resource: `fuel://current`

```kotlin
mcpServer.addResource(
    uri = "fuel://current",
    name = "Current Fuel State",
    description = "Current fuel levels, costs, and limits for all configured providers",
    mimeType = "application/json"
) { request ->
    val json = serializeFuelState()  // our existing FuelResponse as JSON
    ReadResourceResult(
        contents = listOf(
            TextResourceContents(
                text = json,
                uri = request.uri,
                mimeType = "application/json"
            )
        )
    )
}
```

### Resource: `fuel://recommendation`

```kotlin
mcpServer.addResource(
    uri = "fuel://recommendation",
    name = "Model Recommendation",
    description = "Current model recommendation based on fuel levels, cost optimization, and headroom",
    mimeType = "application/json"
) { request ->
    val json = serializeRecommendation()  // recommendation data as JSON
    ReadResourceResult(
        contents = listOf(
            TextResourceContents(
                text = json,
                uri = request.uri,
                mimeType = "application/json"
            )
        )
    )
}
```

### Resource Subscriptions (Push Notifications)

With `subscribe = true` in capabilities, agents can subscribe to resource updates. When fuel state changes,
the server pushes notifications:

```kotlin
// When fuel state updates, notify all subscribed sessions
mcpServer.sessions.forEach { (key, session) ->
    mcpServer.sendResourceUpdated(
        sessionId = session.sessionId,
        notification = ResourceUpdatedNotification(uri = "fuel://current")
    )
}
```

This enables **push-based fuel monitoring** -- agents get notified when their fuel levels change, rather than polling.

---

## 7. Dependency Changes Required

### Version Catalog (`gradle/libs.versions.toml`)

```toml
[versions]
mcp-kotlin = "0.8.4"

[libraries]
# MCP Server (desktop only -- agents connect to us)
mcp-kotlin-server = { module = "io.modelcontextprotocol:kotlin-sdk-server", version.ref = "mcp-kotlin" }
```

### Build Script (`composeApp/build.gradle.kts`)

Add to `desktopMain.dependencies`:

```kotlin
desktopMain.dependencies {
    // ... existing deps ...
    implementation(libs.mcp.kotlin.server)
}
```

No new Ktor deps needed -- we already have `ktor-server-core`, `ktor-server-cio`, `ktor-server-cors`, and
`ktor-server-content-negotiation`.

---

## 8. Architecture: How It Fits Together

```
     Agents (Letta, Claude, Copilot, etc.)
          |
          | MCP over Streamable HTTP
          | POST http://<lan-ip>:8321/mcp
          v
  +-------------------------------------------+
  |        Fuel Dashboard Desktop App         |
  |                                           |
  |   +-----------------------------------+   |
  |   |        EmbeddedServer             |   |
  |   |     (Ktor, port 8321)             |   |
  |   |                                   |   |
  |   |  +-- REST Routes (existing) --+   |   |
  |   |  |  GET /fuel                |   |   |
  |   |  |  GET /agents              |   |   |
  |   |  |  GET /decisions           |   |   |
  |   |  |  GET /health              |   |   |
  |   |  +---------------------------+   |   |
  |   |                                   |   |
  |   |  +-- MCP Endpoint (new) ------+   |   |
  |   |  |  POST /mcp                 |   |   |
  |   |  |                            |   |   |
  |   |  |  Tools:                    |   |   |
  |   |  |    register_agent          |   |   |
  |   |  |    update_model            |   |   |
  |   |  |    update_status           |   |   |
  |   |  |                            |   |   |
  |   |  |  Resources:                |   |   |
  |   |  |    fuel://current          |   |   |
  |   |  |    fuel://recommendation   |   |   |
  |   |  +----------------------------+   |   |
  |   +-----------------------------------+   |
  |                                           |
  |   Shared State: fuelState, agents, alerts |
  +-------------------------------------------+
```

The MCP endpoint lives alongside the existing REST API on the same Ktor server. Both share the same volatile
state (`fuelState`, `agents`, `alerts`) and the same `DecisionRepository`. Tools can mutate state; resources
read from it.

---

## 9. Recommended Implementation Plan

### Step 1: Add Dependency

Add `mcp-kotlin-server` to `libs.versions.toml` and `composeApp/build.gradle.kts` (desktop only).

### Step 2: Create MCP Server Module

Create a new file (e.g., `server/McpServer.kt`) that:
- Creates a `Server` instance with tools and resources capabilities
- Registers the three tools (`register_agent`, `update_model`, `update_status`)
- Registers the two resources (`fuel://current`, `fuel://recommendation`)
- Exposes a function to get the configured `Server` instance

### Step 3: Wire into EmbeddedServer

In `EmbeddedServer.configureRouting()`, add:
```kotlin
mcpStreamableHttp { mcpServer }
```

Handle the ContentNegotiation conflict (test whether the MCP auto-install conflicts with our existing install).

### Step 4: Connect Tools to State

Wire tool handlers to mutate the existing `agents` list and read from `fuelState`. The `register_agent` tool
should add a `FleetAgent` to the agents list; `update_model` and `update_status` should find and update existing
agents by session ID or name.

### Step 5: Test with MCP Inspector

```bash
npx -y @modelcontextprotocol/inspector
```

Connect to `http://localhost:8321/mcp` and verify tools/resources are discoverable and callable.

---

## 10. Risks and Open Questions

| Risk/Question | Severity | Mitigation |
|---|---|---|
| ContentNegotiation double-install conflict | Low | Test during implementation; Ktor `install()` is idempotent |
| Agent identity in tool calls (no auth = who is calling?) | Medium | Use MCP session ID or require agent name in each tool call |
| MCP SDK version churn (still 0.x) | Low | Pin version; API has been stable across recent releases |
| Kotlin version compatibility (SDK needs Kotlin 2.2+) | Low | We are on Kotlin 2.3.21 -- compatible |
| Desktop-only constraint (MCP is desktop-only, same as our server) | None | Already the pattern for EmbeddedServer |

---

## References

- **GitHub**: [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)
- **Maven Central**: [io.modelcontextprotocol:kotlin-sdk](https://central.sonatype.com/artifact/io.modelcontextprotocol/kotlin-sdk)
- **API Docs**: [kotlin.sdk.modelcontextprotocol.io](https://kotlin.sdk.modelcontextprotocol.io/)
- **MCP Specification**: [modelcontextprotocol.io](https://modelcontextprotocol.io)
- **Sample: simple-streamable-server**: [samples/simple-streamable-server](https://github.com/modelcontextprotocol/kotlin-sdk/tree/main/samples/simple-streamable-server)
- **Sample: kotlin-mcp-server** (multi-transport): [samples/kotlin-mcp-server](https://github.com/modelcontextprotocol/kotlin-sdk/tree/main/samples/kotlin-mcp-server)
- **MCP Inspector**: [modelcontextprotocol/inspector](https://github.com/modelcontextprotocol/inspector)
