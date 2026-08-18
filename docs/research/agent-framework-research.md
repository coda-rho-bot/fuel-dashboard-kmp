# Agent Framework API Research

> **Purpose:** Determine which agent frameworks expose queryable APIs for listing agents and their models,
> to inform whether the Fuel Dashboard can build a universal adapter system (similar to the LLM provider approach).

## Summary Table

| Framework | List Agents API | Shows Model? | Auth | Self-hosted? | Viable for Dashboard? |
|---|---|---|---|---|---|
| **Letta** | `GET /v1/agents` | **Yes** -- `model` field (format: `provider/model-name`) | Bearer token | Both (self-hosted or Letta Cloud) | **Yes** -- best fit |
| **OpenAI Assistants** | `GET /v1/assistants` | **Yes** -- `model` field (e.g. `gpt-4o`) | Bearer token + beta header | Cloud only | **Yes** -- but deprecated (sunset Aug 2026) |
| **Anthropic Claude** | `GET /v1/agents` | **Yes** -- `model.id` field (e.g. `claude-sonnet-5`) | API key + beta header | Cloud only | **Yes** -- new Managed Agents API (beta) |
| **Azure AI Foundry** | `GET {endpoint}/assistants` | **Likely** -- model info in agent object | OAuth2 (Azure AD) | Cloud only (Azure) | **Yes** -- but Azure-specific auth complexity |
| **LangGraph / LangSmith** | `POST /assistants/search` | **Indirect** -- model may be in config but not guaranteed | API key | Both (self-hosted or Cloud) | **Partial** -- can list; model depends on graph config |
| **CrewAI** | `GET /crewai_plus/api/v1/agents` (Enterprise) | **Unknown** -- limited docs | Bearer token | Both (AMP Cloud or Factory) | **Partial** -- enterprise only; model field unclear |
| **Dify** | `GET /console/api/agents` (Console) | **Likely** -- in config snapshots, not base list | Session-based login | Both (self-hosted or Cloud) | **Yes** -- but console API requires session auth |
| **Flowise** | `GET /api/v1/chatflows?type=MULTIAGENT` | **No** -- model buried in `flowData` JSON graph | Bearer JWT | Self-hosted (primary) | **Partial** -- model extraction requires JSON parsing |
| **Google Vertex AI** | `GET /v1beta1/{parent}/agents` | **No** -- model in agent config, not list response | OAuth2 (Google Cloud) | Cloud only (GCP) | **Partial** -- list works but model requires detail call |
| **n8n** | `GET /api/v1/workflows` | **No** -- model in workflow node parameters | API key or Bearer JWT | Both (self-hosted or Cloud) | **No** -- workflows not agents; model buried |
| **AutoGen** (standalone) | None -- code-only, no REST API | N/A | N/A | N/A (library only) | **No** -- no REST API; use Azure AI Foundry instead |


---

## Tier 1: Fully Viable (clean list + model in single API call)

### 1. Letta (formerly MemGPT)

- **Endpoint:** `GET /v1/agents`
- **Base URL:** Self-hosted (`http://localhost:8283`) or `https://api.letta.com`
- **Auth:** `Authorization: Bearer <token>`
- **Key Response Fields:**
  ```json
  {
    "id": "agent-uuid",
    "name": "agent-name",
    "model": "anthropic/claude-sonnet-4-20250514",
    "embedding": "openai/text-embedding-3-small",
    "agent_type": "memgpt_agent",
    "llm_config": { "model": "claude-sonnet-4-20250514" },
    "tags": ["tag1"],
    "description": "...",
    "created_at": "2025-01-15T10:00:00Z"
  }
  ```
- **Model Format:** `provider/model-name` (e.g., `anthropic/claude-sonnet-4-20250514`)
- **Pagination:** Cursor-based (`before`, `after`, `limit` default 50)
- **Filtering:** By name, tags, project, template
- **Adapter Difficulty: Trivial.** Single GET request, parse `model` field. The `provider/model-name` format maps naturally to the existing provider system.
- **Notes:** The `llm_config` field is deprecated in favor of the simpler `model` string field. Both contain model info. This is the reference implementation for how an agent API should work.

### 2. OpenAI Assistants API

