package com.angussoftware.fueldashboard.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

class HelpIconApiTest {
    @Test
    fun helpIconDoesNotUseUnstableMaterialTooltipApis() {
        val sourceFile = sequenceOf(
            File("src/commonMain/kotlin/com/angussoftware/fueldashboard/ui/components/HelpComponents.kt"),
            File("composeApp/src/commonMain/kotlin/com/angussoftware/fueldashboard/ui/components/HelpComponents.kt"),
        ).first { it.isFile }

        val source = sourceFile.readText()

        assertFalse(source.contains("TooltipBox("))
        assertFalse(source.contains("PlainTooltip("))
    }
}