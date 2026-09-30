@file:OptIn(ExperimentalLayoutApi::class)

package gr.ipexpert.inboxhelper.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.engine.Filter
import gr.ipexpert.inboxhelper.engine.IntelEngine
import gr.ipexpert.inboxhelper.engine.Sort
import gr.ipexpert.inboxhelper.m365.M365Config
import gr.ipexpert.inboxhelper.m365.SyncEngine
import gr.ipexpert.inboxhelper.voice.Drafts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Top-level navigation state shared by the screens. */
class Nav {
    var tab by mutableIntStateOf(0)                 // 0 today, 1 inbox, 2 assistant, 3 more
    var openConv by mutableStateOf<String?>(null)
    var item by mutableStateOf<String?>(null)       // source viewer (index item id)
    var more by mutableStateOf("")                  // "", contacts, contact:<name>, analytics, tasks, templates, audit, settings, m365, search, topics, topic:<label>
    var filter by mutableStateOf(Filter())
    var sort by mutableStateOf(Sort.PRIORITY)

    fun showInbox(f: Filter, s: Sort = Sort.PRIORITY) { filter = f; sort = s; openConv = null; item = null; tab = 1 }
    fun open(id: String) { item = null; openConv = id }
    fun go(route: String) { openConv = null; item = null; tab = 3; more = route }
}

@Composable
fun AppRoot(resumeTick: Int, deepLinkConv: String?, openRoute: String?, onDeepLinkHandled: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = remember { Nav() }
    val convs by Repo.conversations.collectAsState()
    val corrections by Repo.corrections.collectAsState()
    val settingsVersion by Settings.version.collectAsState()
    val m365Version by M365Config.version.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    // Recomputed when data, settings, corrections change, and every 30 s so timers stay live.
    val intel = remember(convs, corrections, now, settingsVersion) { IntelEngine.computeAll(convs, now, corrections) }
    val hasAccess = remember(resumeTick, now) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }

    // Near-real-time Microsoft 365: sync on open and every 3 minutes while the app is visible.
    LaunchedEffect(resumeTick, m365Version) {
        if (M365Config.connected) {
            SyncEngine.requestSync("app open", 500)
            while (true) { delay(180_000); SyncEngine.requestSync("foreground", 0) }
        }
    }
    LaunchedEffect(deepLinkConv, openRoute) {
        if (deepLinkConv != null) nav.open(deepLinkConv)
        if (openRoute != null) nav.go(openRoute)
        if (deepLinkConv != null || openRoute != null) onDeepLinkHandled()
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) scope.launch { VoiceAgent.handle(ctx, text, intel, nav) }
    }
    fun listen() {
        VoiceSession.open = true
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Settings.voiceLang)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, if (Settings.voiceLang.startsWith("el")) "Πες μου τι να κάνω…" else "What should I do?")
        try { speech.launch(i) } catch (e: Exception) {
            Toast.makeText(ctx, "Speech recognition isn't available on this phone (install/enable Google voice typing).", Toast.LENGTH_LONG).show()
        }
    }
    fun confirmPending() {
        val p = VoiceSession.pending ?: return
        scope.launch {
            VoiceSession.busy = true
            try { SendActions.execute(ctx, p); Drafts.set(p.convId, ""); VoiceSession.reply = "Sent." }
            catch (e: Exception) { VoiceSession.reply = "Not sent: ${e.message}" }
            VoiceSession.pending = null; VoiceSession.busy = false
        }
    }

    BackHandler(enabled = VoiceSession.open || nav.item != null || nav.openConv != null || nav.more.isNotEmpty() || nav.tab != 0) {
        when {
            VoiceSession.open -> VoiceSession.open = false
            nav.item != null -> nav.item = null
            nav.openConv != null -> nav.openConv = null
            nav.more.startsWith("contact:") -> nav.more = "contacts"
            nav.more.startsWith("topic:") -> nav.more = "topics"
            nav.more.isNotEmpty() -> nav.more = ""
            else -> nav.tab = 0
        }
    }

    Scaffold(
        containerColor = Ground,
        floatingActionButton = {
            if (!VoiceSession.open) FloatingActionButton(onClick = { listen() }, containerColor = Teal, contentColor = Color.White) {
                Text("🎤", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        },
        bottomBar = {
            Column {
                PlayerBar()
                if (VoiceSession.open) VoicePanel(onMic = { listen() }, onConfirm = { confirmPending() })
                else if (nav.openConv == null && nav.item == null) {
                    NavigationBar(containerColor = Paper) {
                        NavigationBarItem(nav.tab == 0, { nav.tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Today") })
                        NavigationBarItem(nav.tab == 1, { nav.tab = 1 }, { Icon(Icons.Default.Email, null) }, label = { Text("Inbox") })
                        NavigationBarItem(nav.tab == 2, { nav.tab = 2 }, { Icon(Icons.Default.Star, null) }, label = { Text("Assistant") })
                        NavigationBarItem(nav.tab == 3, { nav.tab = 3; nav.more = "" }, { Icon(Icons.Default.Menu, null) }, label = { Text("More") })
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize(), contentAlignment = Alignment.TopStart) {
            val open = nav.openConv
            val item = nav.item
            when {
                item != null -> ItemScreen(item, nav)
                open != null -> DetailScreen(open, intel, now, nav)
                nav.tab == 0 -> DashboardScreen(intel, now, hasAccess, nav)
                nav.tab == 1 -> InboxScreen(intel, now, nav)
                nav.tab == 2 -> AssistantScreen(intel, nav)
                else -> MoreScreen(intel, now, hasAccess, nav)
            }
        }
    }
}
