# Provider Setup Guide

The Fuel Dashboard supports 9 provider types. Each has its own adapter that
queries the provider's API for fuel/quota status.

## Provider Summary

| Provider | Kind Enum | Limit Type | API Key Required | Default URL |
|----------|-----------|------------|------------------|-------------|
| z.ai | `ZAI` | Window Credit | Yes | `https://api.z.ai` |
| Letta Cloud | `LETTA_CLOUD` | Window Credit | Yes | `https://api.letta.com` |
| OpenAI | `OPENAI` | Spend Budget + Rate Limit | Yes | `https://api.openai.com` |
| Anthropic | `ANTHROPIC` | Spend Budget + Rate Limit | Yes | `https://api.anthropic.com` |
| DeepSeek | `DEEPSEEK` | Spend Budget | Yes | `https://api.deepseek.com` |
| Groq | `GROQ` | Rate Limit | Yes | `https://api.groq.com/openai` |
| Mistral AI | `MISTRAL` | Spend Budget + Rate Limit | Yes | `https://api.mistral.ai` |
| Junie | `JUNIE` | Spend Budget (manual) | No | — |
| Remote Dashboard | `CONNECTED_API` | — (agent backend) | No (URL required) | `http://127.0.0.1:8322` |

## Fuel Types Explained

The dashboard uses a fuel metaphor to categorize how providers track usage:

- **Window Credit (fuel tank):** Fixed quota that depletes and resets on a rolling/window basis. The dashboard shows a fill level and countdown to reset.
- **Spend Budget (budget):** Dollar-based spending tracked against a cap or credit balance. The dashboard shows money remaining.
- **Rate Limit (faucet):** Throughput throttle (requests/min, tokens/sec). The dashboard shows flow rate, not a depleting reserve.

## Provider Setup Details

### z.ai

z.ai uses a sliding window credit system. API calls consume tokens that replenish
over a 5-hour window.

**Setup:**

1. Get your API key from the z.ai dashboard.
2. Add a provider with kind `ZAI` and your API key.
3. The dashboard polls token usage and shows remaining percentage with window position.

**Override URL:** `https://api.z.ai` (default)

---

### Letta Cloud

Letta Cloud provides managed credits with a finite monthly budget. The dashboard
tracks remaining credits via the Letta API.

**Setup:**

1. Get your Letta Cloud API key.
2. Add a provider with kind `LETTA_CLOUD` and your API key.

**Override URL:** `https://api.letta.com` (default)

---

### OpenAI

OpenAI uses tiered spend limits plus per-model rate limits (TPM/RPM). The
dashboard tracks spend against your monthly budget.

**Setup:**

1. Get an **admin API key** from the OpenAI platform (standard keys cannot query usage).
2. Add a provider with kind `OPENAI` and your admin API key.
3. Optionally set `monthlyBudgetUsd` to track spending against a budget cap.

**Override URL:** `https://api.openai.com` (default)

!!! note "Admin Key Required"
    Usage endpoints require an admin-scoped key, not a standard project key.

---

### Anthropic

Anthropic uses tiered spend limits plus per-model RPM/ITPM/OTPM rate limits.
The dashboard tracks spend via the usage report API.

**Setup:**

1. Get an **admin API key** from the Anthropic console.
2. Add a provider with kind `ANTHROPIC` and your admin API key.
3. Optionally set `monthlyBudgetUsd`.

**Override URL:** `https://api.anthropic.com` (default)

---

### DeepSeek

DeepSeek uses a prepaid balance with concurrency limits. The dashboard queries
the balance endpoint for remaining credit.

**Setup:**

1. Get your DeepSeek API key.
2. Add a provider with kind `DEEPSEEK` and your API key.

**Override URL:** `https://api.deepseek.com` (default)

---

### Groq

Groq exposes rate limits (RPM, TPM, RPD, TPD) plus monthly spend limits. Rate
limit information is available in real-time via response headers.

**Setup:**

1. Get your Groq API key from the Groq console.
2. Add a provider with kind `GROQ` and your API key.

**Override URL:** `https://api.groq.com/openai` (default)

---

### Mistral AI

Mistral AI uses a monthly spend limit and rate limits (RPS, TPM). The dashboard
queries the admin API for usage and spend data.

**Setup:**

1. Get your Mistral admin API key.
2. Add a provider with kind `MISTRAL` and your admin API key.
3. Optionally set `monthlyBudgetUsd`.

**Override URL:** `https://api.mistral.ai` (default)

---

### Junie

Junie credits are checked manually from the UI. No API key is required for
configuration — the balance is fetched on demand.

**Setup:**

1. Add a provider with kind `JUNIE`.
2. No API key needed for configuration.
3. Use the "Check Balance" button in the dashboard to fetch the current Junie
   credit balance. The last-known balance is persisted and displayed.

---

### Remote Dashboard (Connected API)

Connects to another Fuel Dashboard instance. This enables monitoring a remote
fleet or chaining dashboards together.

**Setup:**

1. Add a provider with kind `CONNECTED_API`.
2. Set the server URL to the remote dashboard's address (e.g.,
   `http://192.168.1.100:8322`).
3. If the remote dashboard has auth enabled, provide its API key.

**Override URL:** `http://127.0.0.1:8322` (default — points to localhost)

!!! tip "Chaining Dashboards"
    You can connect multiple dashboards in a hierarchy. The remote dashboard's
    fuel state appears as a provider section in your local dashboard.

## Monthly Budget Support

Only these providers support `monthlyBudgetUsd` tracking:

- OpenAI
- Anthropic
- Mistral AI

For other providers, the budget field is ignored.
