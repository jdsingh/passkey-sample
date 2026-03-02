package me.jagdeep.passkeysample.auth

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.serialization.json.Json

class PasskeyManager(private val context: Context) {
    private val TAG = "PasskeyManager"
    private val credentialManager = CredentialManager.create(context)
    private val prettyJson = Json { prettyPrint = true }

    private fun String.toPrettyJson(): String = try {
        prettyJson.encodeToString(prettyJson.parseToJsonElement(this))
    } catch (_: Exception) { this }

    suspend fun signIn(requestJson: String, activityContext: Context): String {
        Log.d(TAG, "signIn → CredentialManager.getCredential request:\n${requestJson.toPrettyJson()}")
        val getPublicKeyCredentialOption = GetPublicKeyCredentialOption(requestJson)
        val request = GetCredentialRequest(listOf(getPublicKeyCredentialOption))

        return try {
            val result = credentialManager.getCredential(activityContext, request)  // must be Activity context
            val credential = result.credential
            Log.d(TAG, "signIn ← CredentialManager.getCredential response: type=${credential.type}")

            if (credential is PublicKeyCredential) {
                Log.d(TAG, "signIn ← PublicKeyCredential authenticationResponseJson:\n${credential.authenticationResponseJson.toPrettyJson()}")
                credential.authenticationResponseJson
            } else {
                Log.e(TAG, "signIn ← unexpected credential type '${credential.type}'")
                throw Exception("Unexpected credential type: ${credential.type}")
            }
        } catch (e: NoCredentialException) {
            Log.w(TAG, "signIn ← NoCredentialException: no passkeys registered for this app", e)
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "signIn ← GetCredentialCancellationException: user dismissed the picker")
            throw e
        } catch (e: GetCredentialException) {
            Log.e(TAG, "signIn ← GetCredentialException type=${e.type} message=${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "signIn ← unexpected error", e)
            throw e
        }
    }

    @RequiresPermission(Manifest.permission.CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS)
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    suspend fun checkPasskeys(requestJson: String): Boolean {
        Log.d(TAG, "checkPasskeys → CredentialManager.prepareGetCredential request:\n${requestJson.toPrettyJson()}")
        val getPublicKeyCredentialOption = GetPublicKeyCredentialOption(requestJson)
        val request = GetCredentialRequest(listOf(getPublicKeyCredentialOption))

        return try {
            val preparationHandle = credentialManager.prepareGetCredential(request)
            val hasValue = preparationHandle
                .hasCredentialResults(PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL)
            Log.d(TAG, "checkPasskeys ← CredentialManager.prepareGetCredential response: hasPublicKeyCredentials=$hasValue")
            hasValue
        } catch (e: GetCredentialException) {
            Log.w(TAG, "checkPasskeys ← GetCredentialException type=${e.type}: treating as no credentials available", e)
            false
        }
    }
}
