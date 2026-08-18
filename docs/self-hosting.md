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
| `/dashboard` | GET | **complete display state** — everything the UI shows (providers, projection, advisor, all metered breakdowns, waste, events, agents), no secrets |
| `/v1/usage?since=<ms>` | GET | usage aggregates since an epoch-ms cutoff |
| `/v1/usage` | POST | **self-report usage**: `{"source", "model", "input_tokens", "output_tokens", "request_count", "conversation_id", "timestamp"}` |
| `/agents` | GET | registered agents (ACP + MCP + synced) |
| `/agents/register` | POST | MCP-style agent self-registration |
| `/agents/{id}/state` | POST | agent state updates |
| `/agents/{id}` | DELETE | remove an agent |
| `/sync` | GET/POST | settings sync payload |
| `/alerts` | GET | alert list |
| `/decisions` | GET | decision history |
| `/mcp` | POST | MCP (Streamable HTTP) — 15 tools: `get_dashboard` (full state), `get_waste`, `get_fuel_events`, `get_advice`, `get_usage`, `report_usage`, `register_agent`, `update_model`, `update_status`, `add_provider`, `remove_provider`, `list_providers`, `add_orchestrator`, `get_sync_data`, `apply_sync_data` |

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

## Programmatic access for agents

Every piece of information the UI displays is queryable by agents — designed for CI, cron jobs, and other agents (e.g. PR reviewers checking quota before running heavy work):

```bash
# Full state in one call (HTTP)
curl -s http://127.0.0.1:8322/dashboard -H "Authorization: Bearer $FUEL_KEY" | jq .advisor

# Or via MCP from any Letta agent: the fuel-dashboard MCP server's tools
# get_dashboard / get_waste / get_fuel_events / get_advice / get_usage
```

The snapshot covers: provider levels/resets/errors, fuel projection (burn rate, exhaustion, headroom), the advisor's full advice state (regime + routine consumers with savings), all metered usage breakdowns (source / model / conversation / agent×model, 24h + 7d), wasted quota per provider (daily rows, observed vs estimated), the fuel event timeline, model drain rates, agents with models-in-use, and ingestion status. Secrets (API keys) are excluded by construction.

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

## Data & privacy

- **Stays local:** all usage records, snapshots, titles, and settings (SQLite + Java prefs on the host)
- **Leaves the machine:** API keys are sent to their own providers when polling (z.ai key → z.ai, Letta key → Letta). No telemetry is sent anywhere else
- **Public surface:** if the Cloudflare tunnel is enabled, the API (including `/v1/usage` ingestion) is reachable at `fuel.angussoftware.dev` — protected by the Bearer key; treat that key like a password

## Cloudflare tunnel

The tunnel config (`agents-dashboard.yml`) maps `fuel.angussoftware.dev` → `localhost:8322`. The public URL serves the same API (Bearer-authenticated); `/health` is intentionally open for monitoring.
