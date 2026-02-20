package me.jagdeep.passkeysample

import android.app.Application
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
    private val repository = AuthRepository(application)

    private val _uiState = MutableStateFlow<AutoSignInUiState>(AutoSignInUiState.Checking)
    val uiState: StateFlow<AutoSignInUiState> = _uiState

    // Called immediately when the Fragment view is ready. Invokes CredentialManager
    // proactively — if passkeys are available the system bottom-sheet appears; if not,
    // we transition to NoPasskeys so the manual form is revealed.
    fun queryPasskeys() {
        viewModelScope.launch {
            _uiState.value = AutoSignInUiState.Checking
            val result = repository.signIn(null)
            _uiState.value = result.fold(
                onSuccess = { AutoSignInUiState.Success(it) },
                onFailure = { e ->
                    when (e) {
                        is NoCredentialException,
                        is GetCredentialCancellationException -> AutoSignInUiState.NoPasskeys
                        else -> AutoSignInUiState.Error(e.message ?: "Sign-in failed")
                    }
                }
            )
        }
    }

    // Called by the manual "Sign In with Passkey" button once the username field is visible.
    fun signIn(username: String?) {
        viewModelScope.launch {
            _uiState.value = AutoSignInUiState.Loading
            val result = repository.signIn(username?.takeIf { it.isNotBlank() })
            _uiState.value = result.fold(
                onSuccess = { AutoSignInUiState.Success(it) },
                onFailure = { e ->
                    val message = when (e) {
                        is NoCredentialException -> "No passkeys found for this account"
                        is GetCredentialCancellationException -> "Sign-in cancelled"
                        else -> e.message ?: "Sign-in failed"
                    }
                    AutoSignInUiState.Error(message)
                }
            )
        }
    }

    fun resetError() {
        if (_uiState.value is AutoSignInUiState.Error) {
            _uiState.value = AutoSignInUiState.NoPasskeys
        }
    }
}
