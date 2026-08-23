# Fuel Dashboard

Cross-platform fuel monitoring for an AI agent fleet. The Fuel Dashboard answers three questions a fleet operator actually has:

1. **Am I OK?** — current quota levels, burn rates, and projections for every provider
2. **Where did my fuel go?** — exact, metered per-agent / per-model / per-conversation token usage
3. **What's been happening?** — a deduplicated timeline of fuel events: drops, model switches, recommendations

It runs as a Kotlin Multiplatform app (desktop + Android) with an embedded API server, and it is deliberately **honest**: every number is either metered from a real API or clearly labeled as an estimate. There are no fake recommendations and no decorative metrics.

## The 2-minute mental model

**Fuel** is whatever limits your agents: a z.ai 5-hour token window, Letta credits, a Junie balance, an OpenRouter budget. Each provider has a quota type:

| Type | Behavior | Examples |
|------|----------|----------|
| **Rate window** | Self-healing — hitting 0% means throttling until the window slides, not running out | z.ai 5h window, Letta daily |
| **Credit pool** | Finite budget that depletes and refills on a schedule | Letta monthly credits, Junie balance |

**Models and permissions are per conversation.** In Letta, an agent does not have "one model" — each conversation runs its own model and permission mode. The dashboard reflects this: agent cards show what conversations *actually ran* (metered), not a single config value.

**Usage data is metered, not guessed.** The dashboard pulls per-run token counts from the Letta runs API and accepts self-reported usage from any tool (Junie tasks, image generation, local models) via a universal reporting API.

## Quick tour

=== "Overview"
    The "am I OK?" glance: fuel status card (levels, burn rate, projection, sparkline, advisor advice), one tile per provider, last-update status.

=== "Usage"
    The "where did it go?" view: metered usage by source, model, conversation, and agent×model cross-tab (24h / 7d), per-model consumption attributed from gauge drops, and wasted-quota tracking (capacity that expired unused).

=== "Intel"
    The "what happened?" timeline: fuel drops (burst-aggregated), agent model switches, and recommendation changes — newest first, deduplicated.

=== "Agents"
    The fleet monitor: per-agent cards with live ACP status and the models their conversations actually ran in the last 24h.

## Where to go next

- **[Getting Started](getting-started.md)** — run the app, connect providers, enable usage metering
- **[API Reference](api-reference.md)** — embedded HTTP server endpoints and wire formats
- **[The Advisor](advisor.md)** — how the fuel advisor decides what (if anything) to recommend
- **[Where Data Comes From](data-flow.md)** — the source of every number you see
- **[Troubleshooting](troubleshooting.md)** — the real failure modes and their fixes
