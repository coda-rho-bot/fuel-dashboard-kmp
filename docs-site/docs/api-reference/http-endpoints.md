# HTTP API Reference

The embedded Ktor server listens on **port 8322** at **host 0.0.0.0** (all
interfaces, reachable from the LAN).

## Authentication

All endpoints require a **Bearer token** in the `Authorization` header, except
`GET /health`:

```http
Authorization: Bearer <your-api-key>
```

The API key is a 256-bit random value generated on first launch and persisted in
platform settings. View or regenerate it in **Settings > Server**.

??? info "CORS"
    CORS is enabled for all origins (`anyHost()`). This is safe because HTTP
    credentials are not enabled — clients must still provide the Bearer API key
    explicitly.

---

## Endpoints

### `GET /`

Returns service metadata and a list of available endpoints. Requires auth.

**Response:**

```json
{
  "service": "fuel-dashboard",
  "version": "2.0",
  "endpoints": [
    "GET /fuel",
    "GET /decisions",
    "GET /agents",
    "GET /alerts",
    "GET /health (no auth)",
    "POST /agents/register",
    "POST /agents/{id}/state",
    "DELETE /agents/{id}",
    "POST /mcp (MCP Streamable HTTP)"
  ]
}
```

---

### `GET /fuel`

Returns the current fuel state for all configured providers, including burn rate
and model recommendation.

**Query parameters:** None

**Response (`FuelResponse`):**

```json
{
  "providers": {
    "z.ai": {
      "remainingPct": 78,
      "available": true,
      "windowPosition": 0.35,
      "resetsAt": { "window": 1734567890000 }
    }
  },
  "recommendedModel": "anthropic/claude-sonnet-5",
  "burnRatePctPerHr": 12.5,
  "surplusAlert": false,
  "junie": {
    "balance": 450.0,
    "license": "PRO-XXXX",
    "lastChecked": 1734567000000
  }
}
```

The `junie` field is included if a Junie balance has ever been checked manually.

---

### `GET /decisions`

Returns recent model routing decisions from the decision engine, persisted in SQLite.

**Query parameters:**

| Parameter | Type | Default | Range | Description |
|-----------|------|---------|-------|-------------|
| `limit` | Integer | 20 | 1–100 | Maximum number of decisions to return |

**Response (`DecisionsResponse`):**

```json
{
  "decisions": [
    {
      "id": "uuid-string",
      "agentId": "coda",
      "modelHandle": "anthropic/claude-sonnet-5",
      "provider": "Anthropic",
      "tier": "MEDIUM",
      "complexity": "MEDIUM",
      "utilizationRatio": 0.72,
      "headroom": 15,
      "reason": "medium (task floor), ratio 0.72, 85% remaining",
      "timestamp": 1734567890000
    }
  ]
}
```

---

### `GET /agents`

Returns all known agents — both ACP-discovered agents and those that
self-registered via HTTP or MCP.

**Response (`AgentsResponse`):**

```json
{
  "agents": [
    {
      "agentId": "coda",
      "name": "Coda",
      "currentModel": "anthropic/claude-sonnet-5",
      "lastTaskComplexity": "MEDIUM",
      "fuelAllocation": 0,
      "activeSubagents": 2
    }
  ]
}
```

Registered agents (from `POST /agents/register` or MCP `register_agent`) that
are not also ACP-discovered appear with `fuelAllocation: 0` and
`activeSubagents: 0`.

---

### `POST /agents/register`

Registers a new agent or updates an existing one (deduplication by name,
case-insensitive).

**Request body (`RegisterAgentRequest`):**

```json
{
  "name": "Coda",
  "model": "anthropic/claude-sonnet-5",
  "framework": "letta",
  "command": "letta run coda"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | String | Yes | Human-readable agent name |
| `model` | String | No | Current model handle |
| `framework` | String | No | Agent framework (e.g., `letta`, `crewai`, `autogen`) |
| `command` | String | No | Command to start the agent |

**Response (`RegisterAgentResponse`):**

```json
{
  "status": "registered",
  "agentId": "coda"
}
```

The `agentId` is derived from the name (lowercased, spaces replaced with hyphens).
If an agent with the same name already exists, it is updated and the existing ID
is returned.

---

### `POST /agents/{id}/state`

Updates an agent's model, status, or capabilities.

**Path parameters:**

| Parameter | Description |
|-----------|-------------|
| `id` | The agent ID (returned from registration) |

**Request body (`UpdateAgentStateRequest`):**

```json
{
  "model": "openai/gpt-4o",
  "status": "working",
  "capabilities": ["code-generation", "analysis"]
}
```

All fields are optional — only provided fields are updated.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `model` | String | No | New model handle |
| `status` | String | No | Current status (`idle`, `working`, `waiting`, `error`) |
| `capabilities` | String[] | No | List of agent capabilities |

**Response (`StateUpdateResponse`):**

```json
{
  "status": "updated"
}
```

Returns `404` if the agent ID is not found.

---

### `DELETE /agents/{id}`

Removes a registered agent from both in-memory state and SQLite.

**Path parameters:**

| Parameter | Description |
|-----------|-------------|
| `id` | The agent ID to remove |

**Response (`StateUpdateResponse`):**

```json
{
  "status": "removed"
}
```

Returns `404` if the agent ID is not found.

---

### `GET /alerts`

Returns active fuel alerts (critical threshold warnings).

**Response (`AlertsResponse`):**

```json
{
  "alerts": [
    "z.ai fuel critically low: 8% remaining",
    "Anthropic budget 92% consumed"
  ]
}
```

---

### `GET /health`

Lightweight health check. **No authentication required** — suitable for uptime
monitors and load balancers.

**Response:**

```json
{
  "status": "ok"
}
```

---

### `POST /mcp`

MCP (Model Context Protocol) endpoint using Streamable HTTP transport. This is
the entry point for MCP clients (AI agents) to call tools and read resources.

See the [MCP Tools Reference](mcp-tools.md) for available tools and resources.

Requires the same Bearer token authentication as all other endpoints.
