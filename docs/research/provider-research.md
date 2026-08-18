# LLM Provider API Research — Fuel Dashboard Adapters

> **Purpose:** Catalog the quota/usage/billing APIs exposed by each major LLM provider so we know which adapters to build and how.
>
> **Research date:** August 2025
>
> **Fuel metaphor:**
> - **Fuel tank (window-credit):** Fixed quota that depletes and resets on a rolling/window basis (e.g., tokens/min, requests/day). The dashboard shows a "fill level."
> - **Budget (spend):** Dollar-based spending tracked against a cap or credit balance. The dashboard shows money remaining.
> - **Faucet (rate-limit):** Throughput throttle — requests/min, tokens/sec. The dashboard shows flow rate, not a depleting reserve.

---

## Summary Table

| Provider | Quota Model | API Endpoint(s) | Auth | Fuel Type | Real-time? | Viable Adapter? |
|----------|-------------|-----------------|------|-----------|------------|-----------------|
| **OpenAI** | Tiered spend limits + per-model TPM/RPM rate limits | `GET /v1/organization/costs`, `GET /v1/organization/usage/completions` | `Authorization: Bearer $ADMIN_KEY` (admin key required) | Budget + Faucet | ~5 min delay | **Yes** |
| **Anthropic** | Tiered spend limits + per-model RPM/ITPM/OTPM rate limits | `GET /v1/organizations/usage_report/messages`, `GET /v1/organizations/cost_report` | `x-api-key: $ANTHROPIC_ADMIN_KEY` (admin key) | Budget + Faucet | ~5 min delay | **Yes** |
| **Google Vertex AI** | Quotas per region/model (TPM, RPM), spend-based tiers; billing via GCP | Cloud Monitoring API (time-series query) | OAuth2 / Service Account (GCP IAM) | Faucet + Budget | ~2.5 min delay (quota metrics) | **Partial** (complex GCP setup) |
| **Cohere** | Per-key rate limits (RPM), monthly call caps for trial keys | No usage API documented; rate limits in dashboard only | `Authorization: Bearer $API_KEY` | Faucet | N/A (no queryable API) | **No** |
| **Together AI** | Prepaid credits, dynamic per-model rate limits | Billing analytics in dashboard only; no usage API endpoint | `Authorization: Bearer $API_KEY` | Budget | Delayed (dashboard) | **Partial** (credit balance via dashboard scraping) |
| **Groq** | Rate limits (RPM, TPM, RPD, TPD) + monthly spend limits | Rate limit headers on every response; spend in dashboard | `Authorization: Bearer $GROQ_API_KEY` | Faucet + Budget | **Real-time** (headers) | **Yes** (header interception) |
| **Mistral AI** | Monthly spend limit, rate limits (RPS, TPM) | `GET /v1/admin/usage`, `GET /v1/admin/spend-limit`, `GET /v1/admin/rate-limit` | `x-api-key: $ADMIN_API_KEY` (or `Authorization: Bearer`) | Budget + Faucet | Near real-time | **Yes** |
| **Fireworks AI** | Prepaid credits, per-model rate limits | `GET /v1/accounts/{account_id}/billingUsage`, `GET /v1/accounts/{account_id}/billing/summary` | `Authorization: Bearer $API_KEY` | Budget | Near real-time | **Yes** |
| **Perplexity** | Prepaid credits, cumulative-spend usage tiers | No usage API; billing/usage in dashboard only | `Authorization: Bearer $API_KEY` | Budget | N/A (no queryable API) | **No** |
| **DeepSeek** | Prepaid balance, concurrency limits per model | `GET /user/balance` | `Authorization: Bearer $API_KEY` | Budget | **Real-time** | **Yes** (balance only) |
| **Replicate** | Pay-as-you-go (compute-time billing) or prepaid credit | No dedicated billing endpoint; `GET /v1/predictions` has per-prediction metrics | `Authorization: Bearer $REPLICATE_API_TOKEN` | Budget | Per-prediction (real-time) | **Partial** (aggregate from prediction list) |

---

## Detailed Findings by Provider

### 1. OpenAI

**Quota model:** Tiered usage limits based on cumulative spend. Each tier has RPM and TPM rate limits per model. Organization-level monthly spend limits can also be configured. Costs API reports dollar spend by day.

**Two complementary APIs:**