- **Endpoint:** `GET /v1/assistants`
- **Base URL:** `https://api.openai.com/v1`
- **Auth:** `Authorization: Bearer $OPENAI_API_KEY` + header `OpenAI-Beta: assistants=v2`
- **Key Response Fields:**
  ```json
  {
    "id": "asst_abc123",
    "name": "Coding Tutor",
    "model": "gpt-4o",
    "description": "...",
    "instructions": "...",
    "tools": [],
    "created_at": 1698982736
  }
  ```
- **Pagination:** Cursor-based (`after`, `before`, `limit` up to 100, default 20)
- **Adapter Difficulty: Trivial.** Single GET, parse `model` field. Already have OpenAI API key for the Providers tab.
- **Warning: DEPRECATED.** OpenAI announced sunset for August 26, 2026. The Responses API replaces it but has not yet been documented for agent listing. Build the adapter, but flag it as deprecated.

### 3. Anthropic Claude Managed Agents

- **Endpoint:** `GET /v1/agents`
- **Base URL:** `https://api.anthropic.com`
- **Auth:** Three headers required:
  - `X-Api-Key: $ANTHROPIC_API_KEY`
  - `anthropic-version: 2023-06-01`
  - `anthropic-beta: managed-agents-2026-04-01`
- **Key Response Fields:**
  ```json
  {
    "data": [
      {
        "id": "agent_011CZkYpogX7uDKUyvBTophP",
        "name": "My First Agent",
        "model": {
          "id": "claude-sonnet-5",
          "effort": { "type": "low" },
          "speed": "standard"
        },
        "description": "A general-purpose starter agent.",
        "created_at": "2026-03-15T10:00:00Z",
        "version": 1
      }
    ],
    "next_page": "cursor"
  }
  ```
- **Pagination:** Cursor-based (`page` / `next_page`)
- **Adapter Difficulty: Easy.** Parse `data[].model.id` for model info. Note the nested model object (model is `{ id, effort, speed }`, not a flat string).
- **Notes:** Beta API. The SDK sets beta headers automatically, but a raw REST adapter must include the `anthropic-beta` header. Rate limits: 300 req/min creates, 1200 req/min reads.


---

## Tier 2: Viable with Caveats

### 4. Dify

- **Endpoints:**
  - Agent roster: `GET /console/api/agents` -- returns agent list with pagination
  - Agent apps: `GET /console/api/agent` -- returns agent-mode apps
  - App info (per-app): `GET /v1/info` -- returns single app metadata
- **Base URL:** Self-hosted or `https://api.dify.ai`
- **Auth (Console API):** Session-based -- requires login token from `/console/api/login`. Not a simple API key.
- **Auth (App API):** `Authorization: Bearer <app_api_key>` (per-app scoped, only returns info for that one app)
- **Key Response Fields (Agent Roster via Console API):**
  ```json
  {
    "data": [
      {
        "id": "agent-uuid",
        "name": "My Agent",
        "agent_kind": "...",
        "active_config_snapshot_id": "...",
        "archived": false,
        "created_at": "..."
      }
    ],
    "has_more": true,
    "limit": 20,
    "page": 1,
    "total": 50
  }
  ```
- **Model Visibility:** Agent config snapshots contain model info. Need `GET /console/api/agents/{agent_id}/versions` to access config with model details. Not in the base list response.
- **Pagination:** Page/limit based
- **Adapter Difficulty: Medium.** Two-step: list agents, then fetch config snapshot for each to get model. Console API auth is session-based (login flow), not a clean API key. The public App API is key-based but only returns one app at a time.
- **Notes:** Dify has two API surfaces -- Console API (workspace management) and App API (per-app interaction). The Console API is what you need for fleet visibility, but it requires session auth.

### 5. LangGraph / LangSmith Agent Server

- **Endpoint:** `POST /assistants/search` (note: POST, not GET)
- **Base URL:** Self-hosted (`http://localhost:8124`) or LangSmith Cloud
- **Auth:** `X-Api-Key: <LANGSMITH_API_KEY>`
- **Key Response Fields:**
  ```json
  {
    "assistants": [
      {
        "assistant_id": "uuid",
        "graph_id": "my-graph",
        "name": "My Agent",
        "config": {
          "configurable": {},
          "tags": [],
          "recursion_limit": 25
        },
        "metadata": {},
        "created_at": "..."
      }
    ],
    "next": "cursor-or-null"
  }
  ```
