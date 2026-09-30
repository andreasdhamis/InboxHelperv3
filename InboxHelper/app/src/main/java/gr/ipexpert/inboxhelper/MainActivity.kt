package gr.ipexpert.inboxhelper

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import gr.ipexpert.inboxhelper.ui.AppRoot
import gr.ipexpert.inboxhelper.ui.AppTheme
import gr.ipexpert.inboxhelper.voice.Speaker
import gr.ipexpert.inboxhelper.work.Analyzer
import gr.ipexpert.inboxhelper.work.Notifier

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_OPEN = "open_route" }

    /** Bumped on every resume so the UI re-checks notification access and syncs. */
    private val resumeTick = mutableIntStateOf(0)
    /** Conversation to open when launched from one of our alerts. */
    private val deepLink = mutableStateOf<String?>(null)
    private val openRoute = mutableStateOf<String?>(null)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Speaker.init(this)
        readIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            AppTheme {
                AppRoot(
                    resumeTick = resumeTick.intValue,
                    deepLinkConv = deepLink.value,
                    openRoute = openRoute.value,
                    onDeepLinkHandled = { deepLink.value = null; openRoute.value = null },
                )
            }
        }
    }

    private fun readIntent(i: Intent?) {
        i?.getStringExtra(Notifier.EXTRA_CONV)?.let { deepLink.value = it }
        i?.getStringExtra(EXTRA_OPEN)?.let { openRoute.value = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue += 1
        Analyzer.sweep()
    }
}
