package com.aetheria.aura.net

import com.aetheria.aura.actions.AuraAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object HermesClient {
    const val AURA_HOST = "http://localhost:8766/command"

    /**
     * Sends a command to Hermes and returns the parsed AuraAction.
     * Throws exceptions on failure so caller can set status to "Hermes unreachable".
     */
    suspend fun send(command: String): AuraAction {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(AURA_HOST)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 10_000  // 10s connect timeout
                connection.readTimeout = 60_000     // 60s read timeout
                connection.doOutput = true

                // Build JSON body with JSONObject
                val jsonBody = JSONObject().put("command", command)
                val jsonInputString = jsonBody.toString()

                connection.outputStream.use { outputStream ->
                    outputStream.write(jsonInputString.toByteArray(StandardCharsets.UTF_8))
                    outputStream.flush()
                }

                val responseCode = connection.responseCode

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // Read entire response with readText()
                    val response = InputStreamReader(connection.inputStream, StandardCharsets.UTF_8).readText()
                    // Parse the response into AuraAction
                    AuraAction.parse(response)
                } else {
                    // Non-200 response - throw to propagate failure
                    throw RuntimeException("Hermes HTTP $responseCode")
                }
            } catch (e: Exception) {
                // Let failures propagate
                throw e
            } finally {
                connection?.disconnect()
            }
        }
    }
}