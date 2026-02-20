import SwiftUI

enum Route: Hashable {
    case signIn
    case home(String)
}

struct ContentView: View {
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            MainView(path: $path)
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .signIn:
                        SignInView(path: $path)
                    case .home(let username):
                        HomeView(username: username, path: $path)
                    }
                }
        }
    }
}
