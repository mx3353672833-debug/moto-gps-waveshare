import Foundation
import MotoNavigationCore

struct PlaceSearchResult: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let name: String
    let address: String
    let city: String
    let district: String
    let displayArea: String
    let location: WGS84Point
    let distanceM: Double?

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case address
        case city
        case district
        case displayArea = "display_area"
        case location
        case distanceM = "distance_m"
    }
}

private struct PlaceSearchEnvelope: Decodable {
    let protocolVersion: Int
    let query: String
    let places: [PlaceSearchResult]

    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol_version"
        case query
        case places
    }
}

private struct PlaceGatewayFailureEnvelope: Decodable {
    let error: PlaceGatewayFailure
}

private struct PlaceGatewayFailure: Decodable {
    let code: String
    let message: String
}

final class AmapGatewayPlaceProvider: @unchecked Sendable {
    private let baseURL: URL
    private let session: URLSession
    private let decoder = JSONDecoder()

    init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func search(keywords: String, near origin: WGS84Point? = nil) async throws -> [PlaceSearchResult] {
        let query = keywords.trimmingCharacters(in: .whitespacesAndNewlines)
        guard query.count >= 2 else { return [] }

        let endpoint = baseURL.appending(path: "v1/places")
        guard endpoint.scheme == "https" || endpoint.host == "127.0.0.1" || endpoint.host == "localhost",
              var components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false)
        else {
            throw AmapGatewayError.invalidEndpoint
        }
        var queryItems = [URLQueryItem(name: "keywords", value: query)]
        if let origin, origin.isValid, origin.coordinateSystem == "WGS84" {
            queryItems.append(
                URLQueryItem(name: "longitude_deg", value: String(format: "%.7f", origin.longitudeDeg))
            )
            queryItems.append(
                URLQueryItem(name: "latitude_deg", value: String(format: "%.7f", origin.latitudeDeg))
            )
        }
        components.queryItems = queryItems
        guard let url = components.url else { throw AmapGatewayError.invalidEndpoint }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 10
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw AmapGatewayError.invalidResponse
        }
        guard (200 ..< 300).contains(http.statusCode) else {
            if let failure = try? decoder.decode(PlaceGatewayFailureEnvelope.self, from: data) {
                throw AmapGatewayError.rejected(
                    code: failure.error.code,
                    message: failure.error.message
                )
            }
            throw AmapGatewayError.invalidResponse
        }

        let envelope = try decoder.decode(PlaceSearchEnvelope.self, from: data)
        guard envelope.protocolVersion == 1 else {
            throw AmapGatewayError.invalidResponse
        }
        return envelope.places.filter {
            $0.location.coordinateSystem == "WGS84" && $0.location.isValid
        }
    }
}
