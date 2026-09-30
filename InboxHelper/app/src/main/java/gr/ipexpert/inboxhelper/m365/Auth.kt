package gr.ipexpert.inboxhelper.m365

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import gr.ipexpert.inboxhelper.data.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A Microsoft 365 capability the user can switch on. Each one asks only for the Graph permissions it needs,
 * at the moment it is enabled (incremental consent).
 */
enum class Feature(val title: String, val why: String, val scopes: List<String>, val adminConsent: Boolean = false) {
    MAIL_READ("Outlook mail (Inbox + Sent)", "Read your mail and sent items to find unanswered requests, threads and history.", listOf("Mail.Read")),
    MAIL_SEND("Send via Outlook", "Send replies, reply-all, forwards and new mail from your account after you confirm.", listOf("Mail.Send")),
    TEAMS_CHAT("Teams chats", "Read your 1:1 and group chats as part of each conversation's context.", listOf("Chat.Read")),
    TEAMS_CHAT_SEND("Reply in Teams chats", "Post replies to Teams chats after you confirm.", listOf("ChatMessage.Send")),
    TEAMS_CHANNELS("Teams channels", "Read messages in the channels of teams you belong to (your organisation's admin may need to approve).",
        listOf("Team.ReadBasic.All", "Channel.ReadBasic.All", "ChannelMessage.Read.All"), adminConsent = true),
    TEAMS_CHANNEL_SEND("Reply in Teams channels", "Post replies in channel threads after you confirm.", listOf("ChannelMessage.Send")),
    CALENDAR("Calendar", "Use nearby meetings as context (e.g. a meeting about the same project).", listOf("Calendars.Read")),
}

data class Account(val id: String, val name: String, val email: String)

class AuthException(message: String, val needsSignIn: Boolean = false) : Exception(message)

object M365Config {
    const val REDIRECT_URI = "gr.ipexpert.inboxhelper://auth"
    private val BASE_SCOPES = listOf("openid", "profile", "offline_access", "User.Read")

    private lateinit var sp: SharedPreferences
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private fun changed() { _version.value = _version.value + 1 }

    fun init(ctx: Context) { sp = ctx.getSharedPreferences("m365", Context.MODE_PRIVATE) }

    var clientId: String
        get() = sp.getString("clientId", "") ?: ""
        set(v) { sp.edit().putString("clientId", v.trim()).apply(); changed() }
    /** "organizations" (any work/school account), "common", or a tenant id / domain. */
    var tenant: String
        get() = (sp.getString("tenant", "organizations") ?: "organizations").ifBlank { "organizations" }
        set(v) { sp.edit().putString("tenant", v.trim()).apply(); changed() }
    var historyMonths: Int
        get() = sp.getInt("historyMonths", 12)
        set(v) { sp.edit().putInt("historyMonths", v.coerceIn(1, 60)).apply(); changed() }
    /** Threads with activity in the last N days become live conversations; older ones are history for context. */
    var activeDays: Int
        get() = sp.getInt("activeDays", 21)
        set(v) { sp.edit().putInt("activeDays", v.coerceIn(3, 120)).apply(); changed() }
    var signature: String
        get() = sp.getString("signature", "") ?: ""
        set(v) { sp.edit().putString("signature", v).apply(); changed() }

    fun wants(f: Feature): Boolean = sp.getBoolean("want_${f.name}", f == Feature.MAIL_READ || f == Feature.MAIL_SEND || f == Feature.TEAMS_CHAT || f == Feature.TEAMS_CHAT_SEND)
    fun setWants(f: Feature, on: Boolean) { sp.edit().putBoolean("want_${f.name}", on).apply(); changed() }

    /** Scopes needed for everything the user switched on. */
    fun wantedScopes(): List<String> = (BASE_SCOPES + Feature.entries.filter { wants(it) }.flatMap { it.scopes }).distinct()

    // ---- token storage (refresh token encrypted with the Android Keystore)
    var grantedScopes: Set<String>
        get() = (sp.getString("granted", "") ?: "").split(' ').filter { it.isNotBlank() }.toSet()
        set(v) { sp.edit().putString("granted", v.joinToString(" ")).apply(); changed() }
    var refreshTokenEnc: String
        get() = sp.getString("rt", "") ?: ""
        set(v) { sp.edit().putString("rt", v).apply() }
    var account: Account?
        get() = sp.getString("account", null)?.let { try { val o = JSONObject(it); Account(o.getString("id"), o.optString("name"), o.optString("email")) } catch (_: Exception) { null } }
        set(v) { sp.edit().putString("account", v?.let { JSONObject().put("id", it.id).put("name", it.name).put("email", it.email).toString() }).apply(); changed() }

    fun has(f: Feature): Boolean = f.scopes.all { s -> grantedScopes.any { it.equals(s, ignoreCase = true) } }
    fun enabled(f: Feature): Boolean = wants(f) && has(f)
    val connected: Boolean get() = refreshTokenEnc.isNotBlank() && account != null

    // ---- pending PKCE login
    var pendingVerifier: String
        get() = sp.getString("pkce", "") ?: ""
        set(v) { sp.edit().putString("pkce", v).apply() }
    var pendingState: String
        get() = sp.getString("state", "") ?: ""
        set(v) { sp.edit().putString("state", v).apply() }

