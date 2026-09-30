package gr.ipexpert.inboxhelper.m365

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GraphException(message: String, val code: Int, val retryable: Boolean, val permission: Boolean = false, val gone: Boolean = false) : Exception(message)

/**
 * Microsoft Graph HTTP client.
 * - Adds the bearer token, refreshes once on 401.
 * - Throttling: honours Retry-After on 429/503/504, otherwise exponential backoff (2s → 64s), max 6 attempts.
 * - At most 4 concurrent requests (Graph's per-app mailbox limit is also 4).
 * - 410 Gone on a delta link → caller restarts a full sync for that source.
 */
object Graph {
    const val BASE = "https://graph.microsoft.com/v1.0"
    private val gate = Semaphore(4)

    @Volatile var throttledUntil: Long = 0
        private set

    private data class Resp(val code: Int, val text: String, val retryAfter: Long)

    private suspend fun raw(method: String, pathOrUrl: String, body: String?, headers: Map<String, String>, token: String): Resp =
        withContext(Dispatchers.IO) {
            val url = if (pathOrUrl.startsWith("http")) pathOrUrl else BASE + pathOrUrl
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000; readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val ra = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 0L
                Resp(code, text, ra)
            } finally { conn.disconnect() }
        }

    suspend fun request(method: String, pathOrUrl: String, body: String? = null, headers: Map<String, String> = emptyMap()): String {
        var attempt = 0
        var refreshed = false
        while (true) {
            attempt++
            val wait = throttledUntil - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            val resp = try {
                gate.withPermit { raw(method, pathOrUrl, body, headers, Auth.token()) }
            } catch (e: IOException) {
                if (attempt >= 6) throw GraphException("Network error: ${e.message}", 0, retryable = true)
                delay(2_000L shl minOf(attempt, 5)); continue
            }
            when {
                resp.code in 200..299 -> return resp.text
                resp.code == 401 && !refreshed -> { refreshed = true; Auth.token(forceRefresh = true) }
                resp.code == 429 || resp.code == 503 || resp.code == 504 || resp.code == 502 -> {
                    if (attempt >= 6) throw GraphException("Microsoft 365 is throttling requests (HTTP ${resp.code})", resp.code, retryable = true)
                    val ms = if (resp.retryAfter > 0) resp.retryAfter * 1000 else (2_000L shl minOf(attempt, 5))
                    throttledUntil = System.currentTimeMillis() + ms
                    delay(ms)
                }
                resp.code == 500 && attempt < 3 -> delay(3_000L * attempt)
                else -> {
                    val msg = try { JSONObject(resp.text).getJSONObject("error").let { it.optString("code") + ": " + it.optString("message") } } catch (_: Exception) { resp.text.take(200) }
                    throw GraphException("Graph ${resp.code} — $msg", resp.code, retryable = resp.code >= 500,
                        permission = resp.code == 403, gone = resp.code == 410 || msg.contains("SyncStateNotFound", true) || msg.contains("resyncRequired", true))
                }
            }
        }
    }

    suspend fun getJson(pathOrUrl: String, headers: Map<String, String> = emptyMap()): JSONObject =
        JSONObject(request("GET", pathOrUrl, null, headers).ifBlank { "{}" })

    suspend fun postJson(path: String, body: JSONObject): JSONObject {
        val t = request("POST", path, body.toString())
        return if (t.isBlank()) JSONObject() else try { JSONObject(t) } catch (_: Exception) { JSONObject() }
    }

    suspend fun getText(path: String, headers: Map<String, String> = emptyMap()): String = request("GET", path, null, headers)
}
