package me.jagdeep.passkeysample.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    private const val BASE_URL = "https://passkey-sample-e9304.web.app"

    suspend fun generateAuthOptions(username: String?): String = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/api/generate-authentication-options")
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
            connection.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val error = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                throw Exception("HTTP $responseCode: $error")
            }
            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun verifyAuthentication(responseJson: String, challengeId: String): String = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/api/verify-authentication")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val body = """{"response":$responseJson,"challengeId":"$challengeId"}"""
            connection.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val error = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                throw Exception("HTTP $responseCode: $error")
            }
            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
