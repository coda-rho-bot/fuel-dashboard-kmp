# Agents Tab

The fleet monitor: one card per agent the dashboard knows about.

## Where agents come from

| Source | Meaning |
|--------|---------|
| **ACP (live)** | The dashboard holds a real ACP session with the agent — status dot shows connected/idle/thinking |
| **MCP self-registration** | The agent registered itself via the dashboard's MCP server |
| **Synced (config only)** | The entry came from a settings sync (e.g. phone) — no live session. Rendered dimmed with a "config only" badge. |

## Reading an agent card

**The honest core: "Models in use · 24h"** — what this agent's conversations *actually ran* over the last day, metered:

```
glm-5.2   180M tokens · 5 conv · 150 req
glm-4.7     2M tokens · 1 conv ·  12 req
Total 24h: 182M tokens · 162 requests
```

This is the truth about "what model does this agent use" — **there is no single answer**. Models and permissions are set per conversation in Letta; the same agent may run glm-5.2 in your interactive session and glm-4.7 in a cron.

Agents with no metered usage say exactly that: *"No metered usage in the last 24h — models are set per conversation; there's no single agent model."*

**Status dot**: green = connected, amber = idle, blue = thinking, grey = disconnected (retrying with backoff).

**Last seen**: when the dashboard last had a live session.

## What happened to the model/mode selectors?

Older versions had model dropdowns and permission-mode chips on each card. They were **removed deliberately**: they only configured the dashboard's internal monitoring session (which has no chat surface), and the mode selector was never wired to anything. They implied a per-agent model/permission config that does not exist in Letta. If a future dashboard feature needs session launching, it will be an explicit "start session" action — not a selector dressed as configuration.

## Managing the list

- **Remove** (trash icon): removes the agent from *monitoring* — nothing is deleted in Letta
- **Add**: register an agent manually by command
- **QR**: sync providers/settings/agent list with the mobile app
