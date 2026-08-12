# Research Documentation

The following research documents were created during the design and development
of the Fuel Dashboard. They provide technical context for architectural decisions.

## Available Research Documents

### [Provider API Research](https://git.angussoftware.dev/coda/fuel-dashboard-kmp/src/branch/main/docs/provider-research.md)

Catalogs the quota/usage/billing APIs exposed by each major LLM provider.
Covers OpenAI, Anthropic, Google Vertex AI, Cohere, Together AI, Groq, Mistral,
Fireworks, Perplexity, DeepSeek, and Replicate.

**Key findings:**

- Fuel types: window-credit (tank), spend budget (budget), rate-limit (faucet)
- Groq provides real-time rate limit data via response headers
- DeepSeek offers real-time balance via `GET /user/balance`
- OpenAI and Anthropic require admin-scoped keys for usage endpoints
- ~5 minute delay on most spend-based APIs

---

### [Orchestrator Embedded Research](https://git.angussoftware.dev/coda/fuel-dashboard-kmp/src/branch/main/docs/orchestrator-embedded-research.md)

Technical feasibility analysis for embedding the fuel orchestrator directly into
the KMP desktop app, eliminating the separate Node.js/Fastify service.

**Key findings:**

- Fully feasible: JVM can run Ktor Server, SQLite, and coroutine loops
- The app already had direct provider adapters replicating orchestrator polling
- Eliminates systemd service, Docker container, and inter-process HTTP overhead
- Single-process architecture simplifies deployment and debugging

---

### [MCP Server Research](https://git.angussoftware.dev/coda/fuel-dashboard-kmp/src/branch/main/docs/mcp-server-research.md)

Technical approach for exposing the Fuel Dashboard as an MCP server, allowing
agents to self-register and read fuel state via the standard protocol.

**Key findings:**

- Official MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk`) is mature
- Maintained by Anthropic in collaboration with JetBrains
- Supports Streamable HTTP and SSE transports
- Can be embedded directly into the existing Ktor server — no separate port
- Apache 2.0 / MIT licensed

---

### [Agent Framework API Research](https://git.angussoftware.dev/coda/fuel-dashboard-kmp/src/branch/main/docs/agent-framework-research.md)

Determines which agent frameworks expose queryable APIs for listing agents and
their models, informing the universal adapter system.

**Key findings:**

- **Letta:** `GET /v1/agents` with model field — best fit
- **OpenAI Assistants:** `GET /v1/assistants` — deprecated (sunset Aug 2026)
- **Anthropic Claude:** `GET /v1/agents` — new Managed Agents API (beta)
- **CrewAI:** Enterprise only; model field unclear
- **AutoGen:** No REST API; library only
- **n8n:** Workflows, not agents; model buried in node params

Tier 1 (fully viable): Letta, OpenAI Assistants, Anthropic Claude, Azure AI Foundry.
Tier 2 (partial): LangGraph, CrewAI, Dify, Flowise, Google Vertex AI.
