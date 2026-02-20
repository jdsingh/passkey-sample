import SwiftUI

struct MainView: View {
    @Binding var path: NavigationPath

    var body: some View {
        VStack {
            Button("Sign In with Passkey") {
                path.append(Route.signIn)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .padding()
        }
        .navigationTitle("Passkey Sample")
    }
}
