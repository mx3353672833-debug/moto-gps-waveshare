import { createHash } from "node:crypto";
import { wgs84ToGcj02 } from "./coordinates.js";

function mapOsmManeuver(type, modifier) {
  const t = String(type || "").toLowerCase();
  const m = String(modifier || "").toLowerCase();

  if (t === "arrive") return "arrive";
  if (t === "depart") return "continue";
  if (t === "roundabout" || t === "rotary") return "roundabout";
  if (t === "exit roundabout" || t === "exit rotary" || m.includes("exit")) return "exit";

  if (m.includes("uturn") || m.includes("u turn")) {
    return m.includes("right") ? "u_turn_right" : "u_turn_left";
  }
  if (m.includes("sharp left")) return "sharp_left";
  if (m.includes("sharp right")) return "sharp_right";
  if (m.includes("slight left")) return "slight_left";
  if (m.includes("slight right")) return "slight_right";
  if (m.includes("left")) return "left";
  if (m.includes("right")) return "right";
  if (m.includes("straight") || t === "continue" || t === "new name") return "continue";

  return "continue";
}

export function createOsmProvider({
  nominatimBaseUrl = "https://nominatim.openstreetmap.org",
  photonBaseUrl = "https://photon.komoot.io",
  osrmBaseUrls,
  osrmBaseUrl,
  userAgent = "MotoGpsWaveshare/1.0 (https://github.com/thriller47/moto-gps-waveshare)",
} = {}) {
  const defaultOsrmEndpoints = [
    "https://routing.openstreetmap.de/routed-car/route/v1/driving",
    "https://router.project-osrm.org/route/v1/driving",
    "https://routing.openstreetmap.de/routed-bike/route/v1/driving",
  ];

  const endpointsToUse = osrmBaseUrls || (osrmBaseUrl ? [osrmBaseUrl] : defaultOsrmEndpoints);

  async function fetchJsonWithTimeout(url, options = {}, timeoutMs = 10000) {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

    try {
      const response = await fetch(url, {
        ...options,
        signal: controller.signal,
        headers: {
          "User-Agent": userAgent,
          ...(options.headers || {}),
        },
      });

      if (!response.ok) {
        const error = new Error(`External service failed with status ${response.status}`);
        error.code = "UPSTREAM_ERROR";
        error.status = response.status;
        error.retryable = true;
        throw error;
      }

      return await response.json();
    } finally {
      clearTimeout(timeoutId);
    }
  }

  return {
    async searchPlaces(query) {
      const rawKeywords = String(query.keywords || query.query || query.q || "").trim();
      if (!rawKeywords) {
        return {
          protocol_version: 1,
          places: [],
        };
      }

      const keywords = encodeURIComponent(rawKeywords);

      // Provider 1 (Primary): Nominatim
      let nominatimUrl = nominatimBaseUrl.endsWith("/")
        ? `${nominatimBaseUrl}search?q=${keywords}&format=json&addressdetails=1&limit=10`
        : `${nominatimBaseUrl}/search?q=${keywords}&format=json&addressdetails=1&limit=10`;

      if (query.origin && query.origin.longitude_deg != null && query.origin.latitude_deg != null) {
        const lon = Number(query.origin.longitude_deg);
        const lat = Number(query.origin.latitude_deg);
        const minLon = Math.max(-180, lon - 0.5);
        const maxLon = Math.min(180, lon + 0.5);
        const minLat = Math.max(-90, lat - 0.5);
        const maxLat = Math.min(90, lat + 0.5);
        nominatimUrl += `&viewbox=${minLon},${maxLat},${maxLon},${minLat}&bounded=0`;
      }

      try {
        const data = await fetchJsonWithTimeout(
          nominatimUrl,
          {
            headers: {
              "Accept-Language": "it,en",
            },
          },
          10000
        );

        if (Array.isArray(data)) {
          const places = data.map((item) => ({
            id: String(item.place_id),
            name: item.name || (item.display_name ? item.display_name.split(",")[0] : rawKeywords),
            address: item.display_name || "",
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
        }
      } catch (nomErr) {
        // Fallback to Photon
      }

      // Provider 2 (Fallback): Photon Komoot
      try {
        let photonUrl = photonBaseUrl.endsWith("/")
          ? `${photonBaseUrl}api/?q=${keywords}&limit=10&lang=it`
          : `${photonBaseUrl}/api/?q=${keywords}&limit=10&lang=it`;

        if (query.origin && query.origin.longitude_deg != null && query.origin.latitude_deg != null) {
          photonUrl += `&lon=${query.origin.longitude_deg}&lat=${query.origin.latitude_deg}`;
        }

        const photonData = await fetchJsonWithTimeout(photonUrl, {}, 10000);
        if (photonData && Array.isArray(photonData.features)) {
          const places = photonData.features.map((feat, idx) => {
            const props = feat.properties || {};
            const coords = feat.geometry?.coordinates || [0, 0];
            const name = props.name || props.street || rawKeywords;
            const addressParts = [
              props.name,
              props.street,
              props.housenumber,
              props.city,
              props.state,
              props.country,
            ].filter(Boolean);
            const address = addressParts.join(", ") || name;

            return {
              id: String(props.osm_id || idx + 1),
              name,
              address,
              location: {
                coordinate_system: "WGS84",
                longitude_deg: Number(coords[0]),
                latitude_deg: Number(coords[1]),
              },
            };
          });

          return {
            protocol_version: 1,
            places,
          };
        }
      } catch (photonErr) {
        // Fallthrough to empty list
      }

      return {
        protocol_version: 1,
        places: [],
      };
    },

    async planRoute(body) {
      const origin = body.origin;
      const destination = body.destination;

      const coords = `${origin.longitude_deg},${origin.latitude_deg};${destination.longitude_deg},${destination.latitude_deg}`;

      let data = null;
      let lastError = null;

      for (const baseEndpoint of endpointsToUse) {
        const cleanEndpoint = baseEndpoint.endsWith("/") ? baseEndpoint : `${baseEndpoint}/`;
        const url = `${cleanEndpoint}${coords}?overview=full&geometries=geojson&steps=true`;

        try {
          const resData = await fetchJsonWithTimeout(url, {}, 12000);
          if (resData && (resData.code === "Ok" || (resData.routes && resData.routes.length > 0))) {
            data = resData;
            break;
          }
        } catch (err) {
          lastError = err;
        }
      }

      if (!data || !data.routes || data.routes.length === 0) {
        const error = lastError || new Error("No route found between origin and destination");
        if (!error.code) {
          error.code = "ROUTE_NOT_FOUND";
          error.retryable = false;
        }
        throw error;
      }

      const primaryRoute = data.routes[0];
      const durationSeconds = Math.round(primaryRoute.duration || 0);
      const distanceMeters = Math.round(primaryRoute.distance || 0);

      const geometry_gcj02 = (primaryRoute.geometry?.coordinates || []).map(([lon, lat]) => {
        const gcj = wgs84ToGcj02({ longitude_deg: lon, latitude_deg: lat });
        return {
          longitude_deg: gcj.longitude_deg,
          latitude_deg: gcj.latitude_deg,
        };
      });

      const maneuvers = [];
      let routeOffsetM = 0;

      if (primaryRoute.legs && primaryRoute.legs[0] && primaryRoute.legs[0].steps) {
        const steps = primaryRoute.legs[0].steps;
        for (let i = 0; i < steps.length; i++) {
          const step = steps[i];
          const stepDistanceM = Math.round(step.distance || 0);
          routeOffsetM += stepDistanceM;

          let routePointIndex = 0;
          if (step.maneuver && Array.isArray(step.maneuver.location) && step.maneuver.location.length >= 2) {
            const mLon = step.maneuver.location[0];
            const mLat = step.maneuver.location[1];
            let minDistance = Infinity;
            for (let pIdx = 0; pIdx < geometry_gcj02.length; pIdx++) {
              const p = geometry_gcj02[pIdx];
              const dist = Math.hypot(p.longitude_deg - mLon, p.latitude_deg - mLat);
              if (dist < minDistance) {
                minDistance = dist;
                routePointIndex = pIdx;
              }
            }
          }

          const type = mapOsmManeuver(step.maneuver?.type, step.maneuver?.modifier);
          const roadName = step.name || "";
          const instruction = step.maneuver?.instruction || step.name || "Prosegui";
          const roundaboutExit = step.maneuver?.exit || 0;

          maneuvers.push({
            index: i + 1,
            id: i + 1,
            route_point_index: routePointIndex,
            type,
            distance_from_start_m: routeOffsetM,
            route_offset_m: routeOffsetM,
            step_distance_m: stepDistanceM,
            road_name: roadName,
            instruction,
            roundabout_exit: roundaboutExit,
          });
        }
      }

      const routeId = `osm-${createHash("sha256").update(JSON.stringify(coords)).digest("hex").slice(0, 20)}`;

      return {
        protocol_version: 1,
        schema_version: 1,
        route_id: routeId,
        provider: "osm",
        coordinate_system: "GCJ-02",
        generated_at_ms: Date.now(),
        origin,
        destination,
        total_distance_m: distanceMeters,
        estimated_duration_s: durationSeconds,
        total_duration_s: durationSeconds,
        polyline: geometry_gcj02,
        geometry_gcj02,
        maneuvers,
        traffic: [],
        traffic_segments: [],
        summary: {
          total_distance_meters: distanceMeters,
          estimated_duration_seconds: durationSeconds,
        },
        steps: maneuvers.map((m) => ({
          instruction: m.instruction,
          distance_meters: m.step_distance_m,
          duration_seconds: 0,
        })),
      };
    },

    async planRouteOptions(body) {
      const mainRoute = await this.planRoute(body);
      return [mainRoute];
    },
  };
}
