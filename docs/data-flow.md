# Where Data Comes From

Every number in the dashboard has a receipt. This page is the receipt book.

## Fuel levels (the gauges)

| Data | Source | Cadence |
|------|--------|---------|
| z.ai TOKENS_PCT, reset time, per-model usage details | z.ai quota API | 30s poll |
| Letta credit balance / allowance | Letta Cloud API | 30s poll |
| Junie balance | local junie CLI check | manual / on-demand |

Every poll writes a **fuel snapshot** to SQLite (`fuel_snapshots`: timestamp, level, active agents/models, reset time). Snapshots power burn rates, projections, the sparkline, waste detection, and the advisor's regime analysis.

## Metered usage (the attribution)

The **Letta runs connector** pulls run-level records from the Letta API:

```
run → (agent, model, conversation, input_tokens, output_tokens, timestamp)
```

- Deduplicated across overlapping poll windows (run-key ledger)
- Historical runs attributed to the model that actually served them (agent→model history table tracks switches over time)
- Stored in `usage_records` — the single metered pool everything reads from

### Self-reported usage

Anything outside Letta reports its own usage into the same pool:

```bash
~/.letta/scripts/report_usage.sh --source NAME --model MODEL --input N --output N
```

- **Junie**: the `junie-auth` wrapper parses `--output-format json` task results and reports per-model usage automatically (source `junie`)
- **MCP**: the `report_usage` tool on the dashboard's MCP server
- **HTTP**: `POST /v1/usage` (see [Self-Hosting & API](self-hosting.md))

Attribution rides in the record itself (the `source` field) — no cross-referencing needed.

## Conversation titles

Conversations display human-readable titles instead of UUIDs. Title resolution is layered:

1. **Server-side summaries** — when the Letta API has a conversation summary, it's used
2. **Fallback labels** — `Agent · Date` (e.g. "Beacon · Aug 15") for summary-less conversations
3. **Backfill** — conversations with usage but no title get fetched **directly by ID** (the conversations *list* endpoint is unreliable for coverage — thousands of migration-era conversations bury recent ones beyond any page window)
4. **Display-time gap fill** — newly-active conversations missing titles trigger an immediate background fetch; the title appears within one poll (30s)

## Consumption attribution (gauge correlation)

`Model Consumption` correlates gauge drops with active models at poll time — correlational, not exact. Useful for drain-rate feel (`%/hr` per model); don't use it for accounting (use metered usage).

## Wasted quota

Per-provider: the fuel level at each quota-window expiry (window length from the provider's own metadata) — the quota that evaporated unused. Sampling the gauge at each window boundary yields adjacent, non-overlapping windows. See [Usage tab](tabs/usage.md#wasted-quota).

## The advisor

Reads `fuel_snapshots` (7d) for the quota regime and window projection, and `usage_records` (7d) for routine-work classification. Pure functions over that data — see [The Advisor](advisor.md).
