package qinme.aiperson

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var pendingCharacterId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        UiPrefs.init(this)
        BehaviorSettings.init(this)
        Speaker.init(this)
        Notifier.ensureChannels(this)
        pendingCharacterId = intent?.getStringExtra("characterId")

        ProactiveScheduler.ensureScheduled(this)
        lifecycleScope.launch { MemoryDecay.run() }

        enableEdgeToEdge()
        setContent {
            AIPersonTheme {
                AppRoot(
                    openCharacterId = pendingCharacterId,
                    onOpened = { pendingCharacterId = null }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppForeground.visible = true
    }

    override fun onPause() {
        super.onPause()
        AppForeground.visible = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingCharacterId = intent.getStringExtra("characterId")
    }
}