#### Completions Usage API
- **Endpoint:** `GET https://api.openai.com/v1/organization/usage/completions`
- **Auth:** `Authorization: Bearer $OPENAI_ADMIN_KEY` (must be an **admin key**, not a regular API key; requires `api.usage.read` scope)
- **Key params:** `start_time` (Unix seconds, required), `end_time`, `bucket_width` (`1m`/`1h`/`1d`), `group_by` (e.g., `["model"]`), `limit`, `page` (pagination cursor)
- **Response format:** Array of time buckets, each containing `results` with:
  ```json
  {
    "object": "bucket",
    "start_time": 1736616660,
    "end_time": 1736640000,
    "results": [{
      "object": "organization.usage.completions.result",
      "input_tokens": 141201,
      "output_tokens": 9756,
      "num_model_requests": 470,
      "input_cached_tokens": 0,
      "input_audio_tokens": 0,
      "output_audio_tokens": 0,
      "model": null,
      "project_id": null
    }]
  }
  ```

#### Costs API
- **Endpoint:** `GET https://api.openai.com/v1/organization/costs`
- **Auth:** Same admin key as above
- **Key params:** `start_time` (required), `bucket_width` (`1d` only), `limit`, `group_by`
- **Response format:** Array of daily buckets with cost amounts:
  ```json
  {
    "object": "bucket",
    "start_time": 1736553600,
    "end_time": 1736640000,
    "results": [{
      "object": "organization.costs.result",
      "amount": { "value": 0.13, "currency": "usd" },
      "line_item": null,
      "organization_id": "org-..."
    }]
  }
  ```

#### Rate limit headers (on every inference response)
| Header | Description |
|--------|-------------|
| `x-ratelimit-limit-requests` | Max requests per period |
| `x-ratelimit-remaining-requests` | Remaining requests |
| `x-ratelimit-reset-requests` | Time until request limit resets |
| `x-ratelimit-limit-tokens` | Max tokens per period |
| `x-ratelimit-remaining-tokens` | Remaining tokens |
| `x-ratelimit-reset-tokens` | Time until token limit resets |
| `x-ratelimit-limit-project-tokens` | Project-scoped token limit (if applicable) |
| `x-ratelimit-remaining-project-tokens` | Project-scoped remaining tokens |
| `Retry-After` | Seconds to wait (on 429 only) |

**Fuel type:** Budget (costs API) + Faucet (rate limit headers)
**Real-time:** Usage/cost data ~5 min delay. Rate limit headers are real-time per-request.
**Data freshness:** Docs say "typically appears within 5 minutes."

**Adapter approach:**
- Primary: Poll `/v1/organization/costs` daily for spend tracking
- Secondary: Poll `/v1/organization/usage/completions` with `bucket_width=1h` for near-real-time token usage
- Optional: Intercept rate limit headers from proxied inference requests for real-time faucet gauge
- Requires admin key provisioning — document this as a setup prerequisite

---

### 2. Anthropic

**Quota model:** Tiered (Start/Build/Scale) with per-model RPM, ITPM (input tokens/min), OTPM (output tokens/min) rate limits. Each tier has a monthly spend cap. Workspaces can have sub-limits.

**Two complementary APIs (Usage & Cost Admin API):**

#### Usage API
- **Endpoint:** `GET https://api.anthropic.com/v1/organizations/usage_report/messages`
- **Auth:** `x-api-key: $ANTHROPIC_ADMIN_KEY` (admin key starting with `sk-ant-admin...`) + `anthropic-version: 2023-06-01` header
- **Key params:** `starting_at` (RFC 3339), `ending_at`, `bucket_width` (`1m`/`1h`/`1d`), `group_by[]` (model, workspace_id, service_tier, api_key_id, inference_geo, speed), filters: `models[]`, `service_tiers[]`, `api_key_ids[]`, `workspace_ids[]`, `context_window[]`
- **Response format:** Paginated buckets with token breakdowns:
  ```json
  {
    "data": [{
      "starting_at": "2025-01-01T00:00:00Z",
      "ending_at": "2025-01-02T00:00:00Z",
      "results": [{
        "model": "claude-sonnet-5",
        "input_tokens": 410,
        "output_tokens": 585,
        "cache_read_input_tokens": 0,
        "cache_creation_input_tokens": 0,
        "service_tier": "standard"
      }]
    }],
    "has_more": false,
    "next_page": null
  }
  ```
