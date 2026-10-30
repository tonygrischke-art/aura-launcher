package com.aetheria.aura.net

import android.content.Context
import com.aetheria.aura.actions.AuraAction
import com.aetheria.aura.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.io.OutputStreamWriter
import java.io.BufferedReader
import java.net.URL
import java.nio.charset.StandardCharsets

object HermesClient {

    /** Returns the Hermes bridge endpoint. Configure via buildConfigField in build.gradle.kts or override at runtime. */
    private fun getEndpoint(context: Context): String {
        // Priority: 1) Runtime override via shared prefs (not implemented yet), 2) BuildConfig, 3) Default for dev
        return try {
            BuildConfig.HERMES_ENDPOINT
        } catch (e: Exception) {
            "http://localhost:8766/command"
        }
    }

    suspend fun send(context: Context, command: String): AuraAction {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var result: AuraAction = AuraAction.Unknown
            try {
                val endpoint = getEndpoint(context)
                val url = URL(endpoint)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 10000 // 10s connect timeout
                connection.readTimeout = 10000   // 10s read timeout
                connection.doOutput = true

                // POST {"command": text}
                val jsonInputString = """{"command":"$command"}"""
                val outputStream = connection.outputStream
                val writer = OutputStreamWriter(outputStream, StandardCharsets.UTF_8)
                writer.write(jsonInputString)
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    val reader = BufferedReader(inputStream.reader(StandardCharsets.UTF_8))
                    val response = reader.readLine()
                    reader.close()

                    // Parse the response into AuraAction
                    result = AuraAction.parse(response ?: "{}")
                } else {
                    // Non-200 response sets error state
                    result = AuraAction.Unknown
                }
            } catch (e: Exception) {
                // Any exception sets the orb to error (represented as Unknown for now)
                result = AuraAction.Unknown
            } finally {
                connection?.disconnect()
            }
            result
        }
    }
}
