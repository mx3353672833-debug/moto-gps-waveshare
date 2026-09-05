import SwiftUI

@main
struct MotoGPSApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
                .preferredColorScheme(.dark)
        }
    }
}
