# Configuration

## Provider Setup

All provider configuration is done through the desktop app's **Settings Panel**.
No environment variables or config files are required for basic setup.

### Adding a Provider

1. Open the desktop app.
2. Click the **Settings** gear icon.
3. Under **Providers**, click **Add Provider**.
4. Select the provider kind (e.g., `ANTHROPIC`, `OPENAI`, `ZAI`).
5. Enter your **API key** for that provider.
6. Optionally set a custom display name or server URL override.
7. Click **Save**.

The dashboard immediately begins polling the provider for fuel/quota status.

### Provider Configuration Fields

Each provider stores:

| Field | Description | Required |
|-------|-------------|----------|
| `kind` | Provider type enum (`ZAI`, `LETTA_CLOUD`, `OPENAI`, etc.) | Yes |
| `apiKey` | API key for the provider | Yes (except Junie and Connected API) |
| `displayName` | Custom label shown in the UI | No |
| `serverUrl` | Override the default API base URL | No |
| `monthlyBudgetUsd` | Monthly spend cap (OpenAI, Anthropic, Mistral only) | No |

### Default Server URLs

If no server URL is specified, the dashboard uses these defaults:

| Provider | Default URL |
|----------|-------------|
| z.ai | `https://api.z.ai` |
| Letta Cloud | `https://api.letta.com` |
| OpenAI | `https://api.openai.com` |
| Anthropic | `https://api.anthropic.com` |
| DeepSeek | `https://api.deepseek.com` |
| Groq | `https://api.groq.com/openai` |
| Mistral AI | `https://api.mistral.ai` |
| Junie | *(none — manual check)* |
| Remote Dashboard | `http://127.0.0.1:8322` |

See the [Providers Guide](../providers/index.md) for provider-specific setup details.

## Server Settings

### API Key

The embedded HTTP server generates a random 256-bit API key on first launch and
persists it. All endpoints except `/health` require this key as a Bearer token:

```
Authorization: Bearer <your-api-key>
```

To view or regenerate the API key:

1. Open **Settings** in the desktop app.
2. Navigate to the **Server** section.
3. The current API key is displayed.
4. Use **Regenerate** to create a new key (all existing clients must update).

### Server Port

The server listens on **port 8322** by default. This is defined in `EmbeddedServer.kt`:

```kotlin
companion object {
    const val DEFAULT_PORT = 8322
    const val DEFAULT_HOST = "0.0.0.0"
}
```

### LAN Binding

The server binds to `0.0.0.0` (all interfaces) so it is reachable from any device
on your LAN. This enables mobile devices to connect via the QR sync feature.

!!! warning "Security Consideration"
    Binding to `0.0.0.0` exposes the server to your entire LAN. All endpoints
    except `/health` require a valid Bearer API key. If you don't need LAN access,
    you can restrict the server to localhost. See the [Security Guide](../guides/security.md).

## Themes

The dashboard ships with **17 color themes** via the Angus-Software-Theming library:

| Theme Family | Variants |
|--------------|----------|
| Angus | Angus |
| Catppuccin | Latte, Frappé, Macchiato, Mocha |
| Nord | Nord, Nord Dark |
| Gruvbox | Gruvbox Light, Gruvbox Dark |
| Solarized | Solarized Light, Solarized Dark |
| Dracula | Dracula |
| Rose Pine | Rose Pine, Rose Pine Moon, Rose Pine Dawn |

### Changing the Theme

1. Open **Settings** in the desktop app.
2. Navigate to the **Theme** section.
3. Select from 17 available themes.

Themes are persisted per-platform:

- **Desktop:** `java.util.prefs.Preferences`
- **Android:** `SharedPreferences`

The app also detects system dark/light mode on launch and suggests a matching
theme if your current selection mismatches.

## Settings Storage

All settings (providers, server API key, theme, Junie balance) are stored in
platform-native preference stores — no external configuration files.

| Setting | Storage Key | Platform Store |
|---------|-------------|----------------|
| Providers list | `multi_provider_settings` | Preferences / SharedPrefs |
| Server API key | `server_api_key` | Preferences / SharedPrefs |
| Server host | `server_host` | Preferences / SharedPrefs |
| Server port | `server_port` | Preferences / SharedPrefs |
| Theme | `theme_name` | Preferences / SharedPrefs |
| Polling interval | `polling_interval` | Preferences / SharedPrefs |
| Junie balance | `junie_balance` | Preferences / SharedPrefs |
| Junie license | `junie_license` | Preferences / SharedPrefs |
