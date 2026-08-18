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

## Wasted Quota

How much quota **expired unused** when each window closed. A window that ends with fuel still remaining wasted it — quota does not carry over:

- A 5h window passes with no usage → **100% wasted**
- A window slides with 10% remaining → **10% wasted**
- A window exhausted to 0% → **0% wasted** (you used everything you had)

Measurement follows each provider's quota mechanics: **sliding windows** (z.ai) are sampled every window-length — the API value at each boundary is exactly that window's trailing usage, so the tiling is exact; **fixed-reset windows** (Letta daily/4h, monthly pools) are measured at their *actual reset times* — the level just before each observed reset is the waste, avoiding the mid-window sampling error that would overcount. Daily rows show the average remaining-at-expiry across that day's windows, with an "hit 0% at least once" marker when any window ran dry.

High waste means capacity went unused (the advisor's surplus regime pairs with this: it's when downgrades are pointless — but also when extra smart-model work is effectively free).
A clean day shows the all-clear line explicitly. Persistent unattributed windows are worth investigating — that's fuel with no receipt.