- **Granularity limits:** `1m` max 1,440 buckets; `1h` max 168 buckets; `1d` max 31 buckets

#### Cost API
- **Endpoint:** `GET https://api.anthropic.com/v1/organizations/cost_report`
- **Auth:** Same admin key
- **Key params:** `starting_at`, `ending_at`, `group_by[]` (workspace_id, description)
- **Response format:** Daily buckets with costs in cents (decimal string):
  ```json
  {
    "data": [{
      "starting_at": "2025-01-01T00:00:00Z",
      "ending_at": "2025-01-02T00:00:00Z",
      "results": [{
        "amount": "123.45",
        "currency": "USD",
        "model": "claude-sonnet-5",
        "cost_type": "tokens",
        "token_type": "uncached_input_tokens",
        "workspace_id": null,
        "service_tier": "standard"
      }]
    }],
    "has_more": false
  }
  ```
- **Note:** Amount is in **cents** as a decimal string (`"123.45"` = $1.23). Daily granularity only.

#### Rate limit headers (on every inference response)
| Header | Description |
|--------|-------------|
| `anthropic-ratelimit-requests-limit` | Max requests per period |
| `anthropic-ratelimit-requests-remaining` | Remaining requests |
| `anthropic-ratelimit-requests-reset` | Reset time (RFC 3339) |
| `anthropic-ratelimit-tokens-limit` | Max tokens per period |
| `anthropic-ratelimit-tokens-remaining` | Remaining tokens (rounded to nearest 1000) |
| `anthropic-ratelimit-tokens-reset` | Token limit reset time (RFC 3339) |
| `anthropic-ratelimit-input-tokens-limit/remaining/reset` | Input token-specific limits |
| `anthropic-ratelimit-output-tokens-limit/remaining/reset` | Output token-specific limits |

**Fuel type:** Budget (cost API) + Faucet (rate limit headers)
**Real-time:** Usage/cost data ~5 min delay. Rate limit headers are real-time per-request.
**Data freshness:** "Typically appears within 5 minutes of API request completion."

**Adapter approach:**
- Primary: Poll `/v1/organizations/cost_report` daily for spend tracking
- Secondary: Poll `/v1/organizations/usage_report/messages` with `bucket_width=1h` for near-real-time token usage
- Optional: Intercept rate limit headers from proxied inference requests for real-time faucet gauge
- Note: Requires admin key (`sk-ant-admin...`) — different from standard API key

---

### 3. Google Vertex AI / Gemini

**Quota model:** Complex multi-layered system:
- **Per-model, per-region quotas:** TPM (tokens/min), RPM (requests/min) — enforced per project, per region
- **Standard PayGo tiers:** Spend-based rolling 30-day window determines baseline throughput tier (e.g., Gemini Pro: Tier 1 $10–$250 = 500K TPM, Tier 2 = 1M TPM, Tier 3 = 2M TPM)
- **Dynamic Shared Quota (DSQ):** 429 errors indicate temporary contention, not hard quota
- **Billing:** Integrated with Google Cloud billing (Cloud Billing API)

**No dedicated "usage endpoint" — uses Cloud Monitoring API:**

#### Cloud Monitoring API (for quota/usage metrics)
- **Endpoint:** `POST https://monitoring.googleapis.com/v3/projects/{project}/timeSeries:query` (or `:list`)
- **Auth:** OAuth2 access token or service account key (GCP IAM)
- **Key metrics (prefix `aiplatform.googleapis.com/`):**
  - `global_online_prediction_input_tokens_per_minute_per_base_model`
  - `global_online_prediction_output_tokens_per_minute_per_base_model`
  - `online_prediction_requests_per_base_model`
  - `quota/rate/net_usage` (consumer quota usage via `serviceruntime.googleapis.com`)
  - `publisher/online_serving/tokens` (token count)
- **Filtering:** By `metric.labels.base_model`, `resource.labels.location`, etc.
- **Data delay:** Up to ~150 seconds for quota metrics (after sampling)

#### Billing (Cloud Billing API)
- Standard GCP Cloud Billing for cost data
- Budgets and alerts via Cloud Billing Budget API

