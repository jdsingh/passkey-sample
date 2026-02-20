package me.jagdeep.passkeysample

import android.app.Application
import android.util.Log
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

    // Called immediately when the Fragment view is ready. Invokes CredentialManager
    // proactively — if passkeys are available the system bottom-sheet appears; if not,
    // we transition to NoPasskeys so the manual form is revealed.
    fun queryPasskeys() {
        Log.d(TAG, "queryPasskeys: proactively invoking CredentialManager")
        viewModelScope.launch {
            _uiState.value = AutoSignInUiState.Checking
            val result = repository.signIn(null)
            result.fold(
                onSuccess = { username ->
                    Log.d(TAG, "queryPasskeys: passkey sign-in succeeded, username=$username")
                    _uiState.value = AutoSignInUiState.Success(username)
                },
                onFailure = { e ->
                    when (e) {
                        is NoCredentialException -> {
                            Log.w(TAG, "queryPasskeys: no passkeys registered for this app — showing manual form")
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
    fun signIn(username: String?) {
        Log.d(TAG, "signIn: manual button triggered, username=${username?.takeIf { it.isNotBlank() } ?: "<none>"}")
        viewModelScope.launch {
            _uiState.value = AutoSignInUiState.Loading
            val result = repository.signIn(username?.takeIf { it.isNotBlank() })
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
