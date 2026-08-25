package io.github.lemcoder.koinference.benchmark.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Two screens, as asked for: run a suite here, or serve one engine over the network.
 *
 * Tabs rather than a navigation library — there are two destinations and a results screen that
 * belongs to one of them, which is not enough to earn a dependency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkApp(viewModel: BenchmarkViewModel) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            var tab by remember { mutableStateOf(0) }
            val run by viewModel.run.collectAsState()

            Scaffold(
                topBar = { TopAppBar(title = { Text("koinference") }) },
            ) { padding ->
                Column(modifier = Modifier.padding(padding)) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Benchmark") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Serve") })
                    }

                    when {
                        // The results table takes the screen when a run finishes, and hands it back
                        // when dismissed: a table and a run button on one screen would compete for
                        // the same space on a phone.
                        tab == 0 && run is RunState.Finished ->
                            ResultsScreen(
                                state = run as RunState.Finished,
                                onDismiss = viewModel::dismissResults,
                            )

                        tab == 0 -> BenchmarkScreen(viewModel)
                        else -> ServeScreen(viewModel)
                    }
                }
            }
        }
    }
}
