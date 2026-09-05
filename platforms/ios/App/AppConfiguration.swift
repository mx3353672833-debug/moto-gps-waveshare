import Foundation

enum AppConfiguration {
    static var gatewayBaseURL: URL {
        if let value = Bundle.main.object(forInfoDictionaryKey: "MOTOGPSGatewayBaseURL") as? String,
           let url = URL(string: value)
        {
            return url
        }
        // Public sources intentionally do not use the author's private gateway.
        // Configure MOTOGPSGatewayBaseURL in project.yml, then run xcodegen.
        return URL(string: "https://example.invalid/moto-gps/api/")!
    }
}
