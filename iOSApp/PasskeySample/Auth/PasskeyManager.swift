import AuthenticationServices
import Foundation

// Must be @MainActor because ASAuthorizationController and its delegates run on the main thread.
@MainActor
final class PasskeyManager: NSObject {

    // Continuation for the manual sign-in button flow.
    private var manualContinuation: CheckedContinuation<String, Error>?

    // Callback for the autofill-assisted flow (fired by the system on field focus).
    private var autofillCallback: ((String) -> Void)?

    // Holds a strong reference so the autofill controller stays alive.
    private var autofillController: ASAuthorizationController?

    // MARK: - Manual sign-in (Sign In button)

    func signIn(requestOptionsJSON: String) async throws -> String {
        return try await withCheckedThrowingContinuation { continuation in
            self.manualContinuation = continuation
            do {
                let controller = try self.makeController(from: requestOptionsJSON)
                controller.performRequests()
            } catch {
                continuation.resume(throwing: error)
                self.manualContinuation = nil
            }
        }
    }

    // MARK: - Autofill-assisted flow

    /// Prefetches auth options and registers them with the system autofill pipeline.
    /// When a TextField with .textContentType(.username) is focused the passkey picker
    /// will appear in the QuickType bar automatically.
    func setupAutofill(requestOptionsJSON: String, onCredential: @escaping (String) -> Void) throws {
        autofillCallback = onCredential
        let controller = try makeController(from: requestOptionsJSON)
        autofillController = controller
        controller.performAutoFillAssistedRequests()
    }

    // MARK: - Private helpers

    private func makeController(from json: String) throws -> ASAuthorizationController {
        guard let data = json.data(using: .utf8),
              let options = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let challengeB64 = options["challenge"] as? String,
              let rpId = options["rpId"] as? String,
              let challengeData = Data(base64URLEncoded: challengeB64) else {
            throw PasskeyError.invalidOptions
        }

        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: rpId)
        let assertionRequest = provider.createCredentialAssertionRequest(challenge: challengeData)

        if let uv = options["userVerification"] as? String {
            assertionRequest.userVerificationPreference =
                ASAuthorizationPublicKeyCredentialUserVerificationPreference(rawValue: uv)
        }

        let controller = ASAuthorizationController(authorizationRequests: [assertionRequest])
        controller.delegate = self
        controller.presentationContextProvider = self
        return controller
    }

    private func buildResponseJSON(from credential: ASAuthorizationPlatformPublicKeyCredentialAssertion) throws -> String {
        let credentialId = credential.credentialID.base64URLEncodedString()
        var inner: [String: Any] = [
            "clientDataJSON":    credential.rawClientDataJSON.base64URLEncodedString(),
            "authenticatorData": credential.rawAuthenticatorData.base64URLEncodedString(),
            "signature":         credential.signature.base64URLEncodedString(),
        ]
        if let userHandle = credential.userID {
            inner["userHandle"] = userHandle.base64URLEncodedString()
        }
        let outer: [String: Any] = [
            "id":       credentialId,
            "rawId":    credentialId,
            "type":     "public-key",
            "response": inner,
        ]
        let data = try JSONSerialization.data(withJSONObject: outer)
        return String(data: data, encoding: .utf8)!
    }
}

// MARK: - ASAuthorizationControllerDelegate

extension PasskeyManager: ASAuthorizationControllerDelegate {
    nonisolated func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        Task { @MainActor in
            guard let credential = authorization.credential
                    as? ASAuthorizationPlatformPublicKeyCredentialAssertion else {
                let err = PasskeyError.unexpectedCredentialType
                manualContinuation?.resume(throwing: err)
                manualContinuation = nil
                return
            }
            do {
                let json = try buildResponseJSON(from: credential)
                if let continuation = manualContinuation {
                    continuation.resume(returning: json)
                    manualContinuation = nil
                } else {
                    autofillCallback?(json)
                }
            } catch {
                manualContinuation?.resume(throwing: error)
                manualContinuation = nil
            }
        }
    }

    nonisolated func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        Task { @MainActor in
            manualContinuation?.resume(throwing: error)
            manualContinuation = nil
        }
    }
}

// MARK: - ASAuthorizationControllerPresentationContextProviding

extension PasskeyManager: ASAuthorizationControllerPresentationContextProviding {
    nonisolated func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        // Must be called on main thread; nonisolated + sync access is safe here because
        // ASAuthorizationController always calls this on the main queue.
        guard let scene = UIApplication.shared.connectedScenes
            .first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene,
              let window = scene.keyWindow ?? scene.windows.first else {
            fatalError("No foreground window available")
        }
        return window
    }
}

// MARK: - Errors

enum PasskeyError: LocalizedError {
    case invalidOptions
    case unexpectedCredentialType

    var errorDescription: String? {
        switch self {
        case .invalidOptions:           return "Invalid authentication options from server"
        case .unexpectedCredentialType: return "Unexpected credential type returned"
        }
    }
}

// MARK: - Base64URL helpers

extension Data {
    init?(base64URLEncoded string: String) {
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 { base64 += String(repeating: "=", count: 4 - remainder) }
        self.init(base64Encoded: base64)
    }

    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
