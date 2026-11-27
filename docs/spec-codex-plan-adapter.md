# Spec: Codex (ChatGPT plan) provider adapter

## Summary
Add OpenAI Codex as a plan-type provider, mirroring the existing CLAUDE_CODE adapter: read local OAuth credentials, poll one usage endpoint, render two windows.

## Research basis (Nov 27, 2026)
- Auth: `~/.codex/auth.json` — plain JSON, fields `auth_mode` ("chatgpt"|"apikey"), `access_token` (~30-60 min TTL), `refresh_token` (single-use, rotates), `id_token` (JWT, contains chatgpt_plan_type), `account_id`.
- `cli_auth_credentials_store` config key may be "keyring" — file absent ≠ signed out. Treat missing file as "no data", never as error.
- Usage: `GET https://chatgpt.com/backend-api/wham/usage` with `Authorization: Bearer <access_token>` + `ChatGPT-Account-Id: <account_id>` header. Response: `plan_type`, `rate_limit.primary_window` + `secondary_window` each with `used_percent` (0-100) + `reset_at` (unix seconds), `credits`, `additional_rate_limits`.
- Window classification by LENGTH not position: 604800s = weekly, ~18000s = 5-hour. Pro plans have NO 5-hour window (primary may BE weekly).
- Unofficial endpoint (found in Codex source, used by ClaudeBar + headroom) — may change; wrap parsing defensively.
- Prior art: github.com/tddworks/ClaudeBar (docs/providers/codex/design.md), github.com/chopratejas/headroom (subscription/codex_rate_limits.py).

## Implementation
1. **ProviderConfig.kt**: add `CODEX("Codex (ChatGPT plan)", ProviderCategory.LLM_PROVIDER)`. `configDir` field semantics mirror CLAUDE_CODE (null/blank → default `~/.codex`).
2. **CodexPlanAdapter.kt** (network/):
   - Read auth.json from configDir each poll (never cache across polls — CLI may rotate tokens).
   - `auth_mode != "chatgpt"` → account state "signed-out-equivalent" (API-key mode has no plan quota; skip, report no data).
   - Call wham/usage with Bearer + account-id headers. 401/403 → no data (token likely mid-rotation; next poll retries).
   - Map windows by duration: ≥600000s → weekly gauge; 10000-30000s → 5-hour gauge. Missing window → hide that gauge (Pro: no 5h window).
   - `used_percent` → gauge fill; `reset_at` → countdown, reuse existing formatting.
   - NO refresh-token use in v1: if access_token is expired, the endpoint 401s → no data; the Codex CLI refreshes the file on its own activity. Document this trade-off in KDoc (race avoidance: we never rotate tokens, so we can never invalidate the CLI's).
3. **Login UX**: if auth.json missing/invalid, card shows "Sign in with `codex login`" hint text only. NO interactive launch button in v1 (Codex login flow details unverified on our platforms; hint is honest). Follow-up if desired later.
4. **Desktop-only**: like CLAUDE_CODE, no Android support (no filesystem access to ~/.codex). Declare in provider metadata.
5. **Tests**: adapter parsing (fixture JSONs for: full two-window, Pro single-window, apikey mode, malformed), window-length classification, reset_at countdown math. Follow LimitingWindowGaugeTest patterns.
6. **Docs**: note unofficial-endpoint risk + fallback path (`codex app-server` `account/rateLimits/read` command) in the adapter KDoc for future maintainers.

## Non-goals
- No token refresh/rotation (v1). No keyring backend support (v1; missing file = no data). No login-launch button. No usage *history* beyond current snapshot. No API-key-mode quota (that's OPENAI provider's job).

## Acceptance
- Codex provider addable on desktop, polls without error when signed in, shows plan gauges with countdowns.
- Absent auth / apikey mode / endpoint failure → clean "no data" states, no crashes, no token writes.
- Full test suite green; CI green.
