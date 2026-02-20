import Foundation

/// Pure network + JSON parsing — no UI state, no retained objects.
struct AuthRepository {

    /// Fetches a challenge from the server.
    /// Returns (requestOptionsJSON, challengeId) where requestOptionsJSON has challengeId stripped out.
    func generateOptions(username: String?) async throws -> (requestJSON: String, challengeId: String) {
        let raw = try await ApiClient.generateAuthOptions(username: username)

        guard let data = raw.data(using: .utf8),
              var json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let challengeId = json["challengeId"] as? String else {
            throw AuthError.invalidServerResponse
        }
        json.removeValue(forKey: "challengeId")
        let stripped = try JSONSerialization.data(withJSONObject: json)
        let requestJSON = String(data: stripped, encoding: .utf8)!
        return (requestJSON, challengeId)
    }

    /// Sends the authenticator assertion to the server and returns the authenticated username.
    func verifyResponse(authResponseJSON: String, challengeId: String) async throws -> String {
        let raw = try await ApiClient.verifyAuthentication(
            responseJSON: authResponseJSON,
            challengeId: challengeId
        )
        guard let data = raw.data(using: .utf8),
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let verified = json["verified"] as? Bool, verified,
              let username = json["username"] as? String else {
            throw AuthError.authenticationFailed
        }
        return username
    }
}

enum AuthError: LocalizedError {
    case invalidServerResponse
    case authenticationFailed

    var errorDescription: String? {
        switch self {
        case .invalidServerResponse: return "Invalid response from server"
        case .authenticationFailed:  return "Authentication failed"
        }
    }
}
