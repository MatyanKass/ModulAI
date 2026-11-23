package com.modulai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modulai.app.ui.AgeGateScreen
import com.modulai.app.ui.MainScaffold
import com.modulai.app.ui.ModulAiTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as ModulAiApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ModulAiTheme {
                // null until the stored value is read, so the gate never flashes.
                val confirmed by container.ageConfirmed.collectAsStateWithLifecycle(initialValue = null)
                val scope = rememberCoroutineScope()
                when (confirmed) {
                    null -> Unit
                    false -> AgeGateScreen(
                        onConfirm = { scope.launch { container.confirmAge() } },
                        onExit = { finish() },
                    )
                    true -> MainScaffold(container)
                }
            }
        }
    }
}
