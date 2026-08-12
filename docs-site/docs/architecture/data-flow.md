# Data Flow

How data moves through the Fuel Dashboard from provider APIs to the UI and
external clients.

## Polling Pipeline

The `FuelViewModel` drives a 30-second polling cycle:

```
POLLING CYCLE (every 30 seconds)
================================

FuelViewModel.triggerPoll()
    |
    v
For each configured provider:
    |
    v
ProviderAdapter.fetchFuelStatus()
(Ktor HTTP GET to provider API)
    |
    v
Parse response -> ProviderStateInfo
    fields: remainingPct, available, windowPosition, resetsAt
    |
    v
Assess provider (DecisionEngine.assessProvider)
    inputs: remainingPct, burnRate, windowPosition, resetsAt
    outputs: remaining, utilizationRatio, projectedRemaining
    |
    v
Aggregate all providers -> FuelResponse
    fields: providers map, burnRatePctPerHr, recommendedModel
    |
    +---> StateFlow (UI recomposes)
    |
    +---> EmbeddedServer.fuelState (served via HTTP/MCP)
    |
    +---> DecisionRepository (SQLite log)
```

## Provider Polling

Each provider adapter implements fuel status polling differently:

### Window-Credit Providers (z.ai, Letta Cloud)

These providers have a sliding window quota that depletes and replenishes:

```
Provider API  -->  remaining tokens/credits
                -->  window reset timestamp
                -->  window position (0.0 = just reset, 1.0 = about to reset)
```

The dashboard shows a fill-level bar and tracks the burn rate within the window.

### Spend-Budget Providers (OpenAI, Anthropic, DeepSeek, Mistral)

These providers track dollar spend against a budget cap:

```
Provider API  -->  spend this period ($)
                -->  budget limit ($)
                -->  remaining = budget - spend
```

The dashboard shows remaining budget as a percentage.

### Rate-Limit Providers (Groq)

These providers expose throughput throttles (requests/min, tokens/min):

```
Provider API response headers  -->  X-RateLimit-Remaining
                                  -->  X-RateLimit-Reset
                                  -->  current usage vs limits
```

The dashboard shows flow rate rather than a depleting reserve.

### Manual Check (Junie)

Junie credits require a manual check via the UI. The last-known balance is
persisted in settings and served alongside fuel state.

### Remote Dashboard (Connected API)

Connects to another Fuel Dashboard instance's HTTP API. This enables chaining
dashboards or monitoring a remote fleet:

```
Remote GET /fuel  -->  FuelResponse (same format as local)
                    -->  Displayed as a provider section
```

## Decision Flow

When the decision engine runs:

```
1. Input: task text, provider states, burn rate
   |
   v
2. estimateComplexity(text)
   -> (taskFloor: Complexity, upgradeBenefit: Double)
   |
   v
3. For each provider (sorted by priority):
   |
   +-> assessProvider()
   |     - compute remaining fuel
   |     - compute utilizationRatio = burnRate / optimalBurn
   |     - compute projectedRemaining
   |
   +-> Skip if projectedRemaining <= 0
   |
   +-> selectTier(taskFloor, utilizationRatio, benefit, windowPosition)
   |     -> may upgrade, keep, or downgrade the tier
   |
   +-> findModelForTier(provider, selectedTier)
   |     -> find exact match, or nearest adjacent tier
   |
   +-> If model found: RETURN decision
   |
4. Fallback: first model of first provider
```

## State Distribution

The dashboard maintains a single in-memory fuel state that feeds three consumers:

```
                    FuelViewModel
                    (in-memory state)
                         |
          +--------------+--------------+
          |              |              |
          v              v              v
     StateFlow      EmbeddedServer   DecisionRepo
     (UI)           .fuelState       (SQLite)
                         |
          +--------------+--------------+
          |              |              |
          v              v              v
     HTTP API        MCP Server     Mobile Clients
     GET /fuel       fuel://current (via LAN)
                     fuel://recommendation
```

## UI Update Cycle

```
StateFlow emits new FuelResponse
    |
    v
Compose detects state change
    |
    v
Recomposition:
    - Fuel bars animate to new percentages
    - Agent fleet panel updates
    - Decision log prepends new entry
    - Alerts panel checks threshold conditions
    - Countdown timers recalculate
    - Recommendation banner updates
```

The polling interval defaults to **30 seconds** but can be configured in Settings.
All UI updates are driven by Kotlin coroutines and Compose's reactive state system.