**Fuel type:** Faucet (quota metrics) + Budget (Cloud Billing)
**Real-time:** Delayed (~2.5 min for quota metrics). Billing data is delayed further.
**Complexity:** High — requires GCP project setup, IAM roles, and familiarity with Cloud Monitoring query language.

**Adapter approach:**
- This is the most complex adapter. Requires GCP service account credentials.
- Query Cloud Monitoring for `aiplatform.googleapis.com/global_online_prediction_*_tokens_per_minute_per_base_model` to build a faucet gauge
- Query Cloud Billing API for cost data
- Consider whether to support this initially or defer to Phase 2
- Alternative: Use the GCP REST API for quota management (`services.googleapis.com` consumer quota endpoints)

---

### 4. Cohere

**Quota model:**
- **Trial keys:** 1,000 API calls/month, per-endpoint RPM limits (e.g., Chat: 20 RPM, Embed: 2,000 inputs/min)
- **Production keys:** Higher RPM (Chat: 500 RPM), pay-as-you-go per-token billing, monthly bills or at $250 balance threshold
- **Dashboard:** Spending limits can be set in the Dashboard

**No queryable usage/billing API.**
- All usage/billing data is visible only in the Cohere Dashboard (web UI)
- API responses include per-request token counts (`billed_units` in response body), but no aggregate endpoint exists
- Rate limits are documented as fixed values per endpoint, not returned as headers

**Fuel type:** Faucet (rate limits) — but not queryable
**Real-time:** N/A — no API to query

**Adapter approach:**
- **Not viable** for a programmatic adapter without dashboard scraping
- Could aggregate usage from per-response `billed_units` if proxying all requests
- Recommend: Skip for v1, revisit if Cohere adds a billing API

---

### 5. Together AI

**Quota model:**
- **Prepaid credits:** Minimum $5 purchase, no expiration on credits, balance must stay positive
- **Dynamic rate limits:** Per-model limits that scale with sustained usage. No fixed published limits.
- **Auto-recharge:** Configurable threshold-based auto top-up

**No queryable usage/billing REST API.**
- Billing analytics are available only in the web dashboard (cost analytics page)
- Rate limit info comes from response headers on inference requests

#### Rate limit headers (on 429 responses only)
- `x-ratelimit-reset`: Seconds to wait before retrying (only present on `429` responses)
- Successful responses include **no** rate-limit headers — only 429s carry `x-ratelimit-reset`

**Fuel type:** Budget (prepaid credits) + Faucet (dynamic rate limits)
**Real-time:** Rate limit info only on 429. Credit balance via dashboard.

**Adapter approach:**
- **Partial:** No usage/billing API endpoint to query for credit balance or spend
- Rate limit data only surfaces on rejection (429), not proactively
- Could intercept 429 headers if proxying inference traffic
- Credit balance requires dashboard scraping (fragile, not recommended)
- Recommend: Low priority for v1

---

### 6. Groq

**Quota model:**
- **Rate limits:** Per-model RPM, RPD (requests/day), TPM, TPD (tokens/day), audio seconds per hour/day. Organization-level enforcement.
- **Spend limits:** Monthly USD budget cap (paid plans only), auto-blocks at limit
- **Service tiers:** `performance` (enterprise), `on_demand` (default), `flex` (best-effort), `auto`

**Excellent rate limit header support (real-time, on every response):**

#### Rate limit headers (on every response)
| Header | Value | Notes |
|--------|-------|-------|
| `x-ratelimit-limit-requests` | 14400 | Always RPD (requests/day) |
| `x-ratelimit-limit-tokens` | 18000 | Always TPM (tokens/min) |
| `x-ratelimit-remaining-requests` | 14370 | Remaining RPD |
| `x-ratelimit-remaining-tokens` | 17997 | Remaining TPM |
| `x-ratelimit-reset-requests` | 2m59.56s | Time to RPD reset |
| `x-ratelimit-reset-tokens` | 7.66s | Time to TPM reset |
| `retry-after` | 2 | Seconds (on 429 only) |

#### Spend tracking
- Spend limits tracked in dashboard with **10–15 min delay**
- Limits page shows current spend vs. limit
- No dedicated usage API endpoint; rate limit data comes from headers

**Fuel type:** Faucet (rate limits via headers) + Budget (spend limits in dashboard)
**Real-time:** **Rate limit headers are real-time on every request.** Spend tracking has 10–15 min delay.

