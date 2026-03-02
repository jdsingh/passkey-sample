package me.jagdeep.passkeysample.auth

import android.content.Context
import android.util.Log
import androidx.credentials.Credential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
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
    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun String.toPrettyJson(): String = try {
        prettyJson.encodeToString(prettyJson.parseToJsonElement(this))
    } catch (_: Exception) { this }

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
            Log.d(TAG, "generateOptions: challengeId=$challengeId")
            val requestOptions = JsonObject(optionsElement.filterKeys { it != "challengeId" })
            Log.d(TAG, "generateOptions: options passed to CredentialManager:\n${requestOptions.toString().toPrettyJson()}")
            // prepareGetCredential pre-warms the CredentialManager for faster picker display.
            // Only available on API 34+; silently skipped on older versions.
            // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            //    passkeyManager.checkPasskeys(requestOptions.toString())
            // }
            Result.success(Pair(requestOptions.toString(), challengeId))
        } catch (e: Exception) {
            Log.e(TAG, "generateOptions: failed", e)
            Result.failure(e)
        }
    }

    // Full flow for the Sign In button: fetches options, invokes CredentialManager, verifies.
    // getCredential: caller-supplied lambda that invokes CredentialManager with an Activity
    // context and returns the credential response.
    suspend fun signIn(
        username: String?,
        getCredential: suspend (GetCredentialRequest) -> GetCredentialResponse
    ): Result<String> {
        Log.d(TAG, "signIn: starting full flow, username=${username ?: "<none>"}")
        return try {
            val (requestOptionsJson, challengeId) = generateOptions(username).getOrThrow()
            Log.d(TAG, "signIn: options received, invoking CredentialManager via lambda")
            val authResponseJson = passkeyManager.signIn(requestOptionsJson, getCredential)
            Log.d(TAG, "signIn: auth response received, proceeding to verify: $authResponseJson")
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
                    Log.d(TAG, "verifyCredential: authenticationResponseJson:\n${credential.authenticationResponseJson.toPrettyJson()}")
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
                Log.e(TAG, "verifyResponse: server returned verified=false:\n${verifyString.toPrettyJson()}")
                return Result.failure(Exception("Authentication failed"))
            }
            val username = verifyResult["username"]?.jsonPrimitive?.content
            if (username == null) {
                Log.e(TAG, "verifyResponse: server response missing 'username':\n${verifyString.toPrettyJson()}")
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
