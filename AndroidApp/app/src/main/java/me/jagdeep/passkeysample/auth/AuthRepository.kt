package me.jagdeep.passkeysample.auth

import android.content.Context
import androidx.credentials.Credential
import androidx.credentials.PublicKeyCredential
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.jagdeep.passkeysample.network.ApiClient

class AuthRepository(private val context: Context) {
    private val passkeyManager = PasskeyManager(context)
    private val json = Json { ignoreUnknownKeys = true }

    // Fetches a challenge from the server. Returns (requestOptionsJson, challengeId).
    // The requestOptionsJson can be passed directly to GetPublicKeyCredentialOption.
    suspend fun generateOptions(username: String?): Result<Pair<String, String>> {
        return try {
            val optionsString = ApiClient.generateAuthOptions(username)
            val optionsElement = json.parseToJsonElement(optionsString).jsonObject
            val challengeId = optionsElement["challengeId"]?.jsonPrimitive?.content
                ?: return Result.failure(Exception("No challengeId in server response"))
            val requestOptions = JsonObject(optionsElement.filterKeys { it != "challengeId" })
            Result.success(Pair(requestOptions.toString(), challengeId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Full flow for the Sign In button: fetches options, invokes CredentialManager, verifies.
    suspend fun signIn(username: String?): Result<String> {
        return try {
            val (requestOptionsJson, challengeId) = generateOptions(username).getOrThrow()
            val authResponseJson = passkeyManager.signIn(requestOptionsJson)
            verifyResponse(authResponseJson, challengeId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Used by the autofill callback: the credential has already been obtained by the system.
    suspend fun verifyCredential(credential: Credential, challengeId: String): Result<String> {
        return try {
            val authResponseJson = when (credential) {
                is PublicKeyCredential -> credential.authenticationResponseJson
                else -> throw Exception("Unsupported credential type: ${credential.type}")
            }
            verifyResponse(authResponseJson, challengeId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun verifyResponse(authResponseJson: String, challengeId: String): Result<String> {
        return try {
            val verifyString = ApiClient.verifyAuthentication(authResponseJson, challengeId)
            val verifyResult = json.parseToJsonElement(verifyString).jsonObject
            val verified = verifyResult["verified"]?.jsonPrimitive?.content?.toBoolean() ?: false
            if (!verified) return Result.failure(Exception("Authentication failed"))
            val username = verifyResult["username"]?.jsonPrimitive?.content
                ?: throw Exception("No username in verification response")
            Result.success(username)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
