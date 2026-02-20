import SwiftUI

struct SignInView: View {
    @Binding var path: NavigationPath
    @StateObject private var viewModel = SignInViewModel()
    @State private var username = ""

    var body: some View {
        VStack(spacing: 16) {
            // textContentType(.username) is what tells iOS to show passkey suggestions
            // in the QuickType bar when this field is focused.
            TextField("Username (optional)", text: $username)
                .textContentType(.username)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .disabled(viewModel.isLoading)

            Button {
                viewModel.signIn(username: username)
            } label: {
                Group {
                    if viewModel.isLoading {
                        ProgressView()
                            .tint(.white)
                    } else {
                        Text("Sign In with Passkey")
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(viewModel.isLoading)

            if let error = viewModel.errorMessage {
                Text(error)
                    .foregroundStyle(.red)
                    .font(.caption)
                    .multilineTextAlignment(.center)
            }
        }
        .padding()
        .navigationTitle("Sign In")
        .onAppear {
            // Register autofill early — before the user taps the field — so the system
            // has time to prepare passkey suggestions for the QuickType bar.
            viewModel.prefetchForAutofill()
        }
        .onChange(of: viewModel.signedInUsername) { username in
            if let username {
                path.append(Route.home(username))
                viewModel.resetState()
            }
        }
    }
}
