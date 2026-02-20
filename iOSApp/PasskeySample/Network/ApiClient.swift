import Foundation

enum ApiClient {
    static let baseURL = "https://passkey-sample-e9304.web.app"

    static func generateAuthOptions(username: String?) async throws -> String {
        let url = URL(string: "\(baseURL)/api/generate-authentication-options")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        var body: [String: Any] = [:]
        if let username, !username.isEmpty { body["username"] = username }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        return try await perform(request)
    }

    static func verifyAuthentication(responseJSON: String, challengeId: String) async throws -> String {
        let url = URL(string: "\(baseURL)/api/verify-authentication")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        guard let responseData = responseJSON.data(using: .utf8),
              let responseParsed = try? JSONSerialization.jsonObject(with: responseData) else {
            throw URLError(.cannotParseResponse)
        }
        let body: [String: Any] = ["response": responseParsed, "challengeId": challengeId]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        return try await perform(request)
    }

    private static func perform(_ request: URLRequest) async throws -> String {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? "Unknown error"
            throw URLError(.badServerResponse, userInfo: [NSLocalizedDescriptionKey: body])
        }
        return String(data: data, encoding: .utf8) ?? ""
    }
}
