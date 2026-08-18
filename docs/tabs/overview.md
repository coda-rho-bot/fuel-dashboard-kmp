# Overview Tab

The "am I OK?" glance. Everything here answers: *how much fuel is left, how fast is it burning, and will it last until reset?*

## Fuel Status Card

The primary card, field by field:

| Field | Meaning |
|-------|---------|
| **Fuel %** | Current level of the primary rate-window provider (z.ai 5h window). 100% = full window available. |
| **Burn rate** | Average gauge drop per hour over the recent span (from real deltas, not estimates). |
| **Projected exhaustion** | When the current level + burn rate hits 0%. |
| **Headroom at reset** | Projected level when the window slides. Positive headroom = healthy. |
| **Sparkline** | Level history over the last hour — see the sawtooth as the window slides. |
| **Active agents / models** | What was live at the last poll. |

!!! tip "Rate windows self-heal"
    A z.ai window hitting 0% isn't a catastrophe — it means throttling until old usage slides out of the 5h window. The card's warning styling means "uncomfortable soon", not "dead".

### Advisor section

The card's footer is the [Fuel Advisor](../advisor.md) — one line of regime-aware advice. When things are fine it says so explicitly ("No action needed — surplus regime"), and when action helps it names *which routine work* to move and what it saves. It never recommends downgrading interactive sessions.

## Provider tiles

One tile per configured provider. Each shows its quota in the shape that matches its type:

- **Rate windows** (z.ai, Letta daily): fuel bar + countdown to reset
- **Credit pools** (Letta monthly, Junie): budget bar + refill schedule
- **Rate limits**: compact reset bars

Per-model consumption attributed from gauge drops lives in the [Usage tab](usage.md).

## Status row

- **Last updated** — provider data polls every 30 seconds
- **⚠ N errors** — count of failing providers; open Settings to see details
