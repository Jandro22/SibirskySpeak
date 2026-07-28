package com.sibirskyspeak.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Optional, best-effort online gloss assist. The app remains fully usable when
 * disabled, offline, rate-limited, or unavailable. MyMemory's no-key endpoint is
 * used only as a translation hint; it is never treated as authoritative content
 * and the learner can edit the result before starting practice.
 */
class OnlineGlossService {
    suspend fun lookupRussian(token: String): String? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(token.trim(), Charsets.UTF_8.name())
        val connection = (URL("https://api.mymemory.translated.net/get?q=$encoded&langpair=ru|en")
            .openConnection() as? HttpURLConnection) ?: return@withContext null
        try {
            connection.connectTimeout = 2_500
            connection.readTimeout = 2_500
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "SibirskySpeak/2.0 (free learner app)")
            if (connection.responseCode !in 200..299) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optJSONObject("responseData")
                ?.optString("translatedText")
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals(token, ignoreCase = true) }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
