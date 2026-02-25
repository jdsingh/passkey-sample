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

class PasskeyManager(private val context: Context) {
    private val TAG = "PasskeyManager"
    private val credentialManager = CredentialManager.create(context)

    suspend fun signIn(requestJson: String): String {
        Log.d(TAG, "signIn: invoking CredentialManager")
        val getPublicKeyCredentialOption = GetPublicKeyCredentialOption(requestJson)
        val request = GetCredentialRequest(listOf(getPublicKeyCredentialOption))

        return try {
            val result = credentialManager.getCredential(context, request)
            val credential = result.credential
            Log.d(TAG, "signIn: credential received, type=${credential.type}")

            if (credential is PublicKeyCredential) {
                Log.d(TAG, "signIn: PublicKeyCredential obtained successfully")
                credential.authenticationResponseJson
            } else {
                Log.e(TAG, "signIn: unexpected credential type '${credential.type}'")
                throw Exception("Unexpected credential type: ${credential.type}")
            }
        } catch (e: NoCredentialException) {
            Log.w(TAG, "signIn: no passkeys available for this app (NoCredentialException)", e)
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "signIn: user cancelled the credential picker")
            throw e
        } catch (e: GetCredentialException) {
            Log.e(TAG, "signIn: GetCredentialException type=${e.type} message=${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "signIn: unexpected error", e)
            throw e
        }
    }

    @RequiresPermission(Manifest.permission.CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS)
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    suspend fun checkPasskeys(requestJson: String): Boolean {
        Log.d(TAG, "checkPasskeys: invoking CredentialManager")
        val getPublicKeyCredentialOption = GetPublicKeyCredentialOption(requestJson)

        val request = GetCredentialRequest(listOf(getPublicKeyCredentialOption))

        try {
            val preparationHandle = credentialManager.prepareGetCredential(request)
            Log.d(TAG, "checkPasskeys: passkeys available")

            val hasValue = preparationHandle
                .hasCredentialResults(PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL)
            Log.d(TAG, "checkPasskeys: hasValue=$hasValue")
            return hasValue
        } catch (e: GetCredentialException) {
            return false
        }
    }
}
