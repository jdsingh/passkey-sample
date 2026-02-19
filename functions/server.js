import express from 'express';
import session from 'express-session';
import {
  generateRegistrationOptions,
  verifyRegistrationResponse,
  generateAuthenticationOptions,
  verifyAuthenticationResponse,
} from '@simplewebauthn/server';
import { isoBase64URL } from '@simplewebauthn/server/helpers';
import { initializeApp, getApps } from 'firebase-admin/app';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';

// Guard against double-initialization in emulator restarts
if (getApps().length === 0) {
  initializeApp();
}
const db = getFirestore();

const app = express();

app.use(express.json());

app.use(
  session({
    secret: process.env.SESSION_SECRET || 'passkeys-sample-secret-change-in-production',
    resave: false,
    saveUninitialized: true,
    cookie: {
      // Firebase Hosting terminates TLS before forwarding to Functions over HTTP,
      // so req.secure is false inside the function. Use NODE_ENV to set secure flag.
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
    },
  })
);

// RP configuration — set via environment variables for production
const rpID = process.env.RPID || 'localhost';
const rpName = process.env.RPDISPLAYNAME || 'Passkeys Sample';
const origin = process.env.EXPECTED_ORIGIN || 'http://localhost:3000';

// ── Firestore helpers ─────────────────────────────────────────────────────

async function getChallenge(sessionID) {
  const snap = await db.collection('challenges').doc(sessionID).get();
  return snap.exists ? snap.data() : null;
}

async function setChallenge(sessionID, data) {
  await db.collection('challenges').doc(sessionID).set({
    ...data,
    createdAt: FieldValue.serverTimestamp(),
  });
}

async function deleteChallenge(sessionID) {
  await db.collection('challenges').doc(sessionID).delete();
}

// Deserialize a stored passkey doc (converts publicKey from Base64URL → Uint8Array)
function deserializePasskey(pk) {
  return { ...pk, publicKey: isoBase64URL.toBuffer(pk.publicKey) };
}

async function getUser(username) {
  const snap = await db.collection('users').doc(username).get();
  if (!snap.exists) return null;
  const data = snap.data();
  return {
    ...data,
    passkeys: (data.passkeys || []).map(deserializePasskey),
  };
}

async function createUser(username) {
  const user = { id: crypto.randomUUID(), username, passkeys: [] };
  await db.collection('users').doc(username).set(user);
  return { ...user };
}

async function addPasskeyToUser(username, passkey) {
  // Serialize Uint8Array → Base64URL string for Firestore storage
  const passkeyToStore = {
    ...passkey,
    publicKey: isoBase64URL.fromBuffer(passkey.publicKey),
  };
  await db.collection('users').doc(username).update({
    passkeys: FieldValue.arrayUnion(passkeyToStore),
  });
}

async function updatePasskeyCounter(username, credentialID, newCounter) {
  const ref = db.collection('users').doc(username);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) throw new Error('User not found during counter update');
    const passkeys = (snap.data().passkeys || []).map((pk) =>
      pk.id === credentialID ? { ...pk, counter: newCounter } : pk
    );
    tx.update(ref, { passkeys });
  });
}

// Scan all users to find which one owns a credential ID (acceptable for demo scale)
async function findUserByCredentialID(credentialID) {
  const snap = await db.collection('users').get();
  for (const doc of snap.docs) {
    const data = doc.data();
    const passkey = (data.passkeys || []).find((pk) => pk.id === credentialID);
    if (passkey) {
      return {
        user: { ...data, passkeys: (data.passkeys || []).map(deserializePasskey) },
        passkey: deserializePasskey(passkey),
      };
    }
  }
  return null;
}

// ── Routes ────────────────────────────────────────────────────────────────

// Generate registration options
app.post('/api/generate-registration-options', async (req, res) => {
  try {
    const {
      username,
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

    let user = await getUser(username);
    if (!user) {
      user = await createUser(username);
    }

    const authenticatorSelection = { residentKey, userVerification };
    if (authenticatorAttachment && authenticatorAttachment !== 'any') {
      authenticatorSelection.authenticatorAttachment = authenticatorAttachment;
    }

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

    if (preferredAuthenticatorType && preferredAuthenticatorType !== 'none') {
      optionsConfig.preferredAuthenticatorType = preferredAuthenticatorType;
    }

    const options = await generateRegistrationOptions(optionsConfig);

    await setChallenge(req.sessionID, {
      challenge: options.challenge,
      username,
      rpID: customRpID || rpID,
    });

    console.log('Registration options generated:', { username, attestationType, authenticatorSelection });
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

    const challengeData = await getChallenge(req.sessionID);
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

      await addPasskeyToUser(username, {
        id: credential.id,
        publicKey: credential.publicKey,
        counter: credential.counter,
        transports: response.response.transports || [],
        deviceType: credentialDeviceType,
        backedUp: credentialBackedUp,
        createdAt: new Date().toISOString(),
      });

      await deleteChallenge(req.sessionID);

      console.log('Passkey registered for user:', username);
      console.log('Device type:', credentialDeviceType);
      console.log('Backed up:', credentialBackedUp);

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

    let allowCredentials = [];
    if (username) {
      const user = await getUser(username);
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

    await setChallenge(req.sessionID, {
      challenge: options.challenge,
      username: username || null,
    });

    console.log('Authentication options generated:', { username: username || 'discoverable' });
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

    const challengeData = await getChallenge(req.sessionID);
    if (!challengeData) {
      return res.status(400).json({ error: 'No challenge found. Please start authentication again.' });
    }

    const { challenge } = challengeData;

    const found = await findUserByCredentialID(response.id);
    if (!found) {
      return res.status(400).json({ error: 'Passkey not found' });
    }
    const { user: foundUser, passkey: authenticator } = found;

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
      await updatePasskeyCounter(
        foundUser.username,
        authenticator.id,
        verification.authenticationInfo.newCounter
      );

      await deleteChallenge(req.sessionID);

      console.log('User authenticated:', foundUser.username);
      res.json({ verified: true, username: foundUser.username });
    } else {
      res.json({ verified: false });
    }
  } catch (error) {
    console.error('Error verifying authentication:', error);
    res.status(500).json({ error: error.message });
  }
});

// Get registered users (debug endpoint)
app.get('/api/users', async (req, res) => {
  try {
    const snap = await db.collection('users').get();
    const userList = snap.docs.map((doc) => {
      const data = doc.data();
      return {
        username: data.username,
        passkeysCount: (data.passkeys || []).length,
        passkeys: (data.passkeys || []).map((pk) => ({
          id: pk.id.substring(0, 20) + '...',
          deviceType: pk.deviceType,
          backedUp: pk.backedUp,
          createdAt: pk.createdAt,
        })),
      };
    });
    res.json(userList);
  } catch (error) {
    console.error('Error fetching users:', error);
    res.status(500).json({ error: error.message });
  }
});

export default app;
