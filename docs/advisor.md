# The Fuel Advisor

The Advisor (footer of the fuel status card) replaces an older "recommender" that had one idea: *switch everything to the cheapest model*. That was wrong in both directions — it recommended downgrades when quota was going to waste anyway, and it never considered whether the cheap model was smart enough for the work.

The Advisor v3 asks three questions, in order.

## 1. Should anything change at all? (quota regime)

From 7 days of gauge history, it counts **exhaustion events** — runs of snapshots at ≥95% quota usage (clustered, so one bad window counts once).

| Regime | Exhaustions | Behavior |
|--------|-------------|----------|
| **Surplus** | 0–1 of ~34 windows | Says "No action needed" explicitly. If the quota rarely runs out, downgrading anything is pointless — surplus burns whether you use it or not. *The smart model is effectively free.* |
| **Normal** | 2–3 | Healthy-window reporting; no action recommended. |
| **Persistent pressure** | ≥4 | Standing advice: move routine work to a cheaper model. |

## 2. Is this window at risk?

Projection: current level + (burn rate × hours until reset). If the projection hits 100% before the window slides, the window is **At Risk** — that's when switching matters *now*:

> ⚠ At risk: projected to exhaust before reset (~2h). Move routine work to glm-4.7:
> • PR Review Cron — 34→23 cr/day (33% off, 7d routine)

## 3. What can safely switch? (routine vs interactive)

A conversation is **routine** when it appears on ≥3 distinct days — cron jobs, scheduled checks, repeated pipelines. Only routine work is ever recommended for downgrade.

**Interactive sessions are never downgraded.** A one-off conversation with heavy tokens is doing real work; the smart model's intelligence is the point. The advisor will say "consider pausing heavy non-urgent runs" before it tells you to move your actual work to a dumber model.

## Worked examples

**Surplus:** *"No action needed. Projected 45% headroom at reset. Quota exhausted 1/34 windows — surplus regime, the smart model is effectively free right now."*

**Healthy:** *"Window healthy — ~30% projected headroom at reset. Exhaustions 2/33 windows."*

**At risk:** *"⚠ At risk: projected to exhaust before reset (~1.5h). Move routine work to glm-4.7: [top routine consumers with per-day savings]"*

**Persistent pressure:** *"Quota exhausted 6/34 windows — standing advice: route routine work to a cheaper model. [top routine consumers]"*

## Cost model

Switch projections use the published z.ai credit multipliers per model (input/cached/output). The cheapest *known* model is the downgrade target — currently glm-4.7. Models without published multipliers don't participate in switch math.
