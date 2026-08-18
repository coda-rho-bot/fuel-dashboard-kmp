# Intel Tab

The "what happened?" timeline — a single merged, deduplicated list of significant fuel events, newest first.

## Event types

| Badge | Event | Fires when |
|-------|-------|-----------|
| `drop` | Fuel drop | Gauge falls ≥ the drop threshold (default **1%**), with drops within 10 minutes aggregated into one event. A restart storm is one entry, not forty. |
| `switch` | Model switch | An agent's configured model changes (e.g. `Beacon: glm-5.2 → glm-4.7`) |
| `rec` | Recommendation | The advisor's recommendation changes |

Each event records what was active when it happened — active agent count and models for drops.

## Tuning the sensitivity

**Settings → Intelligence → Fuel event drop threshold (%)**

- Lower (0.5–1) → fuller timeline, more noise
- Higher (3–5) → only significant drops

Changes apply on the next poll (30s). A quiet day with an empty timeline is *correct behavior* — it means no single poll-to-poll drop crossed the threshold.

## Why deduplication matters

Raw gauge data is noisy: the 5h sliding window rises as old usage expires and falls as new usage lands — hundreds of micro-movements per day. The timeline shows **events** (threshold-crossing drops, aggregated into bursts), not samples. The full sample history lives in the database (`fuel_snapshots`) and feeds the burn-rate calculations elsewhere in the app.