- **Pagination:** Offset-based (`limit` up to 1000, `offset`)
- **Model Visibility:** Model info is **not guaranteed** in the response. The `config.configurable` object *may* contain model settings if the graph author passed it through, but many LangGraph agents hardcode the model in Python code. There is no standard `model` field.
- **Adapter Difficulty: Medium-Hard.** Can list assistants, but extracting model info is unreliable -- depends entirely on how the graph author structured their config. Some agents expose model in `config.configurable.model_name`, others don't.
- **Notes:** LangSmith also has a tracing API (`POST /api/v1/sessions/{id}/runs`) that could show model info from run traces, but that's observability, not inventory. The newer Agent Protocol spec (`POST /agents/search`) may eventually standardize this.

### 6. CrewAI (Enterprise / AMP)

- **Endpoint:** `GET /crewai_plus/api/v1/agents` (Enterprise/AMP API)
- **Base URL:** `https://app.crewai.com` or self-hosted AMP Factory
- **Auth:** `Authorization: Bearer <api_key>` + header `X-Crewai-Organization-Id: <org_uuid>`
- **Model Visibility:** Unknown. The `PlusAPI` class in CrewAI's source confirms the endpoint exists (`AGENTS_RESOURCE = "/crewai_plus/api/v1/agents"`), but response schema is not publicly documented.
- **Adapter Difficulty: Medium.** The API exists in code but documentation is sparse. CrewAI AMP's public REST API is per-crew execution (`/inputs`, `/kickoff`, `/status`), not fleet management.
- **Notes:** Each deployed crew gets its own URL (`https://{crew-name}.crewai.com`). There is no documented "list all crews" endpoint. Without clear public docs, this is hard to build a reliable adapter for.

### 7. Flowise

- **Endpoint:** `GET /api/v1/chatflows?type=MULTIAGENT`
- **Base URL:** Self-hosted Flowise instance
- **Auth:** `Authorization: Bearer <api_key>` (generated in admin UI)
- **Key Response Fields:**
  ```json
  {
    "data": [
      {
        "id": "uuid",
        "name": "Customer Support Bot",
        "type": "MULTIAGENT",
        "flowData": "{\"nodes\":[...],\"edges\":[...]}",
        "deployed": true,
        "category": "Support",
        "createdDate": "2024-01-15T10:30:00.000Z"
      }
    ],
    "total": 25,
    "page": 1,
    "pages": 3
  }
  ```
- **Pagination:** Page/limit based
- **Model Visibility:** **No** -- model is embedded inside `flowData`, a JSON string containing the visual node graph. The model is a node property. Extracting it requires parsing `flowData`, traversing the node tree, and finding LLM model nodes by node type.
- **Adapter Difficulty: Hard.** Must parse `flowData` JSON string, traverse node graph, identify LLM/chat model nodes, extract model name from `data` properties. The node structure is not standardized across versions.
- **Notes:** Flowise also has an Assistants API (`/api/v1/assistants`) for OpenAI-compatible assistants which might have cleaner model fields, but only covers assistants created via that specific integration.

### 8. Google Vertex AI Agents

- **Endpoint:** `GET /v1beta1/{parent}/agents` where `{parent}` = `projects/{project}/locations/{location}`
- **Base URL:** `https://{location}-aiplatform.googleapis.com` (location-specific endpoint)
- **Auth:** OAuth2 -- `Authorization: Bearer $(gcloud auth print-access-token)`
- **Key Response Fields:** Agent resource includes `name`, `base_agent`, `system_instruction`, `tools`, `environment`. Model information is **not** in the list response -- uses a `base_agent` reference (e.g., `antigravity-preview-05-2026`).
- **Pagination:** Standard Google Cloud (`pageToken`, `pageSize`)
- **Model Visibility:** **No** -- requires a separate `GET` on each agent for full configuration details.
- **Adapter Difficulty: Hard.** Google Cloud OAuth2 is complex. Multi-step: list agents, then GET each for details. Known issues with the list API returning empty results for agents created in Agent Platform Studio (as of mid-2026).
- **Also:** Deployed agents use a different endpoint: `GET /v1/projects/{project}/locations/{location}/reasoningEngines`.


---

## Tier 3: Not Viable

### 9. Microsoft AutoGen (standalone)

- **No REST API.** AutoGen is a Python library/framework, not a platform. Agents are defined in code and run in-process via `SingleThreadedAgentRuntime`.
- **Management:** No management API. No monitoring API. The `AgentRuntime` provides internal lifecycle management but no external query interface.
- **Alternative:** Azure AI Foundry provides a managed runtime. Azure AI Foundry has `GET {endpoint}/assistants?api-version=v1` which lists agents with standard fields (id, name, state). Auth is OAuth2. This is the Azure AI Foundry API, not AutoGen proper.
- **Verdict:** If you want to monitor AutoGen agents, you need Azure AI Foundry. Standalone AutoGen is invisible to external tooling.

