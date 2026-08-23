package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/**
 * Paginated row list — renders up to [pageSize] rows with a "Show more"
 * button that reveals [pageSize] more. Button hidden when all rows fit.
 * Page count persists across recompositions (e.g. polls that grow the
 * list do not reset pagination).
 */
@Composable
fun <T> PagedRows(
    items: List<T>,
    pageSize: Int = 10,
    row: @Composable (T) -> Unit,
) {
    var visible by remember { mutableIntStateOf(pageSize) }
    items.take(visible).forEach { row(it) }
    val remaining = items.size - visible
    if (remaining > 0) {
        TextButton(
            onClick = { visible += pageSize },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Show more ($remaining left)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
