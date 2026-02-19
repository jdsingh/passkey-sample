import express from 'express';
import session from 'express-session';
import {
  generateRegistrationOptions,
  verifyRegistrationResponse,
  generateAuthenticationOptions,
  verifyAuthenticationResponse,
} from '@simplewebauthn/server';

const app = express();
const PORT = 3000;

// In-memory storage (use a database in production)
const users = new Map(); // username -> { id, username, passkeys: [] }
const challenges = new Map(); // sessionId -> challenge

app.use(express.json());
app.use(express.static('public'));
app.use(
  session({
    secret: 'passkeys-sample-secret-change-in-production',
    resave: false,
    saveUninitialized: true,
    cookie: { secure: false }, // Set to true with HTTPS
  })
);

// Default RP configuration
const rpID = 'localhost';
const rpName = 'Passkeys Sample';
const origin = `http://${rpID}:${PORT}`;

// Generate registration options
app.post('/api/generate-registration-options', async (req, res) => {
  try {
    const {
      username,
      // Configurable options from frontend
      attestationType = 'none',
      userVerification = 'preferred',
      residentKey = 'preferred',
      authenticatorAttachment,
      timeout = 60000,
      supportedAlgorithmIDs = [-8, -7, -257],
      preferredAuthenticatorType,
      customRpName,
      customRpID,
    } = req.body;

    if (!username) {
      return res.status(400).json({ error: 'Username is required' });
    }

    // Get or create user
    let user = users.get(username);
    if (!user) {
      user = {
        id: crypto.randomUUID(),
        username,
        passkeys: [],
      };
      users.set(username, user);
    }

    // Build authenticatorSelection
    const authenticatorSelection = {
      residentKey,
      userVerification,
    };
    if (authenticatorAttachment && authenticatorAttachment !== 'any') {
      authenticatorSelection.authenticatorAttachment = authenticatorAttachment;
    }

    // Build options
    const optionsConfig = {
      rpName: customRpName || rpName,
      rpID: customRpID || rpID,
      userName: username,
      userDisplayName: username,
      attestationType,
      authenticatorSelection,
      timeout: parseInt(timeout, 10),
      supportedAlgorithmIDs: supportedAlgorithmIDs.map((id) => parseInt(id, 10)),
      excludeCredentials: user.passkeys.map((passkey) => ({
        id: passkey.id,
        transports: passkey.transports,
      })),
    };

    // Add preferredAuthenticatorType if specified
    if (preferredAuthenticatorType && preferredAuthenticatorType !== 'none') {
      optionsConfig.preferredAuthenticatorType = preferredAuthenticatorType;
    }

    const options = await generateRegistrationOptions(optionsConfig);

    // Store challenge for verification
    challenges.set(req.sessionID, {
      challenge: options.challenge,
      username,
      rpID: customRpID || rpID,
    });

    console.log('Registration options generated:', {
      username,
      attestationType,
      authenticatorSelection,
      timeout,
      supportedAlgorithmIDs,
      preferredAuthenticatorType,
    });

    res.json(options);
  } catch (error) {
    console.error('Error generating registration options:', error);
    res.status(500).json({ error: error.message });
  }
});

// Verify registration
app.post('/api/verify-registration', async (req, res) => {
  try {
    const { response } = req.body;
    const challengeData = challenges.get(req.sessionID);

    if (!challengeData) {
      return res.status(400).json({ error: 'No challenge found. Please start registration again.' });
    }

    const { challenge, username, rpID: expectedRPID } = challengeData;

    const verification = await verifyRegistrationResponse({
      response,
      expectedChallenge: challenge,
      expectedOrigin: origin,
      expectedRPID,
    });

    if (verification.verified && verification.registrationInfo) {
      const { credential, credentialDeviceType, credentialBackedUp } = verification.registrationInfo;

      const user = users.get(username);
      if (user) {
        // Store the new passkey
        user.passkeys.push({
          id: credential.id,
          publicKey: credential.publicKey,
          counter: credential.counter,
          transports: response.response.transports || [],
          deviceType: credentialDeviceType,
          backedUp: credentialBackedUp,
          createdAt: new Date().toISOString(),
        });

        console.log('Passkey registered for user:', username);
        console.log('Device type:', credentialDeviceType);
        console.log('Backed up:', credentialBackedUp);
        console.log('Passkey:', user.passkeys)
      }

      // Clean up challenge
      challenges.delete(req.sessionID);

      res.json({
        verified: true,
        registrationInfo: {
          credentialID: credential.id,
          credentialDeviceType,
          credentialBackedUp,
        },
      });
    } else {
      res.json({ verified: false });
    }
  } catch (error) {
    console.error('Error verifying registration:', error);
    res.status(500).json({ error: error.message });
  }
});

