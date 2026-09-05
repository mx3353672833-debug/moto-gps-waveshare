const PI = Math.PI;
const EARTH_AXIS_M = 6378245.0;
const ECCENTRICITY_SQUARED = 0.006693421622965943;

function transformLatitude(longitudeOffset, latitudeOffset) {
  let result =
    -100.0 +
    2.0 * longitudeOffset +
    3.0 * latitudeOffset +
    0.2 * latitudeOffset * latitudeOffset +
    0.1 * longitudeOffset * latitudeOffset +
    0.2 * Math.sqrt(Math.abs(longitudeOffset));

  result +=
    ((20.0 * Math.sin(6.0 * longitudeOffset * PI) +
      20.0 * Math.sin(2.0 * longitudeOffset * PI)) *
      2.0) /
    3.0;
  result +=
    ((20.0 * Math.sin(latitudeOffset * PI) +
      40.0 * Math.sin((latitudeOffset / 3.0) * PI)) *
      2.0) /
    3.0;
  result +=
    ((160.0 * Math.sin((latitudeOffset / 12.0) * PI) +
      320.0 * Math.sin((latitudeOffset * PI) / 30.0)) *
      2.0) /
    3.0;
  return result;
}

function transformLongitude(longitudeOffset, latitudeOffset) {
  let result =
    300.0 +
    longitudeOffset +
    2.0 * latitudeOffset +
    0.1 * longitudeOffset * longitudeOffset +
    0.1 * longitudeOffset * latitudeOffset +
    0.1 * Math.sqrt(Math.abs(longitudeOffset));

  result +=
    ((20.0 * Math.sin(6.0 * longitudeOffset * PI) +
      20.0 * Math.sin(2.0 * longitudeOffset * PI)) *
      2.0) /
    3.0;
  result +=
    ((20.0 * Math.sin(longitudeOffset * PI) +
      40.0 * Math.sin((longitudeOffset / 3.0) * PI)) *
      2.0) /
    3.0;
  result +=
    ((150.0 * Math.sin((longitudeOffset / 12.0) * PI) +
      300.0 * Math.sin((longitudeOffset / 30.0) * PI)) *
      2.0) /
    3.0;
  return result;
}

export function isOutsideGcj02Coverage(longitudeDeg, latitudeDeg) {
  return (
    longitudeDeg < 72.004 ||
    longitudeDeg > 137.8347 ||
    latitudeDeg < 0.8293 ||
    latitudeDeg > 55.8271
  );
}

function roundedCoordinate(value) {
  return Number(value.toFixed(7));
}

/**
 * Converts a validated WGS84 point to the coordinate system expected by AMap.
 * Points outside the mainland-China GCJ-02 coverage bounds are left unchanged.
 */
export function wgs84ToGcj02(point) {
  const longitudeDeg = Number(point.longitude_deg);
  const latitudeDeg = Number(point.latitude_deg);

  if (!Number.isFinite(longitudeDeg) || !Number.isFinite(latitudeDeg)) {
    throw new TypeError("WGS84 point must contain finite longitude_deg and latitude_deg");
  }

  if (isOutsideGcj02Coverage(longitudeDeg, latitudeDeg)) {
    return {
      coordinate_system: "GCJ-02",
      longitude_deg: roundedCoordinate(longitudeDeg),
      latitude_deg: roundedCoordinate(latitudeDeg),
    };
  }

  let latitudeDelta = transformLatitude(longitudeDeg - 105.0, latitudeDeg - 35.0);
  let longitudeDelta = transformLongitude(longitudeDeg - 105.0, latitudeDeg - 35.0);
  const radianLatitude = (latitudeDeg / 180.0) * PI;
  let magic = Math.sin(radianLatitude);
  magic = 1 - ECCENTRICITY_SQUARED * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  latitudeDelta =
    (latitudeDelta * 180.0) /
    (((EARTH_AXIS_M * (1 - ECCENTRICITY_SQUARED)) / (magic * sqrtMagic)) * PI);
  longitudeDelta =
    (longitudeDelta * 180.0) /
    ((EARTH_AXIS_M / sqrtMagic) * Math.cos(radianLatitude) * PI);

  return {
    coordinate_system: "GCJ-02",
    longitude_deg: roundedCoordinate(longitudeDeg + longitudeDelta),
    latitude_deg: roundedCoordinate(latitudeDeg + latitudeDelta),
  };
}

/**
 * Converts an AMap GCJ-02 point back to the WGS84 contract used by GNSS
 * clients. GCJ-02 has no closed-form inverse, so this uses a bounded binary
 * search around the source point and verifies every candidate through the
 * same forward transform used for route requests.
 */
export function gcj02ToWgs84(point) {
  const longitudeDeg = Number(point.longitude_deg);
  const latitudeDeg = Number(point.latitude_deg);

  if (!Number.isFinite(longitudeDeg) || !Number.isFinite(latitudeDeg)) {
    throw new TypeError("GCJ-02 point must contain finite longitude_deg and latitude_deg");
  }

  if (isOutsideGcj02Coverage(longitudeDeg, latitudeDeg)) {
    return {
      coordinate_system: "WGS84",
      longitude_deg: roundedCoordinate(longitudeDeg),
      latitude_deg: roundedCoordinate(latitudeDeg),
    };
  }

  let minimumLongitude = longitudeDeg - 0.02;
  let maximumLongitude = longitudeDeg + 0.02;
  let minimumLatitude = latitudeDeg - 0.02;
  let maximumLatitude = latitudeDeg + 0.02;
  let candidateLongitude = longitudeDeg;
  let candidateLatitude = latitudeDeg;

  for (let iteration = 0; iteration < 32; iteration += 1) {
    candidateLongitude = (minimumLongitude + maximumLongitude) / 2;
    candidateLatitude = (minimumLatitude + maximumLatitude) / 2;
    const projected = wgs84ToGcj02({
      longitude_deg: candidateLongitude,
      latitude_deg: candidateLatitude,
    });

    if (projected.longitude_deg < longitudeDeg) {
      minimumLongitude = candidateLongitude;
    } else {
      maximumLongitude = candidateLongitude;
    }
    if (projected.latitude_deg < latitudeDeg) {
      minimumLatitude = candidateLatitude;
    } else {
      maximumLatitude = candidateLatitude;
    }
  }

  return {
    coordinate_system: "WGS84",
    longitude_deg: roundedCoordinate(candidateLongitude),
    latitude_deg: roundedCoordinate(candidateLatitude),
  };
}
