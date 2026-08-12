# Decision Engine

The decision engine selects the optimal model for a given task by evaluating
each provider's fuel state and applying tier upgrades or downgrades based on
**utilization ratio** — the ratio of actual burn rate to the optimal burn rate.

## Complexity Tiers

Every task is classified into one of four complexity tiers, from cheapest to
most expensive:

| Tier | Typical Use Case |
|------|-----------------|
| `TRIVIAL` | Short responses, simple lookups |
| `LIGHT` | Brief code snippets, simple questions |
| `MEDIUM` | General coding tasks, moderate-length messages |
| `HEAVY` | Architecture, refactoring, debugging, multi-file changes |

## Task Complexity Estimation

The `estimateComplexity()` function analyzes incoming message text to determine
the minimum tier required and the "upgrade benefit" (how much a tier upgrade
would help):

```
Text analysis signals:
  - Code blocks with language tags (```kotlin, ```python) → HEAVY
  - Long code blocks (>500 chars) → HEAVY
  - Heavy keywords (review, refactor, architect, debug, implement, etc.) → HEAVY/MEDIUM
  - Code blocks without language tags → MEDIUM
  - Very short messages (<100 chars) → TRIVIAL
  - Short messages (<300 chars) → LIGHT
  - Default → MEDIUM
```

The function returns a pair: `(minimum_tier, upgrade_benefit)` where
`upgrade_benefit` ranges from 0.0 (no benefit) to 1.0 (high benefit).

## Utilization Ratio

The core metric driving tier selection. For each provider, the engine computes:

```
optimal_burn = remaining_fuel_pct / hours_until_reset
utilization_ratio = actual_burn_rate / optimal_burn
```

- **ratio < 1.0** — burning slower than optimal; fuel will be wasted (surplus)
- **ratio = 1.0** — burning at exactly the right pace
- **ratio > 1.0** — burning faster than optimal; will run out before reset

### Projection

The engine also projects how much fuel will remain when the window resets:

```
projected_remaining = remaining_pct - (burn_rate × hours_until_reset)
```

If `projected_remaining <= 0`, the provider is **skipped** (not viable).

## Tier Selection Algorithm

The `selectTier()` function takes four inputs:

1. **`floor`** — minimum complexity tier required by the task
2. **`utilizationRatio`** — current burn efficiency (or null if unknown)
3. **`benefit`** — upgrade benefit score (0.0–1.0)
4. **`windowPosition`** — position in the reset window (0.0 = just reset, 1.0 = about to reset)

### Window Strategy

First, the window position determines a high-level strategy:

| Window Position | Strategy | Behavior |
|----------------|----------|----------|
| < 0.2 | **Aggressive** | Just reset — spend freely. Upgrade if benefit ≥ 0.3 |
| 0.2 – 0.9 | **Balanced** | Normal operation — use utilization ratio thresholds |
| > 0.9 | **Spend-down** | Window almost over — burn remaining fuel. Upgrade if benefit ≥ 0.3 |

### Balanced Strategy Thresholds

During balanced operation (the common case), tier adjustments depend on the
utilization ratio:

| Utilization Ratio | Action | Condition |
|-------------------|--------|-----------|
| < 0.5 | **Upgrade +1** if benefit ≥ 0.3 | Lots of surplus fuel |
| 0.5 – 0.8 | **Upgrade +1** if benefit ≥ 0.5 | Moderate surplus |
| 0.8 – 1.2 | **Keep floor** | Burning at optimal rate |
| 1.2 – 1.5 | **Downgrade -1** | Burning slightly too fast |
| > 1.5 | **Downgrade -2** | Burning much too fast — conserve |

## Provider Selection

The `decideModel()` function iterates providers **sorted by priority** (lower
number = higher priority). For each provider:

1. **Check availability** — skip if the provider is marked unavailable
2. **Assess** — compute remaining fuel, utilization ratio, projected remaining
3. **Skip if depleted** — if `projected_remaining <= 0`, move to next provider
4. **Select tier** — apply the tier selection algorithm
5. **Find model** — locate a model in the provider's catalog matching the selected tier

If no provider has a model at the exact target tier, `findModelForTier()` searches
adjacent tiers (±1, ±2, ±3) before falling back to any available model.

## Reasoning Effort

The engine also selects a **reasoning effort** level alongside the tier:

| Condition | Reasoning Effort |
|-----------|-----------------|
| Utilization > 1.2 + HEAVY tier | `medium` |
| Utilization > 1.2 + other tiers | `low` |
| HEAVY tier (normal) | `high` |
| MEDIUM tier (normal) | `medium` |
| LIGHT/TRIVIAL tier | `low` |

## Decision Record

Every decision is logged with:

- Agent ID
- Model handle selected
- Provider name
- Tier (actual selected tier)
- Complexity (task floor)
- Utilization ratio
- Headroom (100 − projected remaining %)
- Human-readable reason string
- Timestamp

These records are persisted to SQLite and served via `GET /decisions`.

## Example Decision

```json
{
  "handle": "anthropic/claude-sonnet-5",
  "provider": "Anthropic",
  "tier": "MEDIUM",
  "reasoningEffort": "medium",
  "reason": "medium (task floor), ratio 0.72, 85% remaining",
  "utilizationRatio": 0.72,
  "headroom": 15,
  "projectedRemaining": 71.0
}
```

This decision means: the task required MEDIUM complexity, the utilization ratio
was 0.72 (burning slightly under optimal), and 85% fuel remained. The engine
kept the floor tier since the benefit wasn't high enough to upgrade.
