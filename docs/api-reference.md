# API Reference

The embedded HTTP server runs on `0.0.0.0:8322` (LAN-accessible). All endpoints
except `/health` require an API key via `Authorization: Bearer <key>` header.

## GET / — Service Info

Returns service name, version, and list of available endpoints. No auth required.

**Response:**

```json
{
  "name": "fuel-dashboard",
  "version": "0.1",
  "endpoints": ["GET /fuel", "GET /decisions", ...]
}
```

## GET /fuel — Fuel State

Primary endpoint. Returns the full fuel state for all configured providers.

**Response (snake_case wire format):**

```json
{
  "providers": [
    {
      "id": "zai",
      "display_name": "z.ai",
      "category": "window_credit",
      "remaining_pct": 75.0,
      "reset_ms": 18000000,
      "provider": {
        "remaining_pct": 75.0,
        "resets_at": "2026-08-23T12:00:00Z",
        "window_hours": 5.0
      }
    }
  ],
  "provider_resets": {},
  "provider_windows": {},
  "burn_rate_pct_per_hr": 2.5,
  "window_positions": {},
  "recommended_model": "glm-5.2",
  "mod_managed": false,
  "surplus_alert": null,
  "junie": {
    "balance": 35.0,
    "last_checked": "2026-08-23T18:00:00Z"
  }
}
```

**Key fields:**

- `remaining_pct` — fuel remaining as percentage (0-100)
- `reset_ms` — milliseconds until quota window resets
- `burn_rate_pct_per_hr` — computed burn rate (percentage points per hour)
- `recommended_model` — advisor's current model recommendation
- `provider_resets` / `provider_windows` — per-provider reset timing and window info

## GET /decisions — Decision History

Returns recent decision-engine records.

**Query params:** `?limit=N` (default 20, max 100)

**Response:**

```json
{
  "decisions": [
    {
      "id": 1,
      "agent_id": "agent-abc123",
      "model_handle": "glm-5.2",
      "provider": "zai",
      "tier": "smart",
      "complexity": "high",
      "utilization_ratio": 0.75,
      "headroom": 25,
      "reason": "Surplus quota, smart model is effectively free",
      "timestamp": "2026-08-23T18:00:00Z"
    }
  ]
}
```

## GET /agents — Agent List

Returns all known agents (ACP-discovered, MCP-registered, and config-synced).

**Response:**

```json
{
  "agents": [
    {
      "agentId": "agent-abc123",
      "name": "Coda",
      "currentModel": "glm-5.2",
      "lastTaskComplexity": "high",
      "fuelAllocation": 80,
      "activeSubagents": 2
    }
  ]
}
```

**Note:** `agentId`, `currentModel`, `lastTaskComplexity`, and `fuelAllocation`
use camelCase (not snake_case) in the wire format.

## POST /agents/register — Register Agent

Registers an agent with the dashboard (MCP self-registration path).

**Request body:**

```json
{
  "agentId": "agent-abc123",
  "name": "Coda",
  "currentModel": "glm-5.2"
}
```

## POST /agents/{id}/state — Update Agent State

Updates an agent's model, status, or task complexity. Persists to the agent
registry (survives restarts).

**Request body:**

```json
{
  "currentModel": "glm-5.2",
  "status": "active",
  "lastTaskComplexity": "high"
}
```

## DELETE /agents/{id} — Remove Agent

Removes an agent from the registry.

## GET /alerts — Active Alerts

Returns surplus/pressure alerts.

**Response:**

```json
{
  "alerts": []
}
```

## GET /sync — Export Settings

Returns a sync code (base64-encoded settings) and server URL for cross-device
sync. **Requires auth.**

**Response:**

```json
{
  "sync_code": "eyJ2ZXJzaW9uIjo1LC...",
  "server_url": "http://192.168.1.100:8322"
}
```

## POST /sync — Import Settings

Applies a sync code from another instance. **Requires auth.**

**Request body:**

```json
{
  "sync_code": "eyJ2ZXJzaW9uIjo1LC..."
}
```

**Response:** `200 OK` on success, `400` on invalid code.

## POST /v1/usage — Record Usage

Universal usage ingestion endpoint. Agents and external tools report
per-run token usage here. **Requires auth.**

**Request body (snake_case):**

```json
{
  "source": "letta",
  "model": "glm-5.2",
  "timestamp": 1724131200000,
  "input_tokens": 1500,
  "output_tokens": 500,
  "request_count": 1
}
```

- `source` (required) — who reported the usage (e.g. "letta", "junie")
- `model` (required) — model handle that ran
- `timestamp` (optional) — epoch millis, defaults to now
- `input_tokens` / `output_tokens` (optional) — token counts, default 0
- `request_count` (optional) — number of requests, default 1

**Response:**

```json
{
  "status": "recorded",
  "source": "letta",
  "model": "glm-5.2"
}
```

## GET /v1/usage — Query Usage

Returns aggregated usage since a timestamp. **Requires auth.**

**Query params:** `?since=<epoch_ms>` (default: 24 hours ago)

**Response (snake_case):**

```json
{
  "since": 1724044800000,
  "by_source": [
    {
      "source": "letta",
      "input_tokens": 150000,
      "output_tokens": 50000,
      "request_count": 42
    }
  ],
  "by_model": [
    {
      "model": "glm-5.2",
      "input_tokens": 120000,
      "output_tokens": 40000,
      "request_count": 35
    }
  ]
}
```

## GET /dashboard — Full Dashboard State

Returns the complete dashboard display state (everything the UI shows,
no secrets). Used by mobile devices and remote dashboards.

**Response:** JSON snapshot of `DashboardSnapshot` — includes providers,
agents, metered usage, drain rates, waste detection, fuel events, and
advisor state. No auth required for this endpoint.

## GET /health — Health Check

Lightweight health check for uptime monitors. **No auth required.**

**Response:** `200 OK` with plain text body.

## POST /mcp — MCP Streamable HTTP

MCP (Model Context Protocol) server endpoint. Agents connect here for
self-registration, fuel queries, and provider management via the MCP
streamable HTTP transport.

## Wire Format Notes

- Most DTOs use **snake_case** via `@SerialName` annotations (e.g.
  `remaining_pct`, `burn_rate_pct_per_hr`, `provider_resets`)
- Agent fields use **camelCase** (e.g. `agentId`, `currentModel`,
  `lastTaskComplexity`, `fuelAllocation`) — this is intentional for
  ACP compatibility
- All timestamps are epoch milliseconds unless otherwise noted
- Error responses: `{"error": "message"}`
