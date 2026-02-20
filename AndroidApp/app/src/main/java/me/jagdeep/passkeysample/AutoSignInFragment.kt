package me.jagdeep.passkeysample

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import me.jagdeep.passkeysample.databinding.FragmentAutoSignInBinding

class AutoSignInFragment : Fragment() {

    private var _binding: FragmentAutoSignInBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AutoSignInViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAutoSignInBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Immediately query CredentialManager. If passkeys exist the system bottom-sheet
        // appears; if not, state transitions to NoPasskeys and the form is revealed.
        viewModel.queryPasskeys()

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
                        is AutoSignInUiState.Checking -> {
                            binding.progressBar.isVisible = true
                            binding.tilUsername.isVisible = false
                            binding.btnSignIn.isVisible = false
                            binding.tvError.isVisible = false
                        }
                        is AutoSignInUiState.NoPasskeys -> {
                            binding.progressBar.isVisible = false
                            binding.tilUsername.isVisible = true
                            binding.btnSignIn.isVisible = true
                            binding.tilUsername.isEnabled = true
                            binding.btnSignIn.isEnabled = true
                            binding.tvError.isVisible = false
                        }
                        is AutoSignInUiState.Loading -> {
                            binding.progressBar.isVisible = true
                            binding.btnSignIn.isEnabled = false
                            binding.tilUsername.isEnabled = false
                            binding.tvError.isVisible = false
                        }
                        is AutoSignInUiState.Success -> {
                            findNavController().navigate(
                                R.id.action_autoSignIn_to_home,
                                bundleOf("username" to state.username)
                            )
                        }
                        is AutoSignInUiState.Error -> {
                            binding.progressBar.isVisible = false
                            binding.tilUsername.isVisible = true
                            binding.tilUsername.isEnabled = true
                            binding.btnSignIn.isVisible = true
                            binding.btnSignIn.isEnabled = true
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
