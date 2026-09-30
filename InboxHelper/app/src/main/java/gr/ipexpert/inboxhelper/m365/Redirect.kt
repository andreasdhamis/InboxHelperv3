package gr.ipexpert.inboxhelper.m365

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import gr.ipexpert.inboxhelper.MainActivity
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.index.Index
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Receives gr.ipexpert.inboxhelper://auth?code=… from the Microsoft sign-in page. */
class AuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri != null) {
            M365Session.scope.launch {
                try {
                    val acc = Auth.handleRedirect(uri)
                    Repo.log("", "user", "m365_connected", "${acc.email}; scopes: ${M365Config.grantedScopes.joinToString(" ")}")
                    SyncEngine.scheduleBackground(applicationContext)
                } catch (e: Exception) {
                    Repo.log("", "system", "m365_sign_in_failed", e.message ?: "")
                }
            }
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, "m365"))
        finish()
    }
}

object M365Session {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun health(): List<SourceHealth> {
        val running = SyncEngine.progress.value.running
        val defs = listOf(
            Triple(SyncEngine.MAIL_INBOX, "Outlook Inbox", Feature.MAIL_READ),
            Triple(SyncEngine.MAIL_SENT, "Outlook Sent Items", Feature.MAIL_READ),
            Triple(SyncEngine.TEAMS_CHATS, "Teams chats", Feature.TEAMS_CHAT),
            Triple(SyncEngine.TEAMS_CHANNELS, "Teams channels", Feature.TEAMS_CHANNELS),
            Triple(SyncEngine.CALENDAR, "Calendar", Feature.CALENDAR),
        )
        return defs.map { (key, title, f) ->
            val s = Index.state(key)
            val connected = M365Config.connected && M365Config.enabled(f)
            val status = when {
                !M365Config.connected -> "not connected"
                !M365Config.wants(f) -> "off"
                !M365Config.has(f) -> "permission needed"
                else -> s.status
            }
            SourceHealth(key, title, connected, status, s.lastSync, s.discovered, s.indexed, s.failed, s.error, running && s.status == "syncing")
        }
    }

    /** Disconnects: forgets tokens and, if asked, deletes everything imported from Microsoft 365. */
    fun disconnect(deleteData: Boolean) {
        Auth.signOut()
        if (deleteData) {
            listOf("outlook", "teams_chat", "teams_channel", "calendar").forEach { Index.deleteSource(it) }
            Index.clearStates("mail:"); Index.clearStates("teams:"); Index.clearStates("calendar")
            Repo.deleteBySource("m365|")
        }
        Repo.log("", "user", "m365_disconnected", if (deleteData) "data deleted" else "data kept")
    }
}
