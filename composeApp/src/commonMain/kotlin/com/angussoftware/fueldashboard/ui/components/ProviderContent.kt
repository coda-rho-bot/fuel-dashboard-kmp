package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow

@Composable
fun ProviderContent(
    config: ProviderConfig,
    report: ProviderReport?,
    error: String?,
    showHelp: Boolean,
    titleStyle: TextStyle,
    contentSpacing: Dp,
    isChecking: Boolean,
    onCheckJunieBalance: (() -> Unit)?,
    boxedCreditBalance: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(config.resolvedDisplayName(), style = titleStyle, fontWeight = FontWeight.Bold)
            if (error != null) {
                Text("\u26A0 Error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            } else if (report != null && !report.available) {
                Text("UNAVAILABLE", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }

        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (config.kind != ProviderKind.JUNIE) return@Column
        }

        if (config.kind == ProviderKind.JUNIE) {
            Spacer(Modifier.height(contentSpacing))
            JunieProviderBalance(report, isChecking, onCheckJunieBalance)
            return@Column
        }

        if (report == null) {
            Spacer(Modifier.height(8.dp))
            Text("Connecting...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        Spacer(Modifier.height(contentSpacing))
        when (report.type) {
            ProviderType.SPEND_BUDGET -> {
                report.usedDollars?.let { BudgetBar(it, report.limitDollars, showHelp = showHelp) } ?: if (report.rawDisplay.isBlank()) Text(
                    "No spend data (costs API unavailable — admin key required, or request failed)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ) else Unit
                val rateLimitWindows = report.windows.filter { it.name == "Requests/min" || it.name == "Tokens/min" }
                if (rateLimitWindows.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    rateLimitWindows.forEachIndexed { idx, w ->
                        if (idx > 0) Spacer(Modifier.height(10.dp))
                        ProviderWindowRow(w, showHelp)
                    }
                }
                report.windows.firstOrNull { it.name == "Monthly Budget" }?.let {
                    Spacer(Modifier.height(8.dp))
                    ProviderWindowRow(it, showHelp)
                }
            }
            ProviderType.RATE_LIMIT -> report.windows.forEachIndexed { idx, w ->
                if (idx > 0) Spacer(Modifier.height(10.dp))
                ProviderWindowRow(w, showHelp)
            }
            ProviderType.WINDOW_CREDIT -> {
                if (config.kind == ProviderKind.CONNECTED_API) {
                    // Remote Dashboard: show custom text instead of "no usage data"
                    Text(
                        "Connected to remote dashboard",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (report.windows.isNotEmpty()) {
                    report.windows.forEachIndexed { idx, w ->
                        if (idx > 0) Spacer(Modifier.height(10.dp))
                        ProviderWindowRow(w, showHelp)
                    }
                } else if (report.remainingPct != null) {
                    FuelBar(report.remainingPct, label = "Remaining", showHelp = showHelp)
                } else {
                    Text("No usage data (unlimited or static)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (report.creditsTotal != null && report.creditsTotal > 0) {
                    Spacer(Modifier.height(12.dp))
                    ProviderCreditBalance(report, showHelp, boxedCreditBalance)
                }
            }
        }

        // Universal status line: adapters put their honest per-surface status
        // in rawDisplay (e.g. Gemini "50 models · limits in AI Studio" with no
        // rate headers, OpenRouter spend-only "$1.25 today | $74.25 this
        // month"). Without this line those degraded states render as an empty
        // tile with just the provider name.
        if (report.rawDisplay.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                report.rawDisplay,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProviderWindowRow(window: ReportWindow, showHelp: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Fuel bar row
        window.remainingPct?.let { FuelBar(it, compact = true, label = window.name, showHelp = showHelp) } ?: Text(
            "—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Timer row (below fuel bar)
        window.resetsAt?.let { resetTime ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimerBar(
                    resetsAt = resetTime,
                    windowMs = (window.windowHours * 3_600_000).toLong(),
                    showHelp = showHelp,
                    modifier = Modifier.weight(1f),
                )
                if (window.resetEstimated) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Estimated timer",
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (window.resetEstimated) {
                Text(
                    "Timer estimated — provider did not return a reset value. This may indicate the window resets on next request or a connectivity issue.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFC107),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderCreditBalance(report: ProviderReport, showHelp: Boolean, boxed: Boolean) {
    val criticallyLow = report.creditsTotal != null && report.creditsTotal < 1000
    val containerColor = if (criticallyLow) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (criticallyLow) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val subColor = if (criticallyLow) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val modifier = if (boxed) {
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(containerColor).padding(12.dp)
    } else {
        Modifier.fillMaxWidth().padding(12.dp)
    }

    Column(modifier) {
        if (boxed) {
            Text(formatCredits(report.creditsTotal), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = contentColor)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("credits remaining", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = subColor)
                if (showHelp) {
                    Spacer(Modifier.width(4.dp))
                    HelpIcon("Your purchased credits.")
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatCredits(report.creditsTotal), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = contentColor)
                if (showHelp) {
                    Spacer(Modifier.width(4.dp))
                    HelpIcon("Your purchased credits.")
                }
            }
            Text("credits remaining", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = subColor)
        }
        if (report.creditsUsed != null && report.creditsLimit != null) {
            Spacer(Modifier.height(6.dp))
            val overLimit = report.creditsUsed > report.creditsLimit
            Text(
                if (overLimit) "${formatCredits(report.creditsUsed)} used (${formatCredits(report.creditsLimit)} included monthly, rest pay-as-you-go)"
                else "${formatCredits(report.creditsUsed)} / ${formatCredits(report.creditsLimit)} used this month",
                style = MaterialTheme.typography.bodyMedium,
                color = subColor,
            )
        }
        report.creditsResetAt?.let { resetAt ->
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quota resets ", style = MaterialTheme.typography.bodyMedium, color = subColor)
            }
            TimerBar(
                resetsAt = resetAt,
                windowMs = 2_592_000_000L, // 30 days default
            )
        }
    }
}

/**
 * Formats an integer with thousands separators (e.g., 1234567 → "1,234,567").
 * Pure-Kotlin (no java.text dependency) for commonMain compatibility.
 */
private fun formatCredits(n: Int?): String {
    if (n == null) return "—"
    val s = n.toString()
    return buildString {
        val len = s.length
        for (i in s.indices) {
            if (i > 0 && (len - i) % 3 == 0) append(',')
            append(s[i])
        }
    }
}