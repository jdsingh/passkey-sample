package me.jagdeep.passkeysample.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    private const val TAG = "ApiClient"
    private const val BASE_URL = "https://passkey-sample-e9304.web.app"

    suspend fun generateAuthOptions(username: String?): String = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/api/generate-authentication-options")
        Log.d(TAG, "generateAuthOptions → POST $url  username=${username ?: "<none>"}")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val body = if (username != null) {
                """{"username":"$username"}"""
            } else {
                """{}"""
            }
            Log.d(TAG, "generateAuthOptions request body: $body")
            connection.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = connection.responseCode
            Log.d(TAG, "generateAuthOptions response code: $responseCode")
            if (responseCode !in 200..299) {
                val error = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                Log.e(TAG, "generateAuthOptions HTTP error $responseCode: $error")
                throw Exception("HTTP $responseCode: $error")
            }
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            Log.d(TAG, "generateAuthOptions response: $response")
            response
        } catch (e: Exception) {
            Log.e(TAG, "generateAuthOptions failed", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }

    suspend fun verifyAuthentication(responseJson: String, challengeId: String): String = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/api/verify-authentication")
        Log.d(TAG, "verifyAuthentication → POST $url  challengeId=$challengeId")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val body = """{"response":$responseJson,"challengeId":"$challengeId"}"""
            Log.d(TAG, "verifyAuthentication request body length: ${body.length} chars")
            connection.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = connection.responseCode
            Log.d(TAG, "verifyAuthentication response code: $responseCode")
            if (responseCode !in 200..299) {
                val error = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                Log.e(TAG, "verifyAuthentication HTTP error $responseCode: $error")
                throw Exception("HTTP $responseCode: $error")
            }
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            Log.d(TAG, "verifyAuthentication response: $response")
            response
        } catch (e: Exception) {
            Log.e(TAG, "verifyAuthentication failed", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