    fun clearTokens() {
        sp.edit().remove("rt").remove("granted").remove("account").remove("pkce").remove("state").apply()
        changed()
    }
}

/**
 * Microsoft identity platform, OAuth 2.0 authorization code flow with PKCE for a public mobile client.
 * The browser (Custom Tab) handles the Microsoft sign-in page, MFA and consent; the app never sees the password.
 */
object Auth {
    private var accessToken: String = ""
    private var accessExpiry: Long = 0
    private val lock = Mutex()
    private val rnd = SecureRandom()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private fun b64url(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val authority: String get() = "https://login.microsoftonline.com/${M365Config.tenant}/oauth2/v2.0"

    /** Opens Microsoft sign-in in a Custom Tab, asking for every scope currently wanted. */
    fun signIn(ctx: Context) {
        if (M365Config.clientId.isBlank()) throw AuthException("Enter the Application (client) ID first")
        val verifier = b64url(ByteArray(48).also { rnd.nextBytes(it) })
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = b64url(ByteArray(16).also { rnd.nextBytes(it) })
        M365Config.pendingVerifier = verifier
        M365Config.pendingState = state
        val url = "$authority/authorize?client_id=${enc(M365Config.clientId)}&response_type=code" +
            "&redirect_uri=${enc(M365Config.REDIRECT_URI)}&response_mode=query" +
            "&scope=${enc(M365Config.wantedScopes().joinToString(" "))}" +
            "&state=$state&code_challenge=$challenge&code_challenge_method=S256" +
            (if (!M365Config.connected) "&prompt=select_account" else "")
        _status.value = "Waiting for Microsoft sign-in…"
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tab.launchUrl(ctx, Uri.parse(url))
    }

    /** Called by the redirect activity with gr.ipexpert.inboxhelper://auth?code=…&state=… */
    suspend fun handleRedirect(uri: Uri): Account {
        uri.getQueryParameter("error")?.let { err ->
            val desc = uri.getQueryParameter("error_description") ?: err
            _status.value = "Sign-in failed: $desc"
            throw AuthException(desc, needsSignIn = true)
        }
        val code = uri.getQueryParameter("code") ?: throw AuthException("No authorization code returned")
        if (uri.getQueryParameter("state") != M365Config.pendingState) throw AuthException("Sign-in state mismatch — please try again")
        val verifier = M365Config.pendingVerifier
        val body = "client_id=${enc(M365Config.clientId)}&grant_type=authorization_code&code=${enc(code)}" +
            "&redirect_uri=${enc(M365Config.REDIRECT_URI)}&code_verifier=${enc(verifier)}" +
            "&scope=${enc(M365Config.wantedScopes().joinToString(" "))}"
        val json = tokenRequest(body)
        storeTokens(json)
        M365Config.pendingVerifier = ""; M365Config.pendingState = ""
        val me = Graph.getJson("/me?\$select=id,displayName,mail,userPrincipalName")
        val acc = Account(me.getString("id"), me.optString("displayName"), me.optString("mail").ifBlank { me.optString("userPrincipalName") })
        M365Config.account = acc
        _status.value = "Connected as ${acc.email}"
        return acc
    }

    private fun storeTokens(json: JSONObject) {
        accessToken = json.getString("access_token")
        accessExpiry = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000L
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { M365Config.refreshTokenEnc = Secrets.encrypt(it) }
        val granted = json.optString("scope").split(' ').filter { it.isNotBlank() }
            .map { it.removePrefix("https://graph.microsoft.com/") }.toSet()
        if (granted.isNotEmpty()) M365Config.grantedScopes = granted + setOf("openid", "profile", "offline_access")
    }

    private suspend fun tokenRequest(body: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("$authority/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (code !in 200..299) {
                val err = json.optString("error")
                val desc = json.optString("error_description").lineSequence().firstOrNull() ?: text.take(200)
                // invalid_grant = refresh token expired/revoked → user must sign in again.
                throw AuthException("Microsoft sign-in: $desc", needsSignIn = err == "invalid_grant" || err == "interaction_required")
            }
            json
        } finally { conn.disconnect() }
    }

    /** Valid access token, refreshing silently when needed. */
    suspend fun token(forceRefresh: Boolean = false): String = lock.withLock {
        if (!forceRefresh && accessToken.isNotBlank() && System.currentTimeMillis() < accessExpiry - 120_000) return@withLock accessToken
        val rt = Secrets.decrypt(M365Config.refreshTokenEnc)
        if (rt.isBlank()) throw AuthException("Not signed in to Microsoft 365", needsSignIn = true)
        val scopes = (M365Config.grantedScopes.ifEmpty { M365Config.wantedScopes().toSet() }).joinToString(" ")
        val body = "client_id=${enc(M365Config.clientId)}&grant_type=refresh_token&refresh_token=${enc(rt)}&scope=${enc(scopes)}"
        storeTokens(tokenRequest(body))
        accessToken
    }

    fun signOut() {
        accessToken = ""; accessExpiry = 0
        M365Config.clearTokens()
        _status.value = "Signed out"
    }
}