### 10. n8n

- **Endpoint:** `GET /api/v1/workflows`
- **Base URL:** Self-hosted or `https://api.n8n.cloud`
- **Auth:** `X-N8N-API-KEY: <key>` or Bearer JWT
- **Problem:** n8n lists **workflows**, not agents. While n8n has AI agent nodes, these are embedded within workflows. The API returns the full workflow JSON including `nodes` array, where AI agent nodes have their model configuration in `parameters`.
- **Model Visibility:** Model info is buried in `nodes[].parameters` for nodes of type `@n8n/n8n-nodes-langchain.agent`. Extracting requires iterating through nodes, filtering by type, and reading model config from parameters.
- **Adapter Difficulty: Hard** and semantically wrong -- you'd be parsing workflow graphs to find agent nodes. The concept doesn't map cleanly to "list agents."
- **Verdict:** n8n is a workflow automation tool that happens to support AI nodes. It's not an agent platform. Skip for fleet visibility purposes.

---

## Adapter Architecture Recommendation

### Recommended Approach: Interface with REST Adapters

Following the same pattern as the LLM provider adapters:

```kotlin
interface AgentFrameworkAdapter {
    val frameworkId: String          // "letta", "openai", "anthropic", etc.
    val displayName: String

    suspend fun listAgents(): List<AgentInfo>
    suspend fun getAgent(id: String): AgentInfo?
    suspend fun testConnection(): Boolean
}

data class AgentInfo(
    val id: String,
    val name: String,
    val model: String,              // normalized model identifier
    val framework: String,          // which framework hosts this agent
    val status: AgentStatus,        // active/idle/unknown
    val createdAt: Instant?,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val rawConfig: Map<String, Any?> = emptyMap()  // framework-specific extras
)
```

### Priority Build Order

| Priority | Framework | Effort | Rationale |
|---|---|---|---|
| P0 | **Letta** | Trivial | Self-hosted, clean API, `provider/model-name` format. Already the primary use case. |
| P1 | **OpenAI Assistants** | Trivial | API key already configured in Providers tab. Flag as deprecated. |
| P1 | **Anthropic Claude** | Easy | API key already configured. New beta API, good to support early. |
| P2 | **Dify** | Medium | Self-hostable, popular, but session auth is friction. |
| P2 | **LangGraph/LangSmith** | Medium | Popular framework, but model visibility is unreliable. |
| P3 | **Flowise** | Hard | Self-hosted, but flowData parsing is fragile and version-dependent. |
| P3 | **CrewAI** | Medium | Enterprise feature, sparse docs. Wait for better public API docs. |
| P3 | **Google Vertex AI** | Hard | OAuth complexity, multi-step calls, known API bugs. |
| P3 | **Azure AI Foundry** | Medium | Only path to AutoGen visibility. Azure OAuth adds complexity. |
| Skip | **n8n** | Hard | Not an agent platform. Workflows != agents. |
| Skip | **AutoGen** | N/A | No REST API at all. |

### Key Design Decisions

1. **Model normalization:** Each adapter must normalize model identifiers to a common format. Letta already uses `provider/model-name`. OpenAI uses bare model names (`gpt-4o`). Anthropic uses `claude-*` names. The adapter should map these to the Fuel Dashboard's existing model registry.

2. **Auth diversity:** Adapters must handle multiple auth styles:
   - Bearer token (Letta, OpenAI, CrewAI, Flowise)
   - API key header (Anthropic, LangSmith, n8n)
   - OAuth2 (Google Vertex AI, Azure AI Foundry)
   - Session-based login (Dify Console API)

3. **Pagination:** Most APIs use cursor-based pagination. The adapter interface should handle this internally and return complete lists (or use lazy sequences for large fleets).

4. **Graceful degradation:** For Tier 2 frameworks where model info is unreliable (LangGraph, Flowise), show "Unknown" for model rather than failing. The agent still appears in the list with its name and framework.

5. **Framework auto-detection:** Consider detecting the framework type from the base URL pattern or API response shape, rather than requiring explicit configuration. For example, `/v1/agents` responses from Letta vs Anthropic have different structures.
