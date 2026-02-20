package me.jagdeep.passkeysample.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential

class PasskeyManager(private val context: Context) {
    private val credentialManager = CredentialManager.create(context)

    suspend fun signIn(requestJson: String): String {
        val getPublicKeyCredentialOption = GetPublicKeyCredentialOption(requestJson)
        val request = GetCredentialRequest(listOf(getPublicKeyCredentialOption))

        val result = credentialManager.getCredential(context, request)
        val credential = result.credential

        if (credential is PublicKeyCredential) {
            return credential.authenticationResponseJson
        }
        throw Exception("Unexpected credential type: ${credential.type}")
    }
}
