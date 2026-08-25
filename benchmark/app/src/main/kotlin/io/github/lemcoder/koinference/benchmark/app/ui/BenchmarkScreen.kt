package io.github.lemcoder.koinference.benchmark.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lemcoder.koinference.benchmark.app.client.BackendProcess
import java.io.File

/**
 * The backends this build ships, selectable, with the run button pinned to the bottom.
 *
 * An engine this device cannot run is shown and disabled rather than hidden, with the reason: "why
 * is llama.cpp missing" is exactly the question a hidden row would create.
 */
@Composable
fun BenchmarkScreen(viewModel: BenchmarkViewModel) {
    val backends by viewModel.backends.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val run by viewModel.run.collectAsState()
    val running = run is RunState.Running

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(backends) { state ->
                BackendCard(
                    state = state,
                    checked = state.process in selected,
                    enabled = !running,
                    onToggle = { viewModel.toggle(state.process) },
                    onSelectModel = { viewModel.selectModel(state.process, it) },
                )
            }

            if (run is RunState.Failed) {
                item {
                    Text(
                        text = (run as RunState.Failed).message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        HorizontalDivider()

        Column(modifier = Modifier.padding(16.dp)) {
            if (running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    Text(
                        text = (run as RunState.Running).line,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Button(
                onClick = viewModel::runBenchmark,
                enabled = !running && selected.isNotEmpty() &&
                    backends.any { it.process in selected && it.runnable },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(if (running) "Running…" else "Run benchmark")
            }
        }
    }
}

@Composable
private fun BackendCard(
    state: BackendState,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onSelectModel: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggle() },
                    enabled = enabled && state.runnable,
                )
                Column {
                    Text(state.process.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = state.engineId ?: "connecting…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            state.blockedReason?.let { reason ->
                Text(
                    text = reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (state.models.size > 1) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.models.forEach { path ->
                        FilterChip(
                            selected = path == state.selectedModel,
                            onClick = { onSelectModel(path) },
                            enabled = enabled,
                            label = { Text(File(path).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            } else {
                state.selectedModel?.let { path ->
                    Text(
                        text = File(path).name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
