# Security Guide

Understanding the authentication model and LAN exposure of the Fuel Dashboard.

## Authentication Model

### API Key

Every HTTP endpoint (except `/health`) requires a valid Bearer API key:

```
Authorization: Bearer <api-key>
```

The API key is a 256-bit cryptographically random value generated using
`java.security.SecureRandom` and Base64-URL encoded (no padding). This provides
sufficient entropy to resist brute-force attacks.

### Key Generation

The key is generated once on first launch and persisted in platform-native
preferences. It is never transmitted in plaintext except:

- In the `Authorization` header (over HTTP, within your LAN)
- Encoded in the QR code for mobile sync (visible only on your screen)

### Key Management

- **View:** Settings > Server section in the desktop app
- **Regenerate:** Creates a new key; all existing clients (mobile, MCP agents)
  must update their stored key
- **Distribution:** Via QR code (LAN sync) or manual copy to MCP client configs

### Endpoint Exemptions

Only `GET /health` is exempt from authentication. This endpoint returns a minimal
status JSON and is designed for uptime monitors and load balancers. It does not
expose any fuel data, agent information, or configuration.

## LAN Exposure

### Default Binding

The server binds to `0.0.0.0` (all network interfaces) by default. This means:

- **Anyone on your LAN** can reach port 8322 on your machine
- All endpoints (except `/health`) still require the API key
- The server is **not** exposed to the public internet (unless you port-forward)

### Why 0.0.0.0?

The default binding enables the QR sync feature — mobile devices on your Wi-Fi
need to reach the server. If the server only bound to `127.0.0.1`, no external
device could connect.

### Restricting to Localhost

If you do not need LAN access (e.g., you only use the desktop UI):

1. Change the server host setting to `127.0.0.1`
2. Restart the app
3. The server will only accept connections from the local machine

### Firewall Rules

For additional security, configure your OS firewall to restrict port 8322:

```bash
# Linux (ufw) - only allow from local subnet
sudo ufw deny 8322
sudo ufw allow from 192.168.1.0/24 to any port 8322

# Linux (iptables)
sudo iptables -A INPUT -p tcp --dport 8322 -s 192.168.1.0/24 -j ACCEPT
sudo iptables -A INPUT -p tcp --dport 8322 -j DROP
```

## CORS

CORS is configured with `anyHost()` — all origins are allowed. This is safe
because:

- HTTP credentials are **not** enabled in the CORS config
- Browsers cannot send the `Authorization` header cross-origin without explicit
  client-side code
- All endpoints still require the Bearer API key

This allows web-based dashboards and MCP browser clients to connect from any origin.

## Provider API Keys

Provider API keys (OpenAI, Anthropic, z.ai, etc.) are stored in platform-native
preferences and are:

- **Never exposed** via the HTTP API or MCP server
- The `list_providers` MCP tool intentionally strips API keys from the response
- Keys are only used internally by provider adapters for polling

## MCP Server Security

The MCP endpoint at `POST /mcp` requires the same Bearer API key as all other
endpoints. MCP clients must include:

```
Authorization: Bearer <api-key>
```

DNS rebinding protection is **disabled** (`enableDnsRebindingProtection = false`)
to allow LAN connections from various hostnames and IPs. This is acceptable in
the LAN context because the Bearer token provides the authentication layer.

## Security Checklist

- [ ] API key has been regenerated from the default (first-launch value)
- [ ] Firewall restricts port 8322 to trusted IPs/subnets
- [ ] Provider API keys are not shared or committed to version control
- [ ] QR code is not displayed in public or shared screenshots
- [ ] If not using mobile sync, server host is set to `127.0.0.1`
