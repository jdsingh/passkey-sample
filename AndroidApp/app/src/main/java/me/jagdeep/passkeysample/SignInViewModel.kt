package me.jagdeep.passkeysample

import android.app.Application
import android.util.Log
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
    private val TAG = "SignInViewModel"
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
        Log.d(TAG, "prefetchOptions: fetching challenge for autofill")
        viewModelScope.launch {
            repository.generateOptions(null).fold(
                onSuccess = { (requestJson, challengeId) ->
                    Log.d(TAG, "prefetchOptions: challenge ready, challengeId=$challengeId")
                    autofillChallengeId = challengeId
                    _pendingRequest.value = GetCredentialRequest(
                        listOf(GetPublicKeyCredentialOption(requestJson))
                    )
                },
                onFailure = { e ->
                    Log.e(TAG, "prefetchOptions: failed to fetch challenge — autofill unavailable, manual flow still works", e)
                    // Silently ignore — the Sign In button flow will try again independently.
                }
            )
        }
    }

    // Called by the PendingGetCredentialRequest autofill callback.
    fun handleAutofillCredential(credential: Credential) {
        val challengeId = autofillChallengeId
        if (challengeId == null) {
            Log.e(TAG, "handleAutofillCredential: credential received but no autofillChallengeId stored — dropping")
            return
        }
        Log.d(TAG, "handleAutofillCredential: verifying autofill credential, challengeId=$challengeId")
        viewModelScope.launch {
            _uiState.value = SignInUiState.Loading
            val result = repository.verifyCredential(credential, challengeId)
            result.fold(
                onSuccess = { username ->
                    Log.d(TAG, "handleAutofillCredential: success, username=$username")
                    _uiState.value = SignInUiState.Success(username)
                },
                onFailure = { e ->
                    Log.e(TAG, "handleAutofillCredential: verification failed", e)
                    _uiState.value = SignInUiState.Error(e.message ?: "Sign-in failed")
                }
            )
        }
    }

    // Called by the Sign In button — runs the full independent flow.
    fun signIn(username: String?) {
        Log.d(TAG, "signIn: button triggered, username=${username?.takeIf { it.isNotBlank() } ?: "<none>"}")
        viewModelScope.launch {
            _uiState.value = SignInUiState.Loading
            val result = repository.signIn(username?.takeIf { it.isNotBlank() })
            result.fold(
                onSuccess = { name ->
                    Log.d(TAG, "signIn: success, username=$name")
                    _uiState.value = SignInUiState.Success(name)
                },
                onFailure = { e ->
                    Log.e(TAG, "signIn: failed", e)
                    _uiState.value = SignInUiState.Error(e.message ?: "Sign-in failed")
                }
            )
        }
    }

    fun resetError() {
        if (_uiState.value is SignInUiState.Error) {
            Log.d(TAG, "resetError: clearing error state")
            _uiState.value = SignInUiState.Idle
        }
    }
}
