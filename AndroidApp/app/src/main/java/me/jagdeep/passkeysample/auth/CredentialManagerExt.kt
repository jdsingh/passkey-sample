package me.jagdeep.passkeysample.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse

/**
 * Gets a credential using the Credential Manager API.
 *
 * @param activity The activity to use for the get credential request.
 * @param request The get credential request.
 * @return The get credential response.
 */
suspend fun getCredential(
    activity: Activity,
    request: GetCredentialRequest
): GetCredentialResponse {
    val credentialManager = CredentialManager.create(activity)
    return credentialManager.getCredential(activity, request)
}