**Adapter approach:**
- **Best faucet candidate** — headers are always present, real-time, and cover both daily and per-minute windows
- Intercept response headers from proxied inference requests
- Build dual gauge: TPM faucet (fills/drains per minute) + RPD tank (depletes over 24h)
- For spend tracking: no API available; users must check dashboard manually or skip

---

### 7. Mistral AI

**Quota model:**
- **Monthly spend limit:** Organization-level USD cap, configurable via API
- **Rate limits:** Requests per second (RPS) + per-model token limits (TPM, tokens/month)
- **Billing:** Pay-as-you-go, credits, monthly billing

**Excellent Admin API with dedicated billing endpoints:**

#### Get Usage
- **Endpoint:** `GET https://api.mistral.ai/v1/admin/usage`
- **Auth:** `Authorization: Bearer $ADMIN_API_KEY` (or `x-api-key`)
- **Key params:** `month`, `year` (whole numbers, optional — defaults to current), `workspace_id`, `api_zone` (`global`/`us`/`eu`)
- **Response format:** Usage broken down by category with costs:
  ```json
  {
    "chat": { "models": [...] },
    "completion": { "models": [...] },
    "ocr": { "models": [...] },
    "audio": { "models": [...] },
    "connectors": { "models": [...] },
    "fine_tuning": { "training": [...], "storage": [...] },
    "currency": "USD",
    "currency_symbol": "$",
    "start_date": "2025-08-01T00:00:00Z",
    "end_date": "2025-08-31T23:59:59Z",
    "prices": [...]
  }
  ```

#### Get Spend Limits
- **Endpoint:** `GET https://api.mistral.ai/v1/admin/spend-limit`
- **Auth:** Same admin key
- **Response format:**
  ```json
  {
    "limits": {
      "completion": { "monthly_limit_reached": false },
      "currency": "USD",
      "last_payment_failure": false,
      "last_payment_failure_protection": null
    }
  }
  ```

#### Get Rate Limits
- **Endpoint:** `GET https://api.mistral.ai/v1/admin/rate-limit`
- **Auth:** Same admin key
- **Response format:**
  ```json
  {
    "requests_per_second": 87,
    "tokens_limits_by_model": [{
      "tokens_per_minute": 14,
      "tokens_per_month": 56
    }]
  }
  ```

#### Update Spend Limits
- **Endpoint:** `POST https://api.mistral.ai/v1/admin/spend-limit`
- **Body:** `{ "amount": 500, "no_monthly_limit": false }`

**Fuel type:** Budget (usage + spend-limit) + Faucet (rate-limit)
**Real-time:** Near real-time (current month data available immediately via API)

**Adapter approach:**
- **Full-featured adapter** — query all three endpoints
- `GET /v1/admin/usage` for spend breakdown by category/model
- `GET /v1/admin/spend-limit` for budget gauge (amount used vs. monthly cap)
- `GET /v1/admin/rate-limit` for faucet configuration (RPS, TPM per model)
- Poll monthly usage endpoint for budget tracking; rate-limit endpoint for faucet display
- Requires admin API key from Mistral Backoffice

---

### 8. Fireworks AI

**Quota model:**
- **Prepaid credits:** Purchase credits, usage deducts from balance. Auto top-up and monthly budget cap available.
- **Per-model usage:** Serverless (tokens), dedicated deployments (GPU time), training (tokens/GPU-seconds)
- **Post-paid option:** Available for contracted enterprise customers

**Multiple well-documented billing API endpoints:**

#### Get Billing Usage (metered quantities)
- **Endpoint:** `GET https://api.fireworks.ai/v1/accounts/{account_id}/billingUsage`
- **Auth:** `Authorization: Bearer $API_KEY`
- **Key params:** `startTime` (date-time, required), `endTime`, `usageType` (`SERVERLESS`/`DEDICATED_DEPLOYMENT`/`TRAINING`), `groupBy` (model_name, api_key_id, deployment_name, etc.), `timezone`
- **Response format:** Usage buckets with metered quantities (tokens, accelerator-seconds), no dollar amounts

