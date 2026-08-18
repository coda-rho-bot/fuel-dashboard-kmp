# Usage Tab

The "where did my fuel go?" view. Everything here is **metered** — exact token counts from real API records, not inferred from gauge movement.

## Metered Usage panel

Four breakdowns, each with a **24h / 7d** toggle:

| Breakdown | Answers |
|-----------|---------|
| **By source** | Which agent/runtime consumed tokens (Coda, Beacon, junie, …) |
| **By model** | Which model burned them — with z.ai credit cost per model |
| **By conversation** | Which specific conversations, with human-readable titles |
| **Agent × model** | The cross-tab: every agent paired with every model it actually ran — the honest answer to "what model does this agent use?" (all of them, per conversation) |

Credit costs use the published z.ai per-model multipliers. Models from other providers (e.g. `junie:*`) show tokens but no z.ai credit cost — their cost is accounted by their own provider tile.

## Model Consumption

Per-model fuel consumption **attributed from gauge drops**: when the z.ai gauge falls while a model is active, that drop is attributed to the model. Over days this builds a "glm-5.2 costs X%/hr when active" profile.

!!! note "Attribution vs metering"
    Metered Usage (above) is *exact* — per-run token counts. Model Consumption is *correlational* — gauge drops matched against active models. Use metered for "who/what"; use consumption for "%/hr drain feel".

## Waste Detection

Flags hourly windows where **the gauge dropped but metered usage shows nearly nothing** (≥1% consumed, <1K tokens metered). Unattributed drain means one of:

- **Idle/background consumption** the metering doesn't see (e.g. provider-side overhead)
- **Restart storms** — app restarts re-initialize every agent simultaneously (historically ~10× normal burn)
- **Metering lag** — usage records that arrive after the hour bucket they belong to (the detector tolerates ~10 minutes of lag; beyond that shows as unattributed)

A clean day shows the all-clear line explicitly. Persistent unattributed windows are worth investigating — that's fuel with no receipt.
