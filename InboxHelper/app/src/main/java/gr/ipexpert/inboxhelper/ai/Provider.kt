package gr.ipexpert.inboxhelper.ai

import gr.ipexpert.inboxhelper.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiRequest(
    val system: String,
    val user: String,
    val model: String,
    val maxTokens: Int,
    val temperature: Double? = null,
)

class AiException(message: String, val retryable: Boolean) : Exception(message)

/** Provider abstraction: add another implementation (e.g. a company gateway) without touching the services. */
interface AiProvider {
    val name: String
    suspend fun complete(req: AiRequest): String
}

/** Anthropic Claude Messages API. The key is read from encrypted settings and never shown in the UI. */
class ClaudeProvider : AiProvider {
    override val name = "Anthropic Claude"

    override suspend fun complete(req: AiRequest): String = withContext(Dispatchers.IO) {
        val key = Settings.apiKey
        if (key.isBlank()) throw AiException("Add your Claude API key in Settings", retryable = false)
        val body = JSONObject()
            .put("model", req.model)
            .put("max_tokens", req.maxTokens)
            .put("system", req.system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", req.user)))
        req.temperature?.let { body.put("temperature", it) }

        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", key)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (_: Exception) { text.take(200) }
                // 429 rate limit, 5xx and 529 overloaded are worth retrying; 4xx config errors are not.
                throw AiException("Claude API $code: $msg", retryable = code == 429 || code >= 500)
            }
            val content = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            sb.toString()
        } catch (e: AiException) {
            throw e
        } catch (e: java.io.IOException) {
            throw AiException("Network error: ${e.message ?: "no connection"}", retryable = true)
        } finally {
            conn.disconnect()
        }
    }
}

object Ai {
    /** Swap here to use a different provider. */
    val provider: AiProvider = ClaudeProvider()
}
