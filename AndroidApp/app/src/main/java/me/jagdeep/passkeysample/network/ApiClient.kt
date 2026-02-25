package me.jagdeep.passkeysample.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    private const val TAG = "ApiClient"
    private const val BASE_URL = "https://passkey-sample-e9304.web.app"
    private val prettyJson = Json { prettyPrint = true }

    private fun String.toPrettyJson(): String = try {
        prettyJson.encodeToString(prettyJson.parseToJsonElement(this))
    } catch (_: Exception) { this }

    suspend fun generateAuthOptions(username: String?): String = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/api/generate-authentication-options")
        val body = if (username != null) """{"username":"$username"}""" else """{}"""
        Log.d(TAG, "generateAuthOptions → POST $url\n${body.toPrettyJson()}")

        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val error = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                Log.e(TAG, "generateAuthOptions ← HTTP $responseCode error:\n${error.toPrettyJson()}")
                throw Exception("HTTP $responseCode: $error")
            }
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            Log.d(TAG, "generateAuthOptions ← HTTP $responseCode:\n${response.toPrettyJson()}")
            response
        } catch (e: Exception) {
            Log.e(TAG, "generateAuthOptions ← failed", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }

    suspend fun verifyAuthentication(responseJson: String, challengeId: String): String = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/api/verify-authentication")
        val body = """{"response":$responseJson,"challengeId":"$challengeId"}"""
        Log.d(TAG, "verifyAuthentication → POST $url\n${body.toPrettyJson()}")

        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val error = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                Log.e(TAG, "verifyAuthentication ← HTTP $responseCode error:\n${error.toPrettyJson()}")
                throw Exception("HTTP $responseCode: $error")
            }
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            Log.d(TAG, "verifyAuthentication ← HTTP $responseCode:\n${response.toPrettyJson()}")
            response
        } catch (e: Exception) {
            Log.e(TAG, "verifyAuthentication ← failed", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
