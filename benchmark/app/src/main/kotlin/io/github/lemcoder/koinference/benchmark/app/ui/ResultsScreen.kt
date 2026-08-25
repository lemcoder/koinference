package io.github.lemcoder.koinference.benchmark.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What the run produced, as a table.
 *
 * Scrolls sideways rather than wrapping: eight numeric columns do not fit a phone, and a wrapped
 * cell makes two rows look like one. Medians per row — see [ResultsTable].
 */
@Composable
fun ResultsScreen(state: RunState.Finished, onDismiss: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Results", style = MaterialTheme.typography.titleLarge)

        if (state.failures.isNotEmpty()) {
            Text(
                text = state.failures.joinToString("\n"),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Column {
                HeaderRow()
                HorizontalDivider()
                LazyColumn { items(state.rows) { ResultRow(it) } }
            }
        }

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Cell("engine", ENGINE, bold = true)
        Cell("workload", WORKLOAD, bold = true)
        Cell("tok/s", NUMBER, bold = true)
        Cell("ttft ms", NUMBER, bold = true)
        Cell("tokens", NUMBER, bold = true)
        Cell("chunks", NUMBER, bold = true)
        Cell("pss mb", NUMBER, bold = true)
        Cell("note", NOTE, bold = true)
    }
}

@Composable
private fun ResultRow(row: BenchmarkRow) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Cell(row.engineId, ENGINE)
        Cell(row.workload, WORKLOAD)
        Cell(row.tokensPerSecond.oneDecimal(), NUMBER)
        Cell(row.ttftMs.oneDecimal(), NUMBER)
        Cell(row.tokens?.toString() ?: "—", NUMBER)
        Cell(row.chunks?.toString() ?: "—", NUMBER)
        Cell(row.peakPssMb.oneDecimal(), NUMBER)
        Cell(row.note ?: "", NOTE)
    }
}

@Composable
private fun Cell(text: String, width: Int, bold: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.width(width.dp),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** An em dash, not a zero: a missing measurement and a measured zero are different facts. */
private fun Double?.oneDecimal(): String = this?.let { "%.1f".format(it) } ?: "—"

private const val ENGINE = 80
private const val WORKLOAD = 140
private const val NUMBER = 64
private const val NOTE = 200
