package com.angussoftware.fueldashboard.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardUiApiTest {
    @Test
    fun settingsDoesNotRenderTheAgentsSection() {
        val source = sourceFile("ui/components/SettingsPanel.kt").readText()
        val settingsPanel = source.substringBefore("// ---------------------------------------------------------------------------\n// Providers Section")

        assertFalse(settingsPanel.contains("AgentsSection("))
    }

    @Test
    fun agentPanelProvidesValidatedManualConfiguration() {
        val source = sourceFile("ui/components/AgentPanel.kt").readText()

        assertTrue(source.contains("Add Manually"))
        assertTrue(source.contains("internal fun AddAgentDialog"))
        assertTrue(source.contains("enabled = name.isNotBlank() && command.isNotBlank()"))
    }

    @Test
    fun fuelScreensExplainProviderStatesAndManagement() {
        val expectedEmptyState = "No providers configured. Add your LLM provider in Settings to start monitoring fuel levels. \\u2192"

        listOf("ui/App.kt", "ui/MobileDashboard.kt").forEach { path ->
            val source = sourceFile(path).readText()

            assertTrue(source.contains(expectedEmptyState))
            assertTrue(source.contains("Connecting to providers..."))
            assertTrue(source.contains("Providers managed in Settings"))
        }
    }

    private fun sourceFile(relativePath: String): File = sequenceOf(
        File("src/commonMain/kotlin/com/angussoftware/fueldashboard/$relativePath"),
        File("composeApp/src/commonMain/kotlin/com/angussoftware/fueldashboard/$relativePath"),
    ).first { it.isFile }
}