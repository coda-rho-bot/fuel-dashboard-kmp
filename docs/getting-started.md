# Getting Started

## Run the desktop app

```bash
fuel-dashboard          # launcher script (gradle run under the hood)
```

or directly:

```bash
cd ~/dev/infra/fuel-dashboard-kmp && ./gradlew :composeApp:run
```

The app starts an embedded API server on `http://0.0.0.0:8322` (LAN-accessible; also served at `https://fuel.angussoftware.dev` via Cloudflare tunnel).

!!! note "First build is slow"
    Gradle downloads dependencies on first run (minutes). Subsequent starts are fast.

## First-run setup

### 1. Add a provider (Settings → Providers)

A provider is a fuel source you want to monitor. Each kind needs its own credentials:

| Provider | What you need | What you get |
|----------|---------------|--------------|
| **z.ai** | API key | 5h TOKENS_PCT window, burn rate, reset time |
| **Letta Cloud** | API key | credit balance, monthly allowance |
| **Junie** | nothing (checks locally) | AI credit balance |
| **DeepSeek / OpenAI / Anthropic / Groq / Mistal** | API key | balance / rate info depending on provider |
| **Connected API** | server URL + API key | monitor another dashboard's orchestrator |

z.ai is the primary fuel for the fleet — start there.

### 2. Enable usage metering (Settings → Usage Sources)

Providers show *levels*; usage sources show *where tokens went*. Enable **Letta runs** and paste the Letta API key:

- Pulls per-run token counts from the Letta API (agent, model, conversation)
- Refreshes continuously; conversations and agents appear as data accumulates
- Powers the Usage tab, the Agents tab "Models in use" rows, and the Advisor

Without a usage source the app still works — you get levels and burn rates, but no attribution.

### 3. (Optional) Self-reported usage

Any tool outside Letta can report its own usage — the dashboard stores it in the same metered pool:

```bash
~/.letta/scripts/report_usage.sh --source junie --model junie:gpt-5.6-terra \
  --input 21079 --output 145 --requests 2
```

The `junie-auth` wrapper reports automatically for JSON-mode tasks. An MCP tool (`report_usage` from the `fuel-dashboard` server) is also available.

## Mobile app

The Android app is the same dashboard (Fuel / Usage / Agents / Intel / Settings tabs).

**Pairing via QR:** Desktop → Agents tab → QR icon → scan with the phone app. The QR carries the server URL **and API key** — the phone connects directly to your desktop's embedded server (LAN or via `fuel.angussoftware.dev`).

**Settings sync:** the same QR round-trips your provider configuration, themes, and agent list between devices.

## Where things live

| Thing | Location |
|-------|----------|
| App settings | Java prefs (`~/.java/.userPrefs/fuel-dashboard/`) |
| Database (usage, snapshots, titles) | `~/.fuel-dashboard/decisions.db` |
| API server | `http://127.0.0.1:8322` (Bearer-key auth on data endpoints) |
| MCP endpoint | `http://127.0.0.1:8322/mcp` (Streamable HTTP) |

See [Self-Hosting & API](self-hosting.md) for the full API surface.

## Reporting issues from the app

Settings → Feedback → **Report an issue** files a real issue on the project's Forgejo repo. One-time setup: a Forgejo API token with the `write:issue` scope (Settings → Feedback → token field; shared to mobile via QR settings sync). Reports land in the issue tracker you already watch — nothing disappears into an inbox.
