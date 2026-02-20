import AuthenticationServices
import Combine
import Foundation

enum SignInUiState: Equatable {
    case idle
    case loading
    case success(String)
    case error(String)
}

@MainActor
final class SignInViewModel: ObservableObject {
    @Published private(set) var uiState: SignInUiState = .idle

    private let passkeyManager = PasskeyManager()
    private let repository = AuthRepository()
    private var autofillChallengeId: String?

    // MARK: - Computed helpers for the view

    var isLoading: Bool { uiState == .loading }

    var errorMessage: String? {
        if case .error(let msg) = uiState { return msg }
        return nil
    }

    var signedInUsername: String? {
        if case .success(let u) = uiState { return u }
        return nil
    }

    // MARK: - Autofill

    /// Called from the view's onAppear. Fetches auth options and registers them with
    /// the system so the passkey picker appears when the username field is focused.
    func prefetchForAutofill() {
        Task {
            do {
                let (requestJSON, challengeId) = try await repository.generateOptions(username: nil)
                autofillChallengeId = challengeId
                try passkeyManager.setupAutofill(requestOptionsJSON: requestJSON) { [weak self] authResponseJSON in
                    self?.handleAutofillCredential(authResponseJSON: authResponseJSON)
                }
            } catch {
                // Silent — manual Sign In button flow remains fully functional.
            }
        }
    }

    /// Called by the PasskeyManager autofill callback after the system delivers a credential.
    func handleAutofillCredential(authResponseJSON: String) {
        guard let challengeId = autofillChallengeId else { return }
        Task {
            uiState = .loading
            do {
                let username = try await repository.verifyResponse(
                    authResponseJSON: authResponseJSON,
                    challengeId: challengeId
                )
                uiState = .success(username)
            } catch {
                uiState = isCancellation(error) ? .idle : .error(error.localizedDescription)
            }
        }
    }

    // MARK: - Manual sign-in

    func signIn(username: String?) {
        Task {
            uiState = .loading
            do {
                let (requestJSON, challengeId) = try await repository.generateOptions(username: username)
                let authResponseJSON = try await passkeyManager.signIn(requestOptionsJSON: requestJSON)
                let signedIn = try await repository.verifyResponse(
                    authResponseJSON: authResponseJSON,
                    challengeId: challengeId
                )
                uiState = .success(signedIn)
            } catch {
                uiState = isCancellation(error) ? .idle : .error(error.localizedDescription)
            }
        }
    }

    func resetState() {
        uiState = .idle
    }

    // MARK: - Private

    private func isCancellation(_ error: Error) -> Bool {
        (error as? ASAuthorizationError)?.code == .canceled ||
        (error as NSError).code == ASAuthorizationError.canceled.rawValue
    }
}
