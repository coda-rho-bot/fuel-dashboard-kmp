package com.angussoftware.fueldashboard.presentation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Serializes the complete dashboard display state for programmatic access —
 * the MCP `get_dashboard` tool and the HTTP `GET /dashboard` endpoint both
 * return this. Every piece of information the UI displays is included;
 * secrets (provider API keys, server API key) are excluded by construction.
 *
 * Pure function over [DashboardState] — no I/O, fully testable.
 */
object DashboardSnapshot {

    fun build(state: DashboardState): JsonObject = buildJsonObject {
        put("generated_at", state.lastUpdated)
        put("last_updated", state.lastUpdated)

        // ── Providers: levels, burn rates, resets, errors ──────────────
        put("providers", buildJsonObject {
            state.activeProviders.forEach { config ->
                val report = state.providerReports[config.id]
                val error = state.providerErrors[config.id]
                put(config.id, buildJsonObject {
                    put("name", report?.displayName ?: config.displayName.ifBlank { config.kind.name })
                    put("kind", config.kind.name)
                    report?.remainingPct?.let { put("remaining_pct", it) }
                    report?.resetsAt?.let { put("resets_at", it) }
                    if (report != null && report.windowHours > 0) put("window_hours", report.windowHours)
                    error?.let { put("error", it) }
                })
            }
        })

        // ── Fuel: burn rates + projection ──────────────────────────────
        put("fuel", buildJsonObject {
            state.burnRate?.let { put("burn_rate_pct_per_hr", it) }
            state.fuelProjection?.let { p ->
                putJsonObject("projection") {
                    put("current_pct", p.currentPct)
                    p.burnRatePerHr?.let { put("burn_rate_pct_per_hr", it) }
                    put("hours_until_reset", p.hoursUntilReset)
                    p.hoursUntilExhaustion?.let { put("hours_until_exhaustion", it) }
                    put("projected_remaining_at_reset_pct", p.projectedRemainingAtReset)
                    put("will_make_it", p.willMakeIt)
                    put("headroom_pct", p.headroomPct)
                    put("active_agents", p.activeAgentCount)
                }
            }
        })

        // ── Advisor: the full v3 advice state ──────────────────────────
        state.fuelAdvice?.let { advice ->
            put("advisor", adviceJson(advice))
        }

        // ── Metered usage: all four breakdowns × 24h/7d ────────────────
        put("usage", buildJsonObject {
            putJsonObject("by_source_24h") { metered(state.meteredBySource24h) }
            putJsonObject("by_model_24h") { metered(state.meteredByModel24h) }
            putJsonObject("by_source_7d") { metered(state.meteredBySource7d) }
            putJsonObject("by_model_7d") { metered(state.meteredByModel7d) }
            putJsonObject("by_conversation_24h") { conversations(state.meteredByConversation24h) }
            putJsonObject("by_conversation_7d") { conversations(state.meteredByConversation7d) }
            putJsonObject("by_agent_model_24h") { agentModels(state.meteredByAgentModel24h) }
            putJsonObject("by_agent_model_7d") { agentModels(state.meteredByAgentModel7d) }
        })

        // ── Wasted quota (expired unused, per provider) ────────────────
        put("waste", buildJsonObject {
            state.wasteByProvider.forEach { pw ->
                put(pw.providerId, buildJsonObject {
                    put("provider_name", pw.providerName)
                    put("window_ms", pw.windowMs)
                    put("wasted_pct_avg", pw.wastedPctAvg)
                    put("daily", buildJsonObject {
                        pw.daily.forEach { d ->
                            put(d.dayStart.toString(), buildJsonObject {
                                put("windows", d.windows)
                                put("observed", d.observed)
                                put("estimated", d.estimated)
                                put("wasted_pct_avg", d.wastedPctAvg)
                                put("any_exhausted", d.anyExhausted)
                            })
                        }
                    })
                })
            }
        })

        // ── Fuel events timeline ───────────────────────────────────────
        put("fuel_events", buildJsonObject {
            put("count", state.fuelEvents.size)
            put("events", buildJsonObject {
                state.fuelEvents.forEachIndexed { i, e ->
                    put(i.toString(), buildJsonObject {
                        put("timestamp", e.timestamp)
                        put("type", e.type.name)
                        put("description", e.description)
                    })
                }
            })
        })

        // ── Model drain rates (gauge-correlated) ───────────────────────
        put("model_drain_rates", buildJsonObject {
            state.modelDrainRates.forEach { r ->
                put(r.model, buildJsonObject {
                    put("total_fuel_consumed_pct", r.totalFuelConsumed)
                    put("avg_drain_per_hr", r.avgDrainPerHr)
                    put("samples", r.sampleCount)
                })
            }
        })

        // ── Agents: status + models actually in use ────────────────────
        put("agents", buildJsonObject {
            state.acpAgents.forEach { a ->
                put(a.name, buildJsonObject {
                    put("id", a.id)
                    put("status", a.status)
                    a.currentModel?.let { put("session_model", it) }
                    a.lastSeen?.let { put("last_seen", it) }
                })
            }
        })

        // ── Per-agent metered model usage (the honest model picture) ───
        put("agent_model_usage_24h", buildJsonObject {
            state.meteredByAgentModel24h.forEach { row ->
                put("${row.agentName}:${row.model}", buildJsonObject {
                    put("agent", row.agentName)
                    put("model", row.model)
                    put("input_tokens", row.inputTokens)
                    put("output_tokens", row.outputTokens)
                    put("requests", row.requestCount)
                    row.creditCost?.let { put("zai_credits", it) }
                })
            }
        })

        // ── Ingestion + provider burn-rate context ─────────────────────
        put("ingestion", buildJsonObject {
            put("enabled", state.usageIngestion.enabled)
            put("last_poll_at", state.usageIngestion.lastPollAt)
            state.usageIngestion.lastError?.let { put("last_error", it) }
            put("total_ingested", state.usageIngestion.totalIngested)
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.metered(rows: List<MeteredUsageDisplay>) {
        rows.forEach { r ->
            put(r.label, buildJsonObject {
                put("input_tokens", r.inputTokens)
                put("output_tokens", r.outputTokens)
                put("requests", r.requestCount)
                r.creditCost?.let { put("zai_credits", it) }
            })
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.conversations(rows: List<ConversationUsageDisplay>) {
        rows.forEach { r ->
            put(r.conversationId, buildJsonObject {
                r.title?.let { put("title", it) }
                put("agent", r.agentName)
                put("model", r.model)
                put("input_tokens", r.inputTokens)
                put("output_tokens", r.outputTokens)
                put("requests", r.requestCount)
                r.creditCost?.let { put("zai_credits", it) }
            })
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.agentModels(rows: List<AgentModelUsageDisplay>) {
        rows.forEach { r ->
            put("${r.agentName}:${r.model}", buildJsonObject {
                put("agent", r.agentName)
                put("model", r.model)
                put("input_tokens", r.inputTokens)
                put("output_tokens", r.outputTokens)
                put("requests", r.requestCount)
                r.creditCost?.let { put("zai_credits", it) }
            })
        }
    }

    private fun adviceJson(advice: FuelAdvisor.Advice): JsonObject = when (advice) {
        is FuelAdvisor.Advice.InsufficientData -> buildJsonObject {
            put("state", "insufficient_data")
            put("message", "Collecting quota history — need ~2 window cycles before advice is honest.")
        }
        is FuelAdvisor.Advice.Surplus -> buildJsonObject {
            put("state", "surplus")
            put("message", "No action needed — surplus regime, the smart model is effectively free right now.")
            put("exhaustions", advice.regime.exhaustions)
            put("windows_analyzed", advice.regime.windowsAnalyzed)
            advice.regime.projectedPctAtReset?.let { put("projected_pct_at_reset", it) }
        }
        is FuelAdvisor.Advice.Healthy -> buildJsonObject {
            put("state", "healthy")
            put("message", "Window healthy — projected headroom at reset.")
            put("exhaustions", advice.regime.exhaustions)
            put("windows_analyzed", advice.regime.windowsAnalyzed)
            put("projected_headroom_pct", advice.projectedHeadroomPct)
        }
        is FuelAdvisor.Advice.AtRisk -> buildJsonObject {
            put("state", "at_risk")
            put("message", "Projected to exhaust before reset — move routine work to a cheaper model.")
            advice.projectedExhaustInMs?.let { put("exhausts_in_ms", it) }
            put("routine_consumers", buildJsonObject {
                advice.routineConsumers.forEach { rc ->
                    put(rc.title ?: rc.conversationKey, buildJsonObject {
                        put("model", rc.model)
                        put("active_days", rc.activeDays)
                        put("current_credits_per_day", rc.currentCreditPerDay)
                        put("projected_credits_per_day", rc.projectedCreditPerDay)
                        put("savings_fraction", rc.savingsFraction)
                    })
                }
            })
        }
        is FuelAdvisor.Advice.PersistentPressure -> buildJsonObject {
            put("state", "persistent_pressure")
            put("message", "Quota exhausts repeatedly — standing advice to move routine work to a cheaper model.")
            put("exhaustions", advice.regime.exhaustions)
            put("windows_analyzed", advice.regime.windowsAnalyzed)
            put("routine_consumers", buildJsonObject {
                advice.routineConsumers.forEach { rc ->
                    put(rc.title ?: rc.conversationKey, buildJsonObject {
                        put("model", rc.model)
                        put("active_days", rc.activeDays)
                        put("current_credits_per_day", rc.currentCreditPerDay)
                        put("projected_credits_per_day", rc.projectedCreditPerDay)
                        put("savings_fraction", rc.savingsFraction)
                    })
                }
            })
        }
    }
}
