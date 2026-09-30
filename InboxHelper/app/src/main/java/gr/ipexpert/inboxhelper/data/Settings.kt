package gr.ipexpert.inboxhelper.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets (the API key) with a hardware-backed Android Keystore key. */
object Secrets {
    private const val ALIAS = "inboxhelper_secret"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val (iv, data) = stored.split(":", limit = 2)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }
}

object Settings {
    const val DEFAULT_FAST_MODEL = "claude-haiku-4-5"
    const val DEFAULT_SMART_MODEL = "claude-sonnet-4-5"

    val knownApps = listOf(
        WatchedApp("com.google.android.apps.messaging", "Messages", "sms"),
        WatchedApp("com.samsung.android.messaging", "Samsung Messages", "sms"),
        WatchedApp("com.android.mms", "SMS", "sms"),
        WatchedApp("com.google.android.gm", "Gmail", "mail"),
        WatchedApp("com.microsoft.office.outlook", "Outlook", "mail"),
        WatchedApp("com.samsung.android.email.provider", "Samsung Email", "mail"),
        WatchedApp("com.whatsapp", "WhatsApp", "chat"),
        WatchedApp("com.whatsapp.w4b", "WhatsApp Business", "chat"),
        WatchedApp("com.viber.voip", "Viber", "chat"),
        WatchedApp("com.microsoft.teams", "Teams", "chat"),
        WatchedApp("com.facebook.orca", "Messenger", "chat"),
        WatchedApp("org.telegram.messenger", "Telegram", "chat"),
        WatchedApp("org.thoughtcrime.securesms", "Signal", "chat"),
        WatchedApp("com.slack", "Slack", "chat"),
    )

    fun appFor(pkg: String): WatchedApp? = knownApps.firstOrNull { it.pkg == pkg }

    private lateinit var sp: SharedPreferences
    private var cachedKey: String? = null

    /** Bumped on every change so Compose screens can react. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private fun changed() { _version.value = _version.value + 1 }

    private val _templates = MutableStateFlow<List<Template>>(emptyList())
    val templates: StateFlow<List<Template>> = _templates

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _templates.value = loadTemplates()
    }

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) { sp.edit().putString(k, v).apply(); changed() }
    private fun putB(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply(); changed() }
    private fun putI(k: String, v: Int) { sp.edit().putInt(k, v).apply(); changed() }

    // ---- AI ----
    var apiKey: String
        get() = cachedKey ?: Secrets.decrypt(str("apiKeyEnc", "")).also { cachedKey = it }
        set(v) { cachedKey = v.trim(); put("apiKeyEnc", Secrets.encrypt(v.trim())) }
    val hasKey: Boolean get() = apiKey.isNotBlank()

    var fastModel: String
        get() = str("fastModel", DEFAULT_FAST_MODEL).ifBlank { DEFAULT_FAST_MODEL }
        set(v) = put("fastModel", v.trim())
    var smartModel: String
        get() = str("smartModel", DEFAULT_SMART_MODEL).ifBlank { DEFAULT_SMART_MODEL }
        set(v) = put("smartModel", v.trim())
    /** Language for AI summaries / explanations: "gr" or "en". */
    var aiLang: String
        get() = str("aiLang", "gr")
        set(v) = put("aiLang", v)
    var autoAnalyze: Boolean
        get() = sp.getBoolean("autoAnalyze", true)
        set(v) = putB("autoAnalyze", v)
    var confidenceThreshold: Int
        get() = sp.getInt("confThreshold", 60)
        set(v) = putI("confThreshold", v.coerceIn(0, 100))
    var aboutMe: String
        get() = str("aboutMe", "")
        set(v) = put("aboutMe", v)
    var learningEnabled: Boolean
        get() = sp.getBoolean("learning", true)
        set(v) = putB("learning", v)

    /** Speech recognition language: "el-GR" or "en-US". */
    var voiceLang: String
        get() = str("voiceLang", "el-GR")
        set(v) = put("voiceLang", v)

    // ---- SLA (minutes, measured in business time when business hours are on) ----
    fun slaMinutes(level: String): Int = when (level) {
        Level.CRITICAL -> sp.getInt("sla_critical", 15)
        Level.HIGH -> sp.getInt("sla_high", 60)
        Level.MEDIUM -> sp.getInt("sla_medium", 240)
        Level.LOW -> sp.getInt("sla_low", 480)
        else -> 0
    }
    fun setSla(level: String, minutes: Int) = putI("sla_$level", minutes.coerceIn(1, 60 * 24 * 14))

