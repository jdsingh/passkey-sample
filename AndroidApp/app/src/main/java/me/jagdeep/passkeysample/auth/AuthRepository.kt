package me.jagdeep.passkeysample.auth

import android.content.Context
import android.util.Log
import androidx.credentials.Credential
import androidx.credentials.PublicKeyCredential
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.jagdeep.passkeysample.network.ApiClient

class AuthRepository(private val context: Context) {
    private val TAG = "AuthRepository"
    private val passkeyManager = PasskeyManager(context)
    private val json = Json { ignoreUnknownKeys = true }

    // Fetches a challenge from the server. Returns (requestOptionsJson, challengeId).
    // The requestOptionsJson can be passed directly to GetPublicKeyCredentialOption.
    suspend fun generateOptions(username: String?): Result<Pair<String, String>> {
        Log.d(TAG, "generateOptions: username=${username ?: "<none>"}")
        return try {
            val optionsString = ApiClient.generateAuthOptions(username)
            val optionsElement = json.parseToJsonElement(optionsString).jsonObject
            val challengeId = optionsElement["challengeId"]?.jsonPrimitive?.content
            if (challengeId == null) {
                Log.e(TAG, "generateOptions: server response missing 'challengeId'")
                return Result.failure(Exception("No challengeId in server response"))
            }
            Log.d(TAG, "generateOptions: received challengeId=$challengeId")
            val requestOptions = JsonObject(optionsElement.filterKeys { it != "challengeId" })
            Result.success(Pair(requestOptions.toString(), challengeId))
        } catch (e: Exception) {
            Log.e(TAG, "generateOptions: failed", e)
            Result.failure(e)
        }
    }

    // Full flow for the Sign In button: fetches options, invokes CredentialManager, verifies.
    suspend fun signIn(username: String?): Result<String> {
        Log.d(TAG, "signIn: starting full flow, username=${username ?: "<none>"}")
        return try {
            val (requestOptionsJson, challengeId) = generateOptions(username).getOrThrow()
            Log.d(TAG, "signIn: invoking PasskeyManager with challengeId=$challengeId")
            val authResponseJson = passkeyManager.signIn(requestOptionsJson)
            Log.d(TAG, "signIn: auth response received, proceeding to verify")
            verifyResponse(authResponseJson, challengeId)
        } catch (e: Exception) {
            Log.e(TAG, "signIn: flow failed", e)
            Result.failure(e)
        }
    }

    // Used by the autofill callback: the credential has already been obtained by the system.
    suspend fun verifyCredential(credential: Credential, challengeId: String): Result<String> {
        Log.d(TAG, "verifyCredential: type=${credential.type} challengeId=$challengeId")
        return try {
            val authResponseJson = when (credential) {
                is PublicKeyCredential -> {
                    Log.d(TAG, "verifyCredential: extracting authenticationResponseJson")
                    credential.authenticationResponseJson
                }
                else -> {
                    Log.e(TAG, "verifyCredential: unsupported credential type '${credential.type}'")
                    throw Exception("Unsupported credential type: ${credential.type}")
                }
            }
            verifyResponse(authResponseJson, challengeId)
        } catch (e: Exception) {
            Log.e(TAG, "verifyCredential: failed", e)
            Result.failure(e)
        }
    }

    private suspend fun verifyResponse(authResponseJson: String, challengeId: String): Result<String> {
        Log.d(TAG, "verifyResponse: sending to server, challengeId=$challengeId")
        return try {
            val verifyString = ApiClient.verifyAuthentication(authResponseJson, challengeId)
            val verifyResult = json.parseToJsonElement(verifyString).jsonObject
            val verified = verifyResult["verified"]?.jsonPrimitive?.content?.toBoolean() ?: false
            if (!verified) {
                Log.e(TAG, "verifyResponse: server returned verified=false. Response: $verifyString")
                return Result.failure(Exception("Authentication failed"))
            }
            val username = verifyResult["username"]?.jsonPrimitive?.content
            if (username == null) {
                Log.e(TAG, "verifyResponse: server response missing 'username'. Response: $verifyString")
                throw Exception("No username in verification response")
            }
            Log.d(TAG, "verifyResponse: authentication successful, username=$username")
            Result.success(username)
        } catch (e: Exception) {
            Log.e(TAG, "verifyResponse: failed", e)
            Result.failure(e)
        }
    }
}
