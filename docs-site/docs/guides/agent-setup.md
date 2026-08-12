# Agent Setup Guide

How to register agents for fleet monitoring via the Fuel Dashboard.

## Overview

The Fuel Dashboard can monitor AI agents through two mechanisms:

1. **ACP Discovery** — agents discovered automatically via the Agent Communication Protocol
2. **Self-Registration** — agents register themselves via HTTP API or MCP tools

Both methods feed into the same agent registry, providing a unified fleet view.

## ACP Agent Discovery

Agents that support the ACP (Agent Communication Protocol) are discovered
automatically. The ViewModel pushes discovered agents to the `EmbeddedServer`,
which merges them with self-registered agents for the `GET /agents` response.

ACP-discovered agents appear in the fleet panel with their:

- Agent ID
- Name
- Current model
- Task complexity
- Fuel allocation
- Active subagent count

## Self-Registration

Agents can register themselves using either the HTTP API or MCP tools.

### Via HTTP API

Register an agent with a POST request:

```bash
curl -X POST http://localhost:8322/agents/register \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Coda",
    "model": "anthropic/claude-sonnet-5",
    "framework": "letta",
    "command": "letta run coda"
  }'
```

Response:

```json
{
  "status": "registered",
  "agentId": "coda"
}
```

### Via MCP Tools

Agents using the Model Context Protocol can register through the `register_agent`
tool:

```python
result = await session.call_tool("register_agent", {
    "name": "Coda",
    "model": "anthropic/claude-sonnet-5",
    "framework": "letta"
})
```

## Updating Agent State

### Change Model

When an agent switches models, report the change:

```bash
curl -X POST http://localhost:8322/agents/coda/state \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model": "openai/gpt-4o"}'
```

Or via MCP:

```python
result = await session.call_tool("update_model", {
    "agentId": "coda",
    "model": "openai/gpt-4o"
})
```

### Update Status

Report operational status changes:

```bash
curl -X POST http://localhost:8322/agents/coda/state \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"status": "working"}'
```

Or via MCP:

```python
result = await session.call_tool("update_status", {
    "agentId": "coda",
    "status": "working"
})
```

Valid statuses: `idle`, `working`, `waiting`, `error`.

## Removing Agents

Delete an agent from the registry:

```bash
curl -X DELETE http://localhost:8322/agents/coda \
  -H "Authorization: Bearer YOUR_API_KEY"
```

This removes the agent from both in-memory state and SQLite persistence.

## Persistence

Self-registered agents are persisted to SQLite on registration. This means:

- Agents survive app restarts
- The agent registry is loaded from SQLite on server startup
- The `AgentRegistry` handles all CRUD operations against the database

## Fleet Monitoring Workflow

A typical fleet monitoring setup:

1. **Start the desktop app** — the embedded server starts on port 8322
2. **Each agent registers** on startup via HTTP or MCP
3. **Agents report model changes** when they switch models
4. **Agents report status changes** as they work
5. **The dashboard polls** every 30 seconds and displays the fleet

### Letta Agent Example

For a Letta-based agent, add registration to the startup sequence:

```python
import httpx

async def register_with_fuel_dashboard():
    async with httpx.AsyncClient() as client:
        response = await client.post(
            "http://localhost:8322/agents/register",
            headers={
                "Authorization": "Bearer YOUR_API_KEY",
                "Content-Type": "application/json"
            },
            json={
                "name": "My Agent",
                "model": "anthropic/claude-sonnet-5",
                "framework": "letta"
            }
        )
        agent_id = response.json()["agentId"]
        # Store agent_id for future status updates
```

### Report Model Switches

When your agent's routing logic selects a new model:

```python
async def report_model_change(agent_id, new_model):
    async with httpx.AsyncClient() as client:
        await client.post(
            f"http://localhost:8322/agents/{agent_id}/state",
            headers={
                "Authorization": "Bearer YOUR_API_KEY",
                "Content-Type": "application/json"
            },
            json={"model": new_model}
        )
```

## Reading Fuel State

Registered agents can read fuel state to make informed model decisions:

### Via HTTP

```bash
curl -H "Authorization: Bearer YOUR_API_KEY" \
  http://localhost:8322/fuel
```

### Via MCP Resource

```python
fuel = await session.read_resource("fuel://current")
recommendation = await session.read_resource("fuel://recommendation")
```

The recommendation resource provides the current recommended model, burn rate,
and surplus alert status — enabling agents to align their model selection with
the dashboard's decision engine.
