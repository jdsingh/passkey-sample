// SimpleWebAuthn browser methods
const { startRegistration, startAuthentication, browserSupportsWebAuthn } = SimpleWebAuthnBrowser;

// Check WebAuthn support
if (!browserSupportsWebAuthn()) {
  alert('Your browser does not support WebAuthn. Please use a modern browser.');
}

// Helper to show results
function showResults(section, data) {
  const resultsSection = document.getElementById('results');
  if (resultsSection) {
    resultsSection.style.display = 'block';
  }
  
  const element = document.getElementById(section);
  if (element) {
    element.textContent = JSON.stringify(data, null, 2);
  }
}

function showMessage(message, isError = false) {
  const messageEl = document.getElementById('result-message');
  if (messageEl) {
    messageEl.innerHTML = `<div class="message ${isError ? 'error' : 'success'}">${message}</div>`;
  }
}

// Get selected algorithms
function getSelectedAlgorithms() {
  const checkboxes = document.querySelectorAll('input[name="algorithms"]:checked');
  return Array.from(checkboxes).map(cb => parseInt(cb.value, 10));
}

// Registration flow
const signupForm = document.getElementById('signup-form');
if (signupForm) {
  signupForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const submitBtn = document.getElementById('submit-btn');
    submitBtn.disabled = true;
    submitBtn.textContent = 'Creating passkey...';

    try {
      // Gather form data
      const formData = {
        username: document.getElementById('username').value,
        customRpName: document.getElementById('rpName').value,
        customRpID: document.getElementById('rpID').value,
        attestationType: document.getElementById('attestationType').value,
        userVerification: document.getElementById('userVerification').value,
        residentKey: document.getElementById('residentKey').value,
        authenticatorAttachment: document.getElementById('authenticatorAttachment').value,
        preferredAuthenticatorType: document.getElementById('preferredAuthenticatorType').value,
        timeout: document.getElementById('timeout').value,
        supportedAlgorithmIDs: getSelectedAlgorithms(),
      };

      if (formData.supportedAlgorithmIDs.length === 0) {
        throw new Error('Please select at least one algorithm');
      }

      // Step 1: Get registration options from server
      const optionsResponse = await fetch('/api/generate-registration-options', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(formData),
      });

      if (!optionsResponse.ok) {
        const error = await optionsResponse.json();
        throw new Error(error.error || 'Failed to get registration options');
      }

      const { challengeId, ...options } = await optionsResponse.json();
      showResults('registration-options', options);

      // Step 2: Start registration with authenticator
      let registrationResponse;
      try {
        registrationResponse = await startRegistration({ optionsJSON: options });
      } catch (error) {
        if (error.name === 'InvalidStateError') {
          throw new Error('This authenticator is already registered for this user');
        } else if (error.name === 'NotAllowedError') {
          throw new Error('Registration was cancelled or timed out');
        }
        throw error;
      }

      showResults('registration-response', registrationResponse);

      // Step 3: Send response to server for verification
      const verificationResponse = await fetch('/api/verify-registration', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ response: registrationResponse, challengeId }),
      });

      if (!verificationResponse.ok) {
        const error = await verificationResponse.json();
        throw new Error(error.error || 'Failed to verify registration');
      }

      const verification = await verificationResponse.json();
      showResults('verification-result', verification);

      if (verification.verified) {
        showMessage(`
          <strong>✅ Passkey created successfully!</strong><br>
          <br>
          <strong>Credential ID:</strong> ${verification.registrationInfo.credentialID.substring(0, 30)}...<br>
          <strong>Device Type:</strong> ${verification.registrationInfo.credentialDeviceType}<br>
          <strong>Backed Up:</strong> ${verification.registrationInfo.credentialBackedUp ? 'Yes (synced)' : 'No (device-bound)'}
        `);
      } else {
        throw new Error('Registration verification failed');
      }

    } catch (error) {
      console.error('Registration error:', error);
      showMessage(`❌ Error: ${error.message}`, true);
    } finally {
      submitBtn.disabled = false;
      submitBtn.textContent = 'Create Passkey';
    }
  });
}

// Authentication flow
const loginForm = document.getElementById('login-form');
if (loginForm) {
  loginForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const submitBtn = document.getElementById('submit-btn');
    submitBtn.disabled = true;
    submitBtn.textContent = 'Authenticating...';

    try {
      // Gather form data
      const formData = {
        username: document.getElementById('username').value || undefined,
        userVerification: document.getElementById('userVerification').value,
        timeout: document.getElementById('timeout').value,
      };

      // Step 1: Get authentication options from server
      const optionsResponse = await fetch('/api/generate-authentication-options', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(formData),
      });

      if (!optionsResponse.ok) {
        const error = await optionsResponse.json();
        throw new Error(error.error || 'Failed to get authentication options');
      }

      const { challengeId, ...options } = await optionsResponse.json();
      showResults('auth-options', options);

      // Step 2: Start authentication with authenticator
      let authResponse;
      try {
        authResponse = await startAuthentication({ optionsJSON: options });
      } catch (error) {
        if (error.name === 'NotAllowedError') {
          throw new Error('Authentication was cancelled or timed out');
        }
        throw error;
      }

      showResults('auth-response', authResponse);

      // Step 3: Send response to server for verification
      const verificationResponse = await fetch('/api/verify-authentication', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ response: authResponse, challengeId }),
      });

      if (!verificationResponse.ok) {
        const error = await verificationResponse.json();
        throw new Error(error.error || 'Failed to verify authentication');
      }

      const verification = await verificationResponse.json();
      showResults('verification-result', verification);

      if (verification.verified) {
        showMessage(`
          <strong>✅ Authentication successful!</strong><br>
          <br>
          <strong>Welcome back, ${verification.username}!</strong>
        `);
      } else {
        throw new Error('Authentication verification failed');
      }

    } catch (error) {
      console.error('Authentication error:', error);
      showMessage(`❌ Error: ${error.message}`, true);
    } finally {
      submitBtn.disabled = false;
      submitBtn.textContent = 'Login with Passkey';
    }
  });
}
