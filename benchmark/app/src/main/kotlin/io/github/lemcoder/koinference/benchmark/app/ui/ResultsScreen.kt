package io.github.lemcoder.koinference.benchmark.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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
 * What the run produced: speed and memory together.
 *
 * A card per row rather than a wide table. Eight numeric columns do not fit a phone, and the
 * previous version put memory off the right-hand edge behind a horizontal scroll — where it may as
 * well not have been recorded. On a phone, memory is often the number that decides whether a model
 * ships, so it gets equal billing.
 *
 * Every figure is a median over the measured iterations; see [ResultsTable].
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

        LazyColumn(
            modifier = Modifier.weight(1f).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.rows) { ResultCard(it) }
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
private fun ResultCard(row: BenchmarkRow) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.engineId, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = row.workload,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Figure("tok/s", row.tokensPerSecond.oneDecimal())
                Figure("ttft", row.ttftMs?.let { "${it.toInt()} ms" } ?: MISSING)
                Figure("tokens", row.tokens?.toString() ?: MISSING)
                Figure("chunks", row.chunks?.toString() ?: MISSING)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Read in the engine's own process, which is what the process split is for: this
                // is the model and its engine, not Compose and an HTTP server.
                Figure("peak pss", row.peakPssMb.megabytes())
                Figure("after load", row.weightsPssMb.megabytes())
                Figure("after run", row.afterRunPssMb.megabytes())
            }

            row.note?.takeIf { it.isNotBlank() }?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    // Red only for a failure: the harness's standing notes are remarks, and
                    // colouring them as errors teaches the reader to ignore the colour.
                    color = if (row.noteIsFailure) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** An em dash, not a zero: a missing measurement and a measured zero are different facts. */
private fun Double?.oneDecimal(): String = this?.let { "%.1f".format(it) } ?: MISSING

private fun Double?.megabytes(): String = this?.let { "${it.toInt()} MB" } ?: MISSING

private const val MISSING = "—"
