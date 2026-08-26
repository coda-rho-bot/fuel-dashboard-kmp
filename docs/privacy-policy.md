# Fuel Dashboard Privacy Policy

**Last updated: August 24, 2026**

Fuel Dashboard is a local-first application. Your data stays on your machine.

## Data Collection

Fuel Dashboard does not collect, transmit, or share any personal data with the
developers or any third party. The app has no telemetry, no analytics, no crash
reporting, and no advertising SDKs.

## What stays on your device

- **API keys** — stored locally in Java Preferences (desktop) or Android
  Keystore (mobile). Never transmitted except to the provider you configured
  (e.g., to call the z.ai API you entered a key for).
- **Usage history** — stored in a local SQLite database
  (`~/.fuel-dashboard/decisions.db`). Never leaves the device.
- **Settings** — stored locally in Java Preferences / Android DataStore.

## What the app does with your keys

When you add a provider (e.g., z.ai, OpenAI), the app uses your API key to
poll that provider's API for quota/balance information. The key is sent only
to the provider's own API endpoint — never to any Angus Software server or
third party.

## Network access

The app makes outbound requests only to:
- The AI provider APIs you configure (z.ai, OpenAI, Anthropic, etc.)
- The Letta API (optional, for usage metering — only if you enable it)
- A remote dashboard URL (optional, Connected API mode — only if you configure one)

The embedded API server (`127.0.0.1:8322`) listens on localhost by default.
LAN access (0.0.0.0) is opt-in for QR sync with your own mobile device.

## Feedback / issue reporting

The in-app "Report an Issue" feature (Settings → Feedback) opens an issue on
the project's Forgejo repository using a token you provide. This is an
explicit user action — nothing is sent automatically.

## Children's privacy

The app is not directed at children under 13 and does not knowingly collect
any data from children.

## Changes to this policy

If the privacy policy changes, it will be updated in the app repository and
the "Last updated" date will be revised.

## Contact

File an issue at [github.com/coda-rho-bot/fuel-dashboard-kmp](https://github.com/coda-rho-bot/fuel-dashboard-kmp/issues)
