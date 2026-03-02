package me.jagdeep.passkeysample

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.jagdeep.passkeysample.auth.AuthRepository

sealed class AutoSignInUiState {
    object Checking   : AutoSignInUiState()
    object NoPasskeys : AutoSignInUiState()
    object Loading    : AutoSignInUiState()
    data class Success(val username: String) : AutoSignInUiState()
    data class Error(val message: String)   : AutoSignInUiState()
}

class AutoSignInViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "AutoSignInViewModel"
    private val repository = AuthRepository(application)

    private val _uiState = MutableStateFlow<AutoSignInUiState>(AutoSignInUiState.Checking)
    val uiState: StateFlow<AutoSignInUiState> = _uiState

    // Shown in the UI to explain detection limitations or absence of passkeys.
    private val _infoMessage = MutableStateFlow<String?>(null)
    val infoMessage: StateFlow<String?> = _infoMessage

    // Called immediately when the Fragment view is ready.
    // On API 34+: silently checks for passkeys first; only prompts if found.
    // On API < 34: cannot check silently, so shows an info message and prompts directly.
    fun queryPasskeys(getCredential: suspend (GetCredentialRequest) -> GetCredentialResponse) {
        Log.d(TAG, "queryPasskeys: starting")
        viewModelScope.launch {
            _uiState.value = AutoSignInUiState.Checking

            val optionsResult = repository.generateOptions(null)
            if (optionsResult.isFailure) {
                Log.e(TAG, "queryPasskeys: failed to generate options", optionsResult.exceptionOrNull())
                _uiState.value = AutoSignInUiState.Error(
                    optionsResult.exceptionOrNull()?.message ?: "Failed to connect to server"
                )
                return@launch
            }
            val (requestOptionsJson, challengeId) = optionsResult.getOrThrow()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: use prepareGetCredential to silently check before showing the picker
                val hasPasskeys = repository.checkPasskeys(requestOptionsJson)
                if (!hasPasskeys) {
                    Log.w(TAG, "queryPasskeys: no passkeys found on this device")
                    _infoMessage.value = "No passkeys detected on this device"
                    _uiState.value = AutoSignInUiState.NoPasskeys
                    return@launch
                }
                Log.d(TAG, "queryPasskeys: passkeys detected, proceeding with auto-prompt")
            } else {
                // API < 34: prepareGetCredential unavailable, prompt directly
                Log.d(TAG, "queryPasskeys: API < 34, passkey detection unavailable — prompting directly")
                _infoMessage.value = "Passkey detection is not available on Android 13 and below"
            }

            val result = repository.signInWithOptions(requestOptionsJson, challengeId, getCredential)
            result.fold(
                onSuccess = { username ->
                    Log.d(TAG, "queryPasskeys: sign-in succeeded, username=$username")
                    _infoMessage.value = null
                    _uiState.value = AutoSignInUiState.Success(username)
                },
                onFailure = { e ->
                    when (e) {
                        is NoCredentialException -> {
                            Log.w(TAG, "queryPasskeys: no passkeys registered — showing manual form")
                            _uiState.value = AutoSignInUiState.NoPasskeys
                        }
                        is GetCredentialCancellationException -> {
                            Log.d(TAG, "queryPasskeys: user dismissed the picker — showing manual form")
                            _uiState.value = AutoSignInUiState.NoPasskeys
                        }
                        else -> {
                            Log.e(TAG, "queryPasskeys: unexpected error", e)
                            _uiState.value = AutoSignInUiState.Error(e.message ?: "Sign-in failed")
                        }
                    }
                }
            )
        }
    }

    // Called by the manual "Sign In with Passkey" button once the username field is visible.
    fun signIn(username: String?, getCredential: suspend (GetCredentialRequest) -> GetCredentialResponse) {
        Log.d(TAG, "signIn: manual button triggered, username=${username?.takeIf { it.isNotBlank() } ?: "<none>"}")
        viewModelScope.launch {
            _uiState.value = AutoSignInUiState.Loading
            val result = repository.signIn(username?.takeIf { it.isNotBlank() }, getCredential)
            result.fold(
                onSuccess = { name ->
                    Log.d(TAG, "signIn: success, username=$name")
                    _uiState.value = AutoSignInUiState.Success(name)
                },
                onFailure = { e ->
                    val message = when (e) {
                        is NoCredentialException -> {
                            Log.e(TAG, "signIn: no passkeys found for username=${username?.takeIf { it.isNotBlank() } ?: "<none>"}", e)
                            "No passkeys found for this account"
                        }
                        is GetCredentialCancellationException -> {
                            Log.d(TAG, "signIn: user cancelled the picker")
                            "Sign-in cancelled"
                        }
                        else -> {
                            Log.e(TAG, "signIn: failed", e)
                            e.message ?: "Sign-in failed"
                        }
                    }
                    _uiState.value = AutoSignInUiState.Error(message)
                }
            )
        }
    }

    fun resetError() {
        if (_uiState.value is AutoSignInUiState.Error) {
            Log.d(TAG, "resetError: clearing error, returning to NoPasskeys state")
            _uiState.value = AutoSignInUiState.NoPasskeys
        }
    }
}
