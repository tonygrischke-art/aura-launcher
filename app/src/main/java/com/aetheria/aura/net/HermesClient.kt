package com.aetheria.aura.net

import android.content.Context
import android.content.SharedPreferences
import com.aetheria.aura.actions.AuraAction
import com.aetheria.aura.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.net.HttpURLConnection
import java.io.OutputStreamWriter
import java.io.BufferedReader
import java.net.URL
import java.nio.charset.StandardCharsets

object HermesClient {
    private const val DEFAULT_HOST = "http://10.0.2.2:8766/command"

    private const val PREFS_NAME = "aura_settings"
    private const val KEY_ENDPOINT = "hermes_endpoint"
    private const val KEY_USER_PROFILE = "user_profile"

    /** Persist a user-chosen Hermes endpoint. */
    fun setEndpoint(context: Context, endpoint: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENDPOINT, endpoint)
            .apply()
    }

    /** Returns the persisted endpoint, or the BuildConfig default on first run. */
    fun getStoredEndpoint(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENDPOINT, null) ?: BuildConfig.HERMES_ENDPOINT
    }

    /** Returns the persisted user profile name, or empty string if not set. */
    fun getStoredUserProfile(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USER_PROFILE, null) ?: ""
    }

    /** Persist a user-chosen profile name. */
    fun setUserProfile(context: Context, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USER_PROFILE, name)
            .apply()
    }

    /** Returns true if the endpoint uses cleartext HTTP on a non-localhost host. */
    fun isCleartextNonLocalhost(endpoint: String): Boolean {
        if (!endpoint.startsWith("http://", ignoreCase = true)) return false
        try {
            val host = java.net.URL(endpoint).host.lowercase()
            return host != "localhost" && host != "127.0.0.1"
        } catch (e: Exception) {
            return false
        }
    }

    private val host: String = try {
        BuildConfig.HERMES_HOST
    } catch (e: Exception) {
        DEFAULT_HOST
    }

    suspend fun send(command: String): AuraAction {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var result: AuraAction = AuraAction.Unknown
            try {
                val url = URL(host)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 10000 // 10s connect timeout
                connection.readTimeout = 60000   // 60s read timeout (Hermes may take time)
                connection.doOutput = true

                // POST {"command": text}
                val jsonInputString = "{\"command\":\"$command\"}"
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
                    Log.w("AuraRouter", "Hermes HTTP $responseCode for cmd='$command'")
                }
            } catch (e: Exception) {
                Log.e("AuraRouter", "Hermes request failed for cmd='$command' host=$host", e)
                // Return Unknown but caller will handle the error message
                result = AuraAction.Unknown
            } finally {
                connection?.disconnect()
            }
            result
        }
    }

    suspend fun send(context: Context, command: String): AuraAction {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var result: AuraAction = AuraAction.Unknown
            try {
                val endpoint = getStoredEndpoint(context)
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
