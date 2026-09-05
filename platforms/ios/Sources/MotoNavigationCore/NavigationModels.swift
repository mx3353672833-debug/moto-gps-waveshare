import Foundation

public struct WGS84Point: Codable, Hashable, Sendable {
    public let coordinateSystem: String
    public let longitudeDeg: Double
    public let latitudeDeg: Double

    public init(longitudeDeg: Double, latitudeDeg: Double) {
        coordinateSystem = "WGS84"
        self.longitudeDeg = longitudeDeg
        self.latitudeDeg = latitudeDeg
    }

    public var isValid: Bool {
        longitudeDeg.isFinite && latitudeDeg.isFinite &&
            (-180 ... 180).contains(longitudeDeg) &&
            (-90 ... 90).contains(latitudeDeg)
    }

    enum CodingKeys: String, CodingKey {
        case coordinateSystem = "coordinate_system"
        case longitudeDeg = "longitude_deg"
        case latitudeDeg = "latitude_deg"
    }
}

public struct GCJ02Point: Codable, Hashable, Sendable {
    public let longitudeDeg: Double
    public let latitudeDeg: Double

    public init(longitudeDeg: Double, latitudeDeg: Double) {
        self.longitudeDeg = longitudeDeg
        self.latitudeDeg = latitudeDeg
    }

    enum CodingKeys: String, CodingKey {
        case longitudeDeg = "longitude_deg"
        case latitudeDeg = "latitude_deg"
    }
}

public struct NavigationFix: Equatable, Sendable {
    public let coordinate: WGS84Point
    public let altitudeM: Double?
    public let horizontalAccuracyM: Double
    public let speedMps: Double?
    public let courseDeg: Double?
    public let timestamp: Date

    public init(
        coordinate: WGS84Point,
        altitudeM: Double? = nil,
        horizontalAccuracyM: Double,
        speedMps: Double? = nil,
        courseDeg: Double? = nil,
        timestamp: Date
    ) {
        self.coordinate = coordinate
        self.altitudeM = altitudeM
        self.horizontalAccuracyM = horizontalAccuracyM
        self.speedMps = speedMps
        self.courseDeg = courseDeg
        self.timestamp = timestamp
    }

    public var speedKph: Double? {
        speedMps.map { max(0, $0) * 3.6 }
    }
}

public struct RouteRequest: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let requestID: UInt32
    public let routeMode: String
    public let origin: WGS84Point
    public let destination: WGS84Point
    public let isReroute: Bool
    public let previousRouteID: String?
    public let destinationPOIID: String?

    public init(
        requestID: UInt32,
        origin: WGS84Point,
        destination: WGS84Point,
        isReroute: Bool = false,
        previousRouteID: String? = nil,
        destinationPOIID: String? = nil
    ) {
        protocolVersion = 1
        self.requestID = requestID
        routeMode = "driving"
        self.origin = origin
        self.destination = destination
        self.isReroute = isReroute
        self.previousRouteID = previousRouteID
        self.destinationPOIID = destinationPOIID
    }

    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol_version"
        case requestID = "request_id"
        case routeMode = "route_mode"
        case origin
        case destination
        case isReroute = "is_reroute"
        case previousRouteID = "previous_route_id"
        case destinationPOIID = "destination_poi_id"
    }
}

public enum ManeuverType: String, Codable, Sendable {
    case unknown
    case `continue`
    case slightLeft = "slight_left"
    case left
    case sharpLeft = "sharp_left"
    case uTurnLeft = "u_turn_left"
    case slightRight = "slight_right"
    case right
    case sharpRight = "sharp_right"
    case uTurnRight = "u_turn_right"
    case roundabout
    case exit
    case arrive
}

public struct RouteManeuver: Codable, Equatable, Sendable, Identifiable {
    public let id: UInt32
    public let type: ManeuverType
    public let routeOffsetM: Double
    public let roadName: String
    public let instruction: String
    public let roundaboutExit: Int

    public init(
        id: UInt32,
        type: ManeuverType,
        routeOffsetM: Double,
        roadName: String,
        instruction: String,
        roundaboutExit: Int = 0
    ) {
        self.id = id
        self.type = type
        self.routeOffsetM = routeOffsetM
        self.roadName = roadName
        self.instruction = instruction
        self.roundaboutExit = roundaboutExit
    }

    enum CodingKeys: String, CodingKey {
        case id
        case type
        case routeOffsetM = "route_offset_m"
        case roadName = "road_name"
        case instruction
        case roundaboutExit = "roundabout_exit"
    }
}

public enum TrafficLevel: String, Codable, Sendable {
    case unknown
    case freeFlow = "free_flow"
    case slow
    case congested
    case severe
}

public struct TrafficSegment: Codable, Equatable, Sendable {
    public let startOffsetM: Double
    public let endOffsetM: Double
    public let level: TrafficLevel

    public init(startOffsetM: Double, endOffsetM: Double, level: TrafficLevel) {
        self.startOffsetM = startOffsetM
        self.endOffsetM = endOffsetM
        self.level = level
    }

    enum CodingKeys: String, CodingKey {
        case startOffsetM = "start_offset_m"
        case endOffsetM = "end_offset_m"
        case level
    }
}

public struct RoutePlan: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let routeID: String
    public let provider: String
    public let coordinateSystem: String
    public let generatedAtMs: UInt64
    public let totalDistanceM: Double
    public let totalDurationS: Int
    public let polyline: [GCJ02Point]
    public let maneuvers: [RouteManeuver]
    public let traffic: [TrafficSegment]

    public init(
        routeID: String,
        provider: String,
        coordinateSystem: String = "GCJ-02",
        generatedAtMs: UInt64,
        totalDistanceM: Double,
        totalDurationS: Int,
        polyline: [GCJ02Point],
        maneuvers: [RouteManeuver],
        traffic: [TrafficSegment]
    ) {
        schemaVersion = 1
        self.routeID = routeID
        self.provider = provider
        self.coordinateSystem = coordinateSystem
        self.generatedAtMs = generatedAtMs
        self.totalDistanceM = totalDistanceM
        self.totalDurationS = totalDurationS
        self.polyline = polyline
        self.maneuvers = maneuvers
        self.traffic = traffic
    }

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version"
        case routeID = "route_id"
        case provider
        case coordinateSystem = "coordinate_system"
        case generatedAtMs = "generated_at_ms"
        case totalDistanceM = "total_distance_m"
        case totalDurationS = "total_duration_s"
        case polyline
        case maneuvers
        case traffic
    }
}

public struct RouteEnvelope: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let requestID: UInt32
    public let route: RoutePlan

    public init(requestID: UInt32, route: RoutePlan) {
        protocolVersion = 1
        self.requestID = requestID
        self.route = route
    }

    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol_version"
        case requestID = "request_id"
        case route
    }
}
