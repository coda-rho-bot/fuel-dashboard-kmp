# Fuel Dashboard 0.2.0-beta.2 — Release Notes

**August 25, 2026**

Beta.2 — fixes for connected mode (mobile paired with a desktop dashboard).

## Changes since 0.2.0-beta.1

- **Android fuel display fixed**: the Fuel tab now renders provider gauges and
  burn-rate data when connected to a remote dashboard (callbacks were not
  wired on Android — the tab sat on "Collecting data" forever)
- **Status notification fixed in connected mode**: the persistent notification
  was stuck on "Loading fuel status…" because provider gauges were read from
  the legacy `/fuel` endpoint, which no longer carries them. Gauges now come
  from the `/dashboard` snapshot — live quota %, reset countdown, and timer
  bars, updating every 30s poll
- **Connected-mode history/projection parity**: fuel history, projection, and
  burn rates now also see remote gauges (not just the cards and notification)
- Wire-contract tests added pinning the `/dashboard` providers format

---

# Fuel Dashboard 0.2.0-beta.1 — Release Notes

**August 24, 2026**

First public beta release.

## What's included

- **9 provider adapters**: z.ai, Letta Cloud, OpenAI, Anthropic, DeepSeek,
  Groq, Mistral, Junie, and Connected API (remote dashboard)
- **Embedded orchestrator**: model recommendations across providers based on
  fuel state and tier selection
- **QR sync**: pair mobile with desktop via two QR codes (settings + agents)
- **MCP server**: agents can query fuel state and self-register (port 8322)
- **Usage metering**: optional Letta API integration for per-run token
  attribution (agent, model, conversation)
- **17 themes**: Angus Software Theming library, system dark/light detection
- **Advisor**: opt-in fuel recommendations (hidden by default, toggle in Settings)
- **Feedback**: in-app issue reporting to the Forgejo issue tracker

## Platforms

- **Linux desktop**: DEB package + portable JAR
- **Windows desktop**: portable distribution (rebuild from source for this version)
- **Android**: signed AAB (Google Play closed beta)

## Known limitations

- iOS target is a stub (not functional in this beta)
- Windows portable for 0.2.0-beta.1 must be built from a Windows machine
- Dynamic model discovery is not yet implemented (provider model lists are
  hardcoded and may drift from upstream API changes)
- First Gradle build is slow (dependency download); subsequent starts are fast

## Security

- API keys stored locally (Java Preferences / Android Keystore)
- No telemetry, no analytics, no crash reporting
- API key comparison uses constant-time equality
- QR sync gated to QR version 20 or below (capacity safety)

## Reporting issues

Use the in-app Feedback → Report an Issue feature (requires a Forgejo API
token with `write:issue` scope), or file directly at
[github.com/coda-rho-bot/fuel-dashboard-kmp/issues](https://github.com/coda-rho-bot/fuel-dashboard-kmp/issues)
