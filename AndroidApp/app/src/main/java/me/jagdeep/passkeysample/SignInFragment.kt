package me.jagdeep.passkeysample

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.credentials.PendingGetCredentialRequest
import androidx.credentials.pendingGetCredentialRequest
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.jagdeep.passkeysample.databinding.FragmentSignInBinding

class SignInFragment : Fragment() {

    private var _binding: FragmentSignInBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SignInViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSignInBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // As soon as the server-prefetched request arrives, attach it to the username field.
        // The system will then show the passkey picker whenever the field is focused.
        viewLifecycleOwner.lifecycleScope.launch {
            val request = viewModel.pendingRequest.filterNotNull().first()
            binding.etUsername.pendingGetCredentialRequest = PendingGetCredentialRequest(request) { response ->
                viewModel.handleAutofillCredential(response.credential)
            }
        }

        binding.etUsername.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { viewModel.resetError() }
        })

        binding.btnSignIn.setOnClickListener {
            viewModel.signIn(binding.etUsername.text?.toString())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is SignInUiState.Idle -> {
                            binding.progressBar.isVisible = false
                            binding.btnSignIn.isEnabled = true
                            binding.tilUsername.isEnabled = true
                            binding.tvError.isVisible = false
                        }
                        is SignInUiState.Loading -> {
                            binding.progressBar.isVisible = true
                            binding.btnSignIn.isEnabled = false
                            binding.tilUsername.isEnabled = false
                            binding.tvError.isVisible = false
                        }
                        is SignInUiState.Success -> {
                            findNavController().navigate(
                                R.id.action_signIn_to_home,
                                bundleOf("username" to state.username)
                            )
                        }
                        is SignInUiState.Error -> {
                            binding.progressBar.isVisible = false
                            binding.btnSignIn.isEnabled = true
                            binding.tilUsername.isEnabled = true
                            binding.tvError.isVisible = true
                            binding.tvError.text = state.message
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
