# MCP Tools Reference

The Fuel Dashboard exposes an MCP (Model Context Protocol) server via Streamable
HTTP transport at `POST /mcp`. It advertises **7 tools** and **2 resources**.

The MCP server uses the same agent registry as the HTTP API, ensuring a single
source of truth. Agents registered via MCP appear in `GET /agents` and vice versa.

## Server Info

- **Name:** `fuel-dashboard`
- **Version:** `2.0.0`
- **Capabilities:** tools (with list-changed), resources (with list-changed and subscribe)

All MCP calls require the same Bearer API key as the HTTP endpoints.

---

## Tools

### `register_agent`

Agent self-registers with the Fuel Dashboard.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Human-readable agent name |
| `model` | string | No | Model handle (e.g., `anthropic/claude-sonnet-5`) |
| `framework` | string | No | Agent framework (e.g., `letta`, `crewai`, `autogen`) |
| `command` | string | No | Command to start the agent process |

**Returns:**

```json
{
  "status": "registered",
  "agentId": "coda"
}
```

If an agent with the same name (case-insensitive) already exists, it is updated
instead of duplicated.

---

### `update_model`

Agent reports that it has switched to a different model.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `agentId` | string | Yes | The agent's ID (returned from `register_agent`) |
| `model` | string | Yes | New model handle (e.g., `openai/gpt-4o`) |

**Returns (success):**

```json
{
  "status": "updated",
  "agentId": "coda",
  "model": "openai/gpt-4o"
}
```

**Returns (error):**

```json
{
  "error": "agent not found: coda"
}
```

---

### `update_status`

Agent reports its current operational status.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `agentId` | string | Yes | The agent's ID |
| `status` | string | Yes | Current status (`idle`, `working`, `waiting`, `error`) |

**Returns (success):**

```json
{
  "status": "updated",
  "agentId": "coda",
  "agentStatus": "working"
}
```

---

### `add_provider`

Adds an LLM provider to the Fuel Dashboard's persisted settings.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `kind` | string | Yes | Provider kind enum: `OPENAI`, `ANTHROPIC`, `ZAI`, `LETTA_CLOUD`, `DEEPSEEK`, `GROQ`, `MISTRAL`, `JUNIE`, `CONNECTED_API` |
| `api_key` | string | Yes | Provider API key |
| `name` | string | No | Custom display name |
| `server_url` | string | No | Provider server URL override |

**Returns:**

```json
{
  "status": "success",
  "message": "provider added: abc123-def456"
}
```

The `kind` parameter is case-insensitive and accepts hyphens or spaces (e.g.,
`letta-cloud` or `Letta Cloud` are normalized to `LETTA_CLOUD`).

---

### `remove_provider`

Removes a configured provider by ID or display name.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `id` | string | No* | Provider ID |
| `name` | string | No* | Provider display name |

*At least one of `id` or `name` is required. `id` takes precedence when both are provided.

**Returns:**

```json
{
  "status": "success",
  "message": "provider removed: abc123-def456"
}
```

---

### `list_providers`

Lists all configured providers **without exposing API keys**.

**Parameters:** None

**Returns:**

```json
{
  "providers": [
    {
      "id": "abc123-def456",
      "kind": "ANTHROPIC",
      "name": "Anthropic",
      "server_url": "https://api.anthropic.com"
    },
    {
      "id": "ghi789-jkl012",
      "kind": "ZAI",
      "name": "z.ai",
      "server_url": "https://api.z.ai"
    }
  ]
}
```

---

### `add_orchestrator`

Adds a remote Fuel Dashboard server connection as a `CONNECTED_API` provider.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `url` | string | Yes | Remote Fuel Dashboard server URL |
| `api_key` | string | No | API key for the remote server (required if remote auth is enabled) |

**Returns:**

```json
{
  "status": "success",
  "message": "remote dashboard added: mno345-pqr678"
}
```

---

## Resources

### `fuel://current`

Current fuel levels, costs, and limits for all configured providers.

**MIME type:** `application/json`

**Returns:** The full `FuelResponse` JSON object (same as `GET /fuel`), including
all provider states, burn rate, recommendation, and alerts.

---

### `fuel://recommendation`

Current model recommendation based on fuel levels, cost optimization, and headroom.

**MIME type:** `application/json`

**Returns:**

```json
{
  "recommended_model": "anthropic/claude-sonnet-5",
  "burn_rate_pct_per_hr": 12.5,
  "surplus_alert": false
}
```

---

## Connection Example

To connect an MCP client to the Fuel Dashboard:

```python
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

async def connect():
    async with streamablehttp_client(
        url="http://localhost:8322/mcp",
        headers={"Authorization": "Bearer YOUR_API_KEY"}
    ) as (read, write, _):
        async with ClientSession(read, write) as session:
            await session.initialize()

            # List available tools
            tools = await session.list_tools()
            print(tools)

            # Register an agent
            result = await session.call_tool("register_agent", {
                "name": "My Agent",
                "model": "anthropic/claude-sonnet-5",
                "framework": "custom"
            })
            print(result)

            # Read fuel state
            fuel = await session.read_resource("fuel://current")
            print(fuel)
```