// Generate authentication options
app.post('/api/generate-authentication-options', async (req, res) => {
  try {
    const {
      username,
      userVerification = 'preferred',
      timeout = 60000,
    } = req.body;

    // If username provided, get user's credentials
    let allowCredentials = [];
    if (username) {
      const user = users.get(username);
      if (user && user.passkeys.length > 0) {
        allowCredentials = user.passkeys.map((passkey) => ({
          id: passkey.id,
          transports: passkey.transports,
        }));
      }
    }

    const options = await generateAuthenticationOptions({
      rpID,
      allowCredentials,
      userVerification,
      timeout: parseInt(timeout, 10),
    });

    // Store challenge for verification
    challenges.set(req.sessionID, {
      challenge: options.challenge,
      username,
    });

    console.log('Authentication options generated:', {
      username: username || 'discoverable',
      userVerification,
      timeout,
      allowCredentialsCount: allowCredentials.length,
    });

    res.json(options);
  } catch (error) {
    console.error('Error generating authentication options:', error);
    res.status(500).json({ error: error.message });
  }
});

// Verify authentication
app.post('/api/verify-authentication', async (req, res) => {
  try {
    const { response } = req.body;
    const challengeData = challenges.get(req.sessionID);

    if (!challengeData) {
      return res.status(400).json({ error: 'No challenge found. Please start authentication again.' });
    }

    const { challenge } = challengeData;

    // Find the passkey by credential ID
    let authenticator = null;
    let foundUser = null;

    for (const [username, user] of users) {
      const passkey = user.passkeys.find((pk) => pk.id === response.id);
      if (passkey) {
        authenticator = passkey;
        foundUser = user;
        break;
      }
    }

    if (!authenticator) {
      return res.status(400).json({ error: 'Passkey not found' });
    }

    const verification = await verifyAuthenticationResponse({
      response,
      expectedChallenge: challenge,
      expectedOrigin: origin,
      expectedRPID: rpID,
      credential: {
        id: authenticator.id,
        publicKey: authenticator.publicKey,
        counter: authenticator.counter,
        transports: authenticator.transports,
      },
    });

    if (verification.verified) {
      // Update counter
      authenticator.counter = verification.authenticationInfo.newCounter;

      // Clean up challenge
      challenges.delete(req.sessionID);

      console.log('User authenticated:', foundUser.username);

      res.json({
        verified: true,
        username: foundUser.username,
      });
    } else {
      res.json({ verified: false });
    }
  } catch (error) {
    console.error('Error verifying authentication:', error);
    res.status(500).json({ error: error.message });
  }
});

// Get registered users (for debugging)
app.get('/api/users', (req, res) => {
  const userList = [];
  for (const [username, user] of users) {
    userList.push({
      username,
      passkeysCount: user.passkeys.length,
      passkeys: user.passkeys.map((pk) => ({
        id: pk.id.substring(0, 20) + '...',
        deviceType: pk.deviceType,
        backedUp: pk.backedUp,
        createdAt: pk.createdAt,
      })),
    });
  }
  res.json(userList);
});

app.listen(PORT, () => {
  console.log(`\n🔐 Passkeys Sample Server running at ${origin}`);
  console.log(`\n📝 Open ${origin} in your browser to get started\n`);
});
