@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package gr.ipexpert.inboxhelper.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.ipexpert.inboxhelper.data.AnalysisState
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.engine.Clock
import gr.ipexpert.inboxhelper.index.Index
import gr.ipexpert.inboxhelper.m365.Auth
import gr.ipexpert.inboxhelper.m365.Feature
import gr.ipexpert.inboxhelper.m365.M365Config
import gr.ipexpert.inboxhelper.m365.M365Session
import gr.ipexpert.inboxhelper.m365.SyncEngine

@Composable
fun M365Screen(nav: Nav) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = M365Config.version.collectAsState().value
    @Suppress("UNUSED_VARIABLE") val t = SyncEngine.tick.collectAsState().value
    val progress by SyncEngine.progress.collectAsState()
    val authStatus by Auth.status.collectAsState()
    val convs by Repo.conversations.collectAsState()
    var clientId by remember { mutableStateOf(M365Config.clientId) }
    var tenant by remember { mutableStateOf(M365Config.tenant) }
    var signature by remember { mutableStateOf(M365Config.signature) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val connected = M365Config.connected
    val acc = M365Config.account

    fun signIn() = try { Auth.signIn(ctx) } catch (e: Exception) { Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.more = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column { SectionLabel("Outlook · Teams"); Text("Microsoft 365", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold) }
        }

        // ---------------- connection
        Panel(bg = if (connected) TealSoft else Paper) {
            if (connected && acc != null) {
                Text("Connected ✓", color = Teal, fontWeight = FontWeight.ExtraBold)
                Text("${acc.name} · ${acc.email}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Microsoft 365 stays the source of truth; this app keeps a searchable index and AI analysis on your phone.", fontSize = 12.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { signIn() }) { Text("Re-authorise") }
                    TextButton(onClick = { confirmDisconnect = !confirmDisconnect }) { Text("Disconnect…") }
                }
                if (confirmDisconnect) Panel(bg = Color(0xFFF7E1D3)) {
                    Text("Disconnect Microsoft 365?", fontWeight = FontWeight.Bold)
                    Text("To fully revoke access, also remove “Inbox Helper” at myapps.microsoft.com → Manage apps / or ask your admin.", fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { M365Session.disconnect(false); confirmDisconnect = false }) { Text("Keep data") }
                        Button(onClick = { M365Session.disconnect(true); confirmDisconnect = false }) { Text("Delete imported data") }
                    }
                }
            } else {
                Text("Sign in with Microsoft", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                Text("One-time setup: register “Inbox Helper” in Microsoft Entra ID (see the guide in the README) and paste its Application (client) ID. " +
                    "You sign in on Microsoft's page — the app never sees your password.", fontSize = 13.sp, color = Muted)
                OutlinedTextField(clientId, { clientId = it; M365Config.clientId = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Application (client) ID") }, placeholder = { Text("00000000-0000-0000-0000-000000000000") })
                OutlinedTextField(tenant, { tenant = it; M365Config.tenant = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Tenant (organizations, or your tenant ID / domain)") })
                Text("Redirect URI to register: ${M365Config.REDIRECT_URI}", fontSize = 12.sp, color = Muted)
                Button(onClick = { signIn() }, enabled = clientId.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Sign in with Microsoft") }
            }
            if (authStatus.isNotBlank()) Text(authStatus, fontSize = 12.sp, color = if (authStatus.contains("fail", true)) UrgentText else Muted)
        }

        // ---------------- permissions
        Panel {
            SectionLabel("Data the AI may use · permissions")
            Feature.entries.forEachIndexed { idx, f ->
                if (idx > 0) HorizontalDivider(color = Line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(f.title, fontWeight = FontWeight.SemiBold)
                        Text(f.why, fontSize = 12.sp, color = Muted)
                        Text("Graph: " + f.scopes.joinToString(", ") + if (f.adminConsent) " · admin approval" else "", fontSize = 11.sp, color = Muted)
                        if (connected && M365Config.wants(f)) {
                            if (M365Config.has(f)) Text("Granted ✓", fontSize = 12.sp, color = Teal, fontWeight = FontWeight.Bold)
                            else TextButton(onClick = { signIn() }) { Text("Grant permission") }
                        }
                    }
                    Switch(checked = M365Config.wants(f), onCheckedChange = { M365Config.setWants(f, it) })
                }
            }
            Text("Turning a switch on asks Microsoft for that permission the next time you sign in / tap Grant. Everything else keeps working if one is not granted.",
                fontSize = 12.sp, color = Muted)
        }

        // ---------------- sync progress + health
        if (progress.running) Panel(bg = TealSoft) {
            Text("Microsoft 365 synchronisation", fontWeight = FontWeight.Bold, color = Teal)
            Text(progress.phase, fontSize = 13.sp)
            if (progress.discovered > 0) {
                val pct = (progress.indexed * 100 / progress.discovered.coerceAtLeast(1)).coerceIn(0, 100)
                Text("Discovered: ${progress.discovered} · Indexed: ${progress.indexed} · Remaining: ${(progress.discovered - progress.indexed).coerceAtLeast(0)}", fontSize = 13.sp)
                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp))
                Text("$pct% — you can keep using the app.", fontSize = 12.sp, color = Muted)
            } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Panel {
            SectionLabel("Sync health")
            val pending = convs.count { it.id.startsWith("m365|") && (it.analysisState == AnalysisState.PENDING || it.analysisState == AnalysisState.RUNNING) }
            val failedAi = convs.count { it.id.startsWith("m365|") && it.analysisState == AnalysisState.FAILED }
            val syncFailures = Index.failureCount()
            M365Session.health().forEach { h ->
                val healthy = h.status == "healthy"
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Pill(h.status.uppercase(), when { healthy -> TealSoft; h.status in setOf("error", "no permission", "permission needed") -> Color(0xFFF7E1D3); else -> LowBg },
                            when { healthy -> Teal; h.status in setOf("error", "no permission", "permission needed") -> UrgentText; else -> Muted })
                    }
                    if (h.lastSync > 0) Text("Last sync: ${Clock.duration(System.currentTimeMillis() - h.lastSync)} ago", fontSize = 12.sp, color = Muted)
                    if (h.key.startsWith("mail:") && h.discovered > 0) {
                        val missing = (h.discovered - h.indexed).coerceAtLeast(0)
                        Text("In Microsoft 365 (history window): ${h.discovered} · Indexed: ${h.indexed}" + if (missing > 0) " · Not yet indexed: $missing" else " · complete",
                            fontSize = 12.sp, color = if (missing > 0 && !h.running) UrgentText else Muted)
                    } else if (h.indexed > 0) Text("Indexed: ${h.indexed}", fontSize = 12.sp, color = Muted)
                    if (h.failed > 0) Text("Failed items: ${h.failed}", fontSize = 12.sp, color = UrgentText)
                    if (h.error.isNotBlank() && !healthy) Text(h.error, fontSize = 12.sp, color = UrgentText)
                }
            }
            HorizontalDivider(color = Line)
            KeyValue("Pending AI analysis", pending.toString())
            KeyValue("AI analysis failed", failedAi.toString(), if (failedAi > 0) UrgentText else Ink)
            KeyValue("Items needing retry", syncFailures.toString(), if (syncFailures > 0) UrgentText else Ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = connected && !progress.running, onClick = { SyncEngine.requestSync("manual", 0) }) { Text("Sync now") }
                OutlinedButton(enabled = connected && !progress.running, onClick = { SyncEngine.resetCheckpoints(); SyncEngine.scheduleBackground(ctx, full = true) }) { Text("Full re-sync (fill gaps)") }
            }
            Text("Full re-sync re-reads everything in the history window and fills any gaps; existing AI analysis is kept.", fontSize = 12.sp, color = Muted)
        }

        // ---------------- options
        Panel {
            SectionLabel("History & sending")
            Text("Import history from the last", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 3, 6, 12, 24).forEach { m ->
                    FilterChip(M365Config.historyMonths == m, { M365Config.historyMonths = m }, { Text(if (m < 12) "$m months" else "${m / 12} year" + if (m > 12) "s" else "") }, colors = tealChip())
                }
            }
            Text("Treat threads as live conversations if active in the last", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(7, 14, 21, 30, 60).forEach { d -> FilterChip(M365Config.activeDays == d, { M365Config.activeDays = d; SyncEngine.buildConversations() }, { Text("$d days") }, colors = tealChip()) }
            }
            Text("Older communication is kept as searchable history and used as context for replies.", fontSize = 12.sp, color = Muted)
            OutlinedTextField(signature, { signature = it; M365Config.signature = it }, Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("Email signature (added once to replies sent from the app)") },
                placeholder = { Text("Best regards,\nAndreas Damis\nipexpert") })
            Text("Microsoft Graph doesn't expose Outlook signatures, so paste yours here once.", fontSize = 12.sp, color = Muted)
        }
    }
}
