package io.github.lemcoder.koinference.benchmark.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.Factory
import androidx.lifecycle.ViewModel
import io.github.lemcoder.koinference.benchmark.app.ui.BenchmarkApp
import io.github.lemcoder.koinference.benchmark.app.ui.BenchmarkViewModel

/**
 * The whole UI, and it holds no model.
 *
 * Everything that loads weights lives in another process behind a binder, which is what keeps this
 * process's memory out of any measurement — see `IBackendService`.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: BenchmarkViewModel by lazy {
        ViewModelProvider(
            this,
            object : Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BenchmarkViewModel(applicationContext) as T
            },
        )[BenchmarkViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BenchmarkApp(viewModel) }
    }
}
