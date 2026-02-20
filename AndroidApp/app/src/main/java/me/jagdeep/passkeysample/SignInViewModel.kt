package me.jagdeep.passkeysample

import android.app.Application
import androidx.credentials.Credential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.jagdeep.passkeysample.auth.AuthRepository

sealed class SignInUiState {
    object Idle : SignInUiState()
    object Loading : SignInUiState()
    data class Success(val username: String) : SignInUiState()
    data class Error(val message: String) : SignInUiState()
}

class SignInViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AuthRepository(application)

    private val _uiState = MutableStateFlow<SignInUiState>(SignInUiState.Idle)
    val uiState: StateFlow<SignInUiState> = _uiState

    // Null until the server returns a challenge; the fragment observes this to set
    // PendingGetCredentialRequest on the username field for autofill.
    private val _pendingRequest = MutableStateFlow<GetCredentialRequest?>(null)
    val pendingRequest: StateFlow<GetCredentialRequest?> = _pendingRequest

    // Held privately so the autofill callback can pair the credential with its challenge.
    private var autofillChallengeId: String? = null

    init {
        prefetchOptions()
    }

    private fun prefetchOptions() {
        viewModelScope.launch {
            repository.generateOptions(null).onSuccess { (requestJson, challengeId) ->
                autofillChallengeId = challengeId
                _pendingRequest.value = GetCredentialRequest(
                    listOf(GetPublicKeyCredentialOption(requestJson))
                )
            }
            // Silently ignore failures — the Sign In button flow will try again independently.
        }
    }

    // Called by the PendingGetCredentialRequest autofill callback.
    fun handleAutofillCredential(credential: Credential) {
        val challengeId = autofillChallengeId ?: return
        viewModelScope.launch {
            _uiState.value = SignInUiState.Loading
            val result = repository.verifyCredential(credential, challengeId)
            _uiState.value = result.fold(
                onSuccess = { SignInUiState.Success(it) },
                onFailure = { SignInUiState.Error(it.message ?: "Sign-in failed") }
            )
        }
    }

    // Called by the Sign In button — runs the full independent flow.
    fun signIn(username: String?) {
        viewModelScope.launch {
            _uiState.value = SignInUiState.Loading
            val result = repository.signIn(username?.takeIf { it.isNotBlank() })
            _uiState.value = result.fold(
                onSuccess = { SignInUiState.Success(it) },
                onFailure = { SignInUiState.Error(it.message ?: "Sign-in failed") }
            )
        }
    }

    fun resetError() {
        if (_uiState.value is SignInUiState.Error) {
            _uiState.value = SignInUiState.Idle
        }
    }
}
