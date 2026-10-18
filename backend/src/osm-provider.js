import { Buffer } from "node:buffer";

export function createOsmProvider({
  nominatimBaseUrl = "https://nominatim.openstreetmap.org",
  osrmBaseUrl = "https://router.project-osrm.org",
  userAgent = "MotoGPSGateway/1.0",
} = {}) {
  
  async function fetchJson(url, options = {}) {
    const response = await fetch(url, {
      ...options,
      headers: {
        "User-Agent": userAgent,
        ...(options.headers || {}),
      },
    });

    if (!response.ok) {
      const error = new Error(`External service failed with status ${response.status}`);
      error.code = "UPSTREAM_ERROR";
      error.retryable = true;
      throw error;
    }

    return response.json();
  }

  return {
    async searchPlaces(query) {
      const keywords = encodeURIComponent(query.keywords);
      const url = `${nominatimBaseUrl}/search?q=${keywords}&format=json&addressdetails=1&limit=10`;
      
      const data = await fetchJson(url);

      const places = data.map((item) => ({
        id: String(item.place_id),
        name: item.name || item.display_name.split(",")[0],
        address: item.display_name,
        location: {
          coordinate_system: "WGS84",
          longitude_deg: parseFloat(item.lon),
          latitude_deg: parseFloat(item.lat),
        },
      }));

      return {
        protocol_version: 1,
        places,
      };
    },

    async planRoute(body) {
      const origin = body.origin;
      const destination = body.destination;

      const coords = `${origin.longitude_deg},${origin.latitude_deg};${destination.longitude_deg},${destination.latitude_deg}`;
      const url = `${osrmBaseUrl}/route/v1/driving/${coords}?overview=full&geometries=geojson&steps=true`;

      const data = await fetchJson(url);

      if (!data.routes || data.routes.length === 0) {
        const error = new Error("No route found between origin and destination");
        error.code = "ROUTE_NOT_FOUND";
        error.retryable = false;
        throw error;
      }

      const primaryRoute = data.routes[0];
      const durationSeconds = primaryRoute.duration;
      const distanceMeters = primaryRoute.distance;

      const steps = [];
      if (primaryRoute.legs && primaryRoute.legs[0] && primaryRoute.legs[0].steps) {
        for (const step of primaryRoute.legs[0].steps) {
          steps.push({
            instruction: step.maneuver.instruction || step.name || "Prosegui",
            distance_meters: Math.round(step.distance),
            duration_seconds: Math.round(step.duration),
          });
        }
      }

      return {
        summary: {
          total_distance_meters: Math.round(distanceMeters),
          estimated_duration_seconds: Math.round(durationSeconds),
        },
        steps,
      };
    },

    async planRouteOptions(body) {
      const mainRoute = await this.planRoute(body);
      return [mainRoute];
    },
  };
}
