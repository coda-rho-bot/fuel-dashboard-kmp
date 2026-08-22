package com.angussoftware.fueldashboard.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Source-level semantics of the Settings page:
 * ordering, save semantics (draft + explicit Save — never write-per-keystroke),
 * destructive-action confirmation, and collapse persistence.
 */
class SettingsPageSemanticsTest {

    private val settingsPanel: String by lazy { sourceFile("ui/components/SettingsPanel.kt").readText() }
    private val usageSources: String by lazy { sourceFile("ui/components/UsageSourcesSection.kt").readText() }

    @Test
    fun settingsPageOrdersCoreConfigFirstAndReferenceMaterialLast() {
        val body = settingsPanel.substringAfter("Spacer(Modifier.height(12.dp))\n\n            // --- Providers section")

        val providers = body.indexOf("ProvidersSection(")
        val usage = body.indexOf("UsageSourcesSection(")
        val theme = body.indexOf("ThemeSettingsPanel(")
        val advanced = body.indexOf("AdvancedSection()")
        val docs = body.indexOf("DocumentationFooter()")

        assertTrue(providers in 0 until usage, "Providers must come before Usage Sources")
        assertTrue(usage < theme, "Usage Sources must come before Theme")
        assertTrue(theme < advanced, "Theme must come before Advanced")
        assertTrue(advanced < docs, "Advanced must come before the docs footer")
        assertTrue(docs > 0, "Documentation footer must exist")
    }

    @Test
    fun showHelpToggleLivesInTheHeaderNotThePageBottom() {
        val body = settingsPanel.substringAfter("fun SettingsPanel(")
        val headerEnd = body.indexOf("// --- Providers section")
        val header = body.substring(0, headerEnd)

        assertTrue(header.contains("Show Help"), "Show Help belongs in the header row")
        assertTrue(header.contains("viewModel::setShowHelp"))
    }

    @Test
    fun textInputsNeverPersistOnKeystroke() {
        // The anti-pattern: writing persisted settings from onValueChange.
        // All text config must go through local draft state + explicit Save.
        listOf(settingsPanel, usageSources).forEach { source ->
            val violations = "onValueChange = \\{[^}]*\\}".toRegex().findAll(source)
                .mapNotNull { match ->
                    // capture a wider window after the match to see if a save call
                    // appears before the lambda closes
                    val window = source.substring(match.range.first, (match.range.last + 120).coerceAtMost(source.length))
                    if (window.contains("saveStringSetting") || window.contains("saveLetta")) match.range.first else null
                }.toList()
            assertEquals(emptyList(), violations, "keystroke-persist violation at offsets: $violations")
        }
    }

    @Test
    fun providerRemovalRequiresConfirmation() {
        val rowBody = settingsPanel.substringAfter("private fun ProviderConfigRow(")
        assertTrue(rowBody.contains("showRemoveConfirm"), "Delete must gate behind a confirm dialog")
        assertTrue(rowBody.contains("AlertDialog"))
        assertTrue(rowBody.contains("Remove provider?"))
        assertTrue(rowBody.contains("onRemove()"), "Confirm must be the only path to onRemove")
    }

    @Test
    fun collapsibleSectionsPersistTheirState() {
        assertTrue(settingsPanel.contains("FuelSettingsKeys.COLLAPSED_PROVIDERS"), "Providers collapse state must persist")
        assertTrue(settingsPanel.contains("FuelSettingsKeys.COLLAPSED_ADVANCED"), "Advanced collapse state must persist")
        assertTrue(usageSources.contains("FuelSettingsKeys.COLLAPSED_USAGE"), "Usage Sources collapse state must persist")
    }

    @Test
    fun advancedSectionContainsIntelligenceAndFeedbackWithExplicitSave() {
        val advanced = settingsPanel.substringAfter("private fun AdvancedSection(")
        assertTrue(advanced.contains("IntelligenceSettings()"))
        assertTrue(advanced.contains("FeedbackSettings()"))

        val intelligence = settingsPanel.substringAfter("private fun IntelligenceSettings()")
        assertTrue(intelligence.contains("Enter a number between 0.1 and 20.0"), "Invalid thresholds need visible errors")

        val feedback = settingsPanel.substringAfter("private fun FeedbackSettings()")
        assertTrue(feedback.contains("Save"))
        assertTrue(feedback.contains("Report an issue"))
    }

    @Test
    fun usageSourcesFieldsAreDraftsUntilSave() {
        val section = usageSources.substringAfter("fun UsageSourcesSection(")
        assertTrue(section.contains("urlDraft"), "URL must be a draft field")
        assertTrue(section.contains("keyDraft"), "API key must be a draft field")
        assertTrue(section.contains("UsageSourcesStore.saveLetta(config)"), "Save commits config")
        // The enable toggle is instant-apply and auto-expands the section
        assertTrue(section.contains("if (enabled && isCollapsed)"))
    }

    private fun sourceFile(relativePath: String): File = sequenceOf(
        File("src/commonMain/kotlin/com/angussoftware/fueldashboard/$relativePath"),
        File("composeApp/src/commonMain/kotlin/com/angussoftware/fueldashboard/$relativePath"),
    ).first { it.isFile }
}