    // ---- business hours ----
    var businessHoursOn: Boolean
        get() = sp.getBoolean("bhOn", false)
        set(v) = putB("bhOn", v)
    /** ISO days 1=Mon..7=Sun, stored as "12345". */
    var workDays: Set<Int>
        get() = str("workDays", "12345").mapNotNull { it.digitToIntOrNull() }.filter { it in 1..7 }.toSet()
        set(v) = put("workDays", v.sorted().joinToString(""))
    var workStartMin: Int
        get() = sp.getInt("workStart", 9 * 60)
        set(v) = putI("workStart", v.coerceIn(0, 24 * 60 - 1))
    var workEndMin: Int
        get() = sp.getInt("workEnd", 17 * 60)
        set(v) = putI("workEnd", v.coerceIn(1, 24 * 60))
    /** yyyy-MM-dd, comma separated. */
    var holidays: Set<String>
        get() = str("holidays", "").split(",").map { it.trim() }.filter { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }.toSet()
        set(v) = put("holidays", v.sorted().joinToString(","))

    // ---- notifications ----
    var notifyCritical: Boolean
        get() = sp.getBoolean("nCritical", true)
        set(v) = putB("nCritical", v)
    var notifySla: Boolean
        get() = sp.getBoolean("nSla", true)
        set(v) = putB("nSla", v)
    var notifyDeadline: Boolean
        get() = sp.getBoolean("nDeadline", true)
        set(v) = putB("nDeadline", v)
    var notifyEscalation: Boolean
        get() = sp.getBoolean("nEscalation", true)
        set(v) = putB("nEscalation", v)
    var morningBriefing: Boolean
        get() = sp.getBoolean("nBriefing", true)
        set(v) = putB("nBriefing", v)
    var briefingHour: Int
        get() = sp.getInt("briefingHour", 8)
        set(v) = putI("briefingHour", v.coerceIn(0, 23))
    var lastBriefingDay: String
        get() = str("lastBriefingDay", "")
        set(v) { sp.edit().putString("lastBriefingDay", v).apply() }

    /** Keys of alerts already sent, so each alert fires once. */
    fun alertSent(key: String): Boolean = sp.getStringSet("sentAlerts", emptySet())?.contains(key) == true
    fun markAlert(key: String) {
        val cur = sp.getStringSet("sentAlerts", emptySet())?.toMutableSet() ?: mutableSetOf()
        cur.add(key)
        val trimmed = if (cur.size > 800) cur.toList().takeLast(500).toSet() else cur
        sp.edit().putStringSet("sentAlerts", trimmed).apply()
    }

    // ---- privacy ----
    var retentionDays: Int
        get() = sp.getInt("retentionDays", 60)
        set(v) = putI("retentionDays", v.coerceIn(1, 3650))

    // ---- apps ----
    fun isAppEnabled(pkg: String): Boolean = appFor(pkg) != null && sp.getBoolean("app_$pkg", pkg != "com.slack")
    fun setAppEnabled(pkg: String, on: Boolean) = putB("app_$pkg", on)

    // ---- templates ----
    fun addTemplate(lang: String, text: String) =
        saveTemplates(listOf(Template("t" + System.currentTimeMillis(), lang, text.trim())) + _templates.value)
    fun removeTemplate(id: String) = saveTemplates(_templates.value.filterNot { it.id == id })

    private fun saveTemplates(list: List<Template>) {
        _templates.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("lang", it.lang).put("text", it.text)) }
        sp.edit().putString("templates", arr.toString()).apply()
    }

    private fun loadTemplates(): List<Template> {
        val raw = sp.getString("templates", null) ?: return defaultTemplates
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Template(it.getString("id"), it.optString("lang", "gr"), it.optString("text")) } }
        } catch (_: Exception) { defaultTemplates }
    }

    private val defaultTemplates = listOf(
        Template("d1", "gr", "Είμαι σε σύσκεψη, θα σε καλέσω σε λίγο."),
        Template("d2", "gr", "Το έλαβα, θα το ελέγξω και θα σου απαντήσω σήμερα."),
        Template("d3", "gr", "Έρχομαι, θα είμαι εκεί σε 20 λεπτά."),
        Template("d4", "gr", "Ευχαριστώ! Θα το στείλω μέχρι το τέλος της ημέρας."),
        Template("d5", "en", "I'm in a meeting, I'll call you back shortly."),
        Template("d6", "en", "Received, I'll check it and get back to you today."),
        Template("d7", "en", "On my way, I'll be there in 20 minutes."),
        Template("d8", "en", "Thanks! I'll send it by end of day."),
    )
}