#### Get Billing Summary (rated costs)
- **Endpoint:** `GET https://api.fireworks.ai/v1/accounts/{account_id}/billing/summary`
- **Auth:** Same
- **Key params:** `startTime`, `endTime` (both required), `granularity` (`DAILY`/`UNSPECIFIED`)
- **Response format:** Rated dollar line items by billing category (serverless, dedicated, training), with per-day buckets when `granularity=DAILY`

#### Query Usage Costs (granular costs by dimension)
- **Endpoint:** `POST https://api.fireworks.ai/v1/accounts/{account_id}/usageCosts:query`
- **Auth:** Same (requires account administrator for `ACCOUNT` scope)
- **Body:** JSON with filter dimensions (`HOUR`/`DAY`/`MODEL`/`USER`/`API_KEY`), pagination
- **Response:** Dollar subtotals grouped by caller-supplied dimensions, with account-wide `subtotal`

**Fuel type:** Budget (prepaid credits, billing usage/costs)
**Real-time:** Near real-time

**Adapter approach:**
- Query `GET /billingUsage` for token/quantity consumption
- Query `GET /billing/summary` for dollar spend
- Use `POST /usageCosts:query` for granular per-model/per-key cost breakdowns
- Credit balance: Not exposed via API directly (would need dashboard for remaining balance); but spend rate is queryable
- Requires `account_id` in the path — obtainable from Fireworks dashboard

---

### 9. Perplexity

**Quota model:**
- **Prepaid credits:** Pay-as-you-go, auto top-up when balance < $2
- **Cumulative spend tiers:** Tier 0 ($0) → Tier 5 ($5,000+), permanent once reached, each tier unlocks higher RPM
- **Rate limits:** Leaky bucket algorithm for Search API (50 query units/sec); tier-based RPM for Chat API

**No queryable usage/billing API.**
- All billing/usage data visible only in the web Dashboard (Billing page)
- Credit balance, usage chart, and billing breakdown are dashboard-only
- No documented REST endpoints for usage or billing queries

**Fuel type:** Budget (prepaid credits)
**Real-time:** N/A — no API to query

**Adapter approach:**
- **Not viable** for a programmatic adapter
- Credit balance and usage require dashboard scraping (fragile)
- Recommend: Skip for v1 unless Perplexity adds a billing API

---

### 10. DeepSeek

**Quota model:**
- **Prepaid balance:** Top-up balance + granted balance (free credits), preference for granted balance first
- **Concurrency limits:** Per-model concurrent request caps (deepseek-v4-pro: 500, deepseek-v4-flash: 2500), account-level enforcement
- **Peak/off-peak pricing:** Coming soon — 2x prices during Beijing peak hours (09:00–12:00, 14:00–18:00 UTC+8)

**Simple but effective balance API:**

#### Get User Balance
- **Endpoint:** `GET https://api.deepseek.com/user/balance`
- **Auth:** `Authorization: Bearer $API_KEY`
- **Response format:**
  ```json
  {
    "is_available": true,
    "balance_infos": [{
      "currency": "USD",
      "total_balance": "10.05",
      "granted_balance": "10.00",
      "topped_up_balance": "0.05"
    }]
  }
  ```
- **Key fields:** `is_available` (boolean — sufficient balance for calls), `total_balance`, `granted_balance` (free credits, may expire), `topped_up_balance` (paid)
- **Currencies:** `USD` or `CNY`

**Concurrency tracking:** No API to query current concurrency. Only enforced via 429 on excess.
**Rate limits:** No rate limit headers documented. Only concurrency limits.

**Fuel type:** Budget (prepaid balance)
**Real-time:** **Yes** — balance endpoint returns current available balance

**Adapter approach:**
- Simplest budget adapter — poll `GET /user/balance` for credit balance
- Display total balance, granted vs. topped-up breakdown
- `is_available` boolean serves as a health indicator
- No faucet/rate-limit data available programmatically
- Good candidate for a basic "fuel tank" gauge

---

### 11. Replicate

**Quota model:**
- **Pay-as-you-go:** Billed for compute time (per-second by hardware type) or per input/output tokens, depending on model
- **Prepaid credit option:** Purchase credits, usage deducts from balance
- **Billing in arrears:** Monthly invoicing for previous month's usage (or early charge if usage crosses fraud-detection thresholds)
- **Prediction metrics:** Each prediction returns `predict_time` (GPU seconds) and `total_time`

**No dedicated billing/usage REST endpoint.** Cost data is available through prediction objects:

