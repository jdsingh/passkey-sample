import SwiftUI

struct HomeView: View {
    let username: String
    @Binding var path: NavigationPath

    var body: some View {
        VStack(spacing: 32) {
            Text("Welcome, \(username)!")
                .font(.title)
                .multilineTextAlignment(.center)

            Button("Sign Out") {
                path.removeLast(path.count)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
        .padding()
        .navigationTitle("Welcome")
        .navigationBarBackButtonHidden(true)
    }
}
