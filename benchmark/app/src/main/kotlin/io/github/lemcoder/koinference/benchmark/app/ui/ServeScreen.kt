package io.github.lemcoder.koinference.benchmark.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lemcoder.koinference.benchmark.app.client.BackendProcess
import java.io.File

/**
 * Picks the one engine that serves over the network, and says where to reach it.
 *
 * One at a time, deliberately: two engines answering the same port is meaningless, and two models
 * resident at once would make either one's memory reading describe the pair.
 */
@Composable
fun ServeScreen(viewModel: BenchmarkViewModel) {
    val backends by viewModel.backends.collectAsState()
    val serving by viewModel.serving.collectAsState()
    var chosen by remember { mutableStateOf<BackendProcess?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Serves an OpenAI-compatible API on 0.0.0.0:8080 for the Python clients in " +
                "benchmark/analysis. No authentication: anyone who can reach this device can " +
                "drive the model.",
            style = MaterialTheme.typography.bodySmall,
        )

        backends.forEach { state ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = chosen == state.process,
                        onClick = { chosen = state.process },
                        enabled = state.runnable && serving is ServingState.Stopped,
                    )
                    Column {
                        Text(state.process.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = state.blockedReason
                                ?: state.selectedModel?.let { File(it).name }
                                ?: "no model",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.blockedReason != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        when (val current = serving) {
            is ServingState.Stopped -> Button(
                onClick = { chosen?.let(viewModel::startServing) },
                enabled = chosen != null && backends.first { it.process == chosen }.runnable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start server")
            }

            is ServingState.Starting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                Text("Loading the model on ${current.process.label}…")
            }

            is ServingState.Serving -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Serving ${current.modelName} on ${current.process.label}")
                Text(current.url, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "python benchmark/analysis/openai_bench.py --base-url ${current.url}",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = viewModel::stopServing, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop server")
                }
            }

            is ServingState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = viewModel::stopServing, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset")
                }
            }
        }
    }
}