#### List Predictions (with per-prediction cost metrics)
- **Endpoint:** `GET https://api.replicate.com/v1/predictions`
- **Auth:** `Authorization: Bearer $REPLICATE_API_TOKEN`
- **Pagination:** 100 records per page, cursor-based
- **Per-prediction metrics:**
  ```json
  {
    "id": "...",
    "status": "succeeded",
    "metrics": {
      "predict_time": 3.42,
      "total_time": 8.15
    }
  }
  ```
- Cost = `predict_time` × hardware rate (varies by model, from $0.000025/sec CPU to $0.002800/sec 2×A100)

**Fuel type:** Budget (compute-time billing)
**Real-time:** Per-prediction (real-time as predictions complete)

**Adapter approach:**
- **Partial:** No aggregate billing endpoint — must iterate predictions and compute costs
- Poll `GET /v1/predictions` (paginated) to list recent predictions
- Extract `metrics.predict_time` and multiply by per-model hardware rate
- Requires maintaining a model→hardware→rate mapping table
- Credit balance: Not exposed via API (dashboard only)
- Recommend: Low priority for v1 due to aggregation complexity

---

## Adapter Priority Recommendations

### Phase 1 — High Value, Clean APIs
| Provider | Why | Data Available |
|----------|-----|----------------|
| **OpenAI** | Most users, rich dual API (costs + usage) | Spend ($), token usage, rate limit headers |
| **Anthropic** | Rich Admin API, well-documented | Spend ($), token usage, rate limit headers |
| **Mistral AI** | Best-in-class billing API (3 endpoints) | Spend ($), rate limits, spend limits |
| **DeepSeek** | Simple, real-time balance endpoint | Credit balance ($) |
| **Groq** | Best rate-limit header support | Real-time RPM/TPM/RPD/TPD |

### Phase 2 — Viable but Complex
| Provider | Why | Challenge |
|----------|-----|-----------|
| **Fireworks AI** | Good billing API (3 endpoints) | Needs account_id, response format complex |
| **Google Vertex AI** | Large user base | Requires GCP IAM, Cloud Monitoring query complexity |
| **Replicate** | Unique compute-time model | Must aggregate from prediction list |

### Phase 3 — Not Currently Viable (No Queryable API)
| Provider | Blocker |
|----------|---------|
| **Cohere** | No usage/billing API; dashboard only |
| **Together AI** | No billing API endpoint; dashboard only |
| **Perplexity** | No usage/billing API; dashboard only |

For Phase 3 providers, the only option is intercepting per-request data from a proxy layer (if we route their traffic through our system). This is architecturally different from polling an API and should be considered a separate workstream.

---

## Cross-Cutting Notes

### Auth Key Types
Several providers require a **different key type** for billing/usage APIs vs. inference:
- **OpenAI:** Admin key (not API key) with `api.usage.read` scope
- **Anthropic:** Admin key (`sk-ant-admin...`, not standard `sk-ant-api...`)
- **Mistral:** Admin API key (from Backoffice, not standard API key)
- **Fireworks:** Account administrator access for cost endpoints

The dashboard must guide users through provisioning the correct key type per provider.

### Data Delay Summary
| Provider | Delay |
|----------|-------|
| OpenAI | ~5 min |
| Anthropic | ~5 min |
| Mistral | Near real-time |
| DeepSeek | Real-time |
| Groq (headers) | Real-time |
| Groq (spend) | 10–15 min |
| Fireworks | Near real-time |
| Google Vertex AI | ~2.5 min (quota metrics) |

### Response Header Interception Strategy
For real-time faucet gauges, several providers expose rich rate-limit headers on every inference response. If the fuel dashboard includes or sits behind a request proxy, these headers can be intercepted to provide real-time rate-limit utilization without polling:
- **OpenAI:** `x-ratelimit-*` headers (tokens + requests, limit + remaining + reset)
- **Anthropic:** `anthropic-ratelimit-*` headers (requests, tokens, input tokens, output tokens)
- **Groq:** `x-ratelimit-*` headers (RPM/TPM/RPD/TPD, always present)

This is an architectural decision: if the dashboard proxies inference traffic, header interception gives us the best real-time data. If it's a standalone polling dashboard, we're limited to the usage/cost APIs (which have inherent delays).
