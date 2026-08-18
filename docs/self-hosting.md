# Self-Hosting & API

## Runtime topology

The desktop app **is** the orchestrator: provider polling, usage ingestion, the decision/advisor engine, and an embedded API server all run in the desktop process. There is no separate server to deploy.

| Piece | Detail |
|-------|--------|
| Embedded server | Ktor, `http://0.0.0.0:8322` |
| Public URL | `https://fuel.angussoftware.dev` (Cloudflare tunnel) |
| Auth | Bearer API key (auto-generated, in Settings → Server; also used by mobile/MCP) |
| Database | SQLite `~/.fuel-dashboard/decisions.db` |
| Settings | Java prefs `~/.java/.userPrefs/fuel-dashboard/` |
| MCP | Streamable HTTP at `/mcp` (same Bearer auth) |

## HTTP API

All data endpoints require `Authorization: Bearer <api key>`. `/health` is open for uptime monitors.

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/health` | GET | liveness (no auth) |
| `/fuel` | GET | provider levels, burn rates, windows, alerts, recommendation |
| `/v1/usage?since=<ms>` | GET | usage aggregates since an epoch-ms cutoff |
| `/v1/usage` | POST | **self-report usage**: `{"source", "model", "input_tokens", "output_tokens", "request_count", "conversation_id", "timestamp"}` |
| `/agents` | GET | registered agents (ACP + MCP + synced) |
| `/agents/register` | POST | MCP-style agent self-registration |
| `/agents/{id}/state` | POST | agent state updates |
| `/agents/{id}` | DELETE | remove an agent |
| `/sync` | GET/POST | settings sync payload |
| `/alerts` | GET | alert list |
| `/decisions` | GET | decision history |
| `/mcp` | POST | MCP (Streamable HTTP) — tools: `register_agent`, `report_usage`, `get_usage` |

### Reporting usage from anything

```bash
curl -X POST http://127.0.0.1:8322/v1/usage \
  -H "Authorization: Bearer $FUEL_KEY" \
  -H "Content-Type: application/json" \
  -d '{"source":"my-tool","model":"local:qwen","input_tokens":8000,"output_tokens":400,"timestamp":'<epoch_ms>'}'
```

The schema aligns with OpenTelemetry GenAI semantic conventions (`source` ≈ service.name, `model` ≈ gen_ai.request.model) — bridging to OTel later is a trivial adapter.

## Database

`~/.fuel-dashboard/decisions.db` (SQLite). Key tables:

| Table | Contents |
|-------|----------|
| `usage_records` | the metered pool (all sources) |
| `fuel_snapshots` | gauge history (levels, active agents, reset times) |
| `conversation_titles` | conv-id → title map (summaries + fallbacks) |
| `agent_model_history` | agent → model over time (historical attribution) |
| `model_drain_rates` | gauge-correlated per-model consumption |
| `registered_agents` | MCP/self-registered agents |

Retention: usage records 90d, snapshots 7d (auto-cleanup).

## MCP integration

Add to any MCP client (e.g. Letta Code's global `mcpServers`):

```json
{
  "fuel-dashboard": {
    "url": "http://localhost:8322/mcp",
    "headers": { "Authorization": "Bearer <api key>" }
  }
}
```

## Cloudflare tunnel

The tunnel config (`agents-dashboard.yml`) maps `fuel.angussoftware.dev` → `localhost:8322`. The public URL serves the same API (Bearer-authenticated); `/health` is intentionally open for monitoring.
