(function () {
  "use strict";

  const CANVAS_SIZE = 466;
  const LEGACY_CANVAS_SIZE = 360;
  const VEHICLE_X = CANVAS_SIZE / 2;
  const VEHICLE_Y = 196 * (CANVAS_SIZE / LEGACY_CANVAS_SIZE);
  const PIXELS_PER_METER = 0.44 * (CANVAS_SIZE / LEGACY_CANVAS_SIZE);
  const EARTH_RADIUS_M = 6_371_000;
  const PI = Math.PI;

  const ROAD_STYLES = Object.freeze({
    service: { casing: 0, width: 1.35, color: "#252c30" },
    residential: { casing: 3.8, width: 2.25, color: "#394247" },
    secondary: { casing: 6.5, width: 3.65, color: "#566168" },
  });

  function radians(value) {
    return value * PI / 180;
  }

  function transformLatitude(longitudeOffset, latitudeOffset) {
    let value = -100 + 2 * longitudeOffset + 3 * latitudeOffset
      + 0.2 * latitudeOffset * latitudeOffset
      + 0.1 * longitudeOffset * latitudeOffset
      + 0.2 * Math.sqrt(Math.abs(longitudeOffset));
    value += ((20 * Math.sin(6 * longitudeOffset * PI)
      + 20 * Math.sin(2 * longitudeOffset * PI)) * 2) / 3;
    value += ((20 * Math.sin(latitudeOffset * PI)
      + 40 * Math.sin(latitudeOffset / 3 * PI)) * 2) / 3;
    value += ((160 * Math.sin(latitudeOffset / 12 * PI)
      + 320 * Math.sin(latitudeOffset * PI / 30)) * 2) / 3;
    return value;
  }

  function transformLongitude(longitudeOffset, latitudeOffset) {
    let value = 300 + longitudeOffset + 2 * latitudeOffset
      + 0.1 * longitudeOffset * longitudeOffset
      + 0.1 * longitudeOffset * latitudeOffset
      + 0.1 * Math.sqrt(Math.abs(longitudeOffset));
    value += ((20 * Math.sin(6 * longitudeOffset * PI)
      + 20 * Math.sin(2 * longitudeOffset * PI)) * 2) / 3;
    value += ((20 * Math.sin(longitudeOffset * PI)
      + 40 * Math.sin(longitudeOffset / 3 * PI)) * 2) / 3;
    value += ((150 * Math.sin(longitudeOffset / 12 * PI)
      + 300 * Math.sin(longitudeOffset / 30 * PI)) * 2) / 3;
    return value;
  }

  // This mirrors shared/coordinates/coordinates.cpp so the OSM scene and the
  // AMap-aligned route use one coordinate frame. No synthetic geometry is
  // introduced when a source feature is absent.
  function wgs84ToGcj02(point) {
    const latitude = Number(point[0]);
    const longitude = Number(point[1]);
    if (longitude < 72.004 || longitude > 137.8347
      || latitude < 0.8293 || latitude > 55.8271) {
      return [latitude, longitude];
    }
    const axis = 6_378_245;
    const eccentricitySquared = 0.006693421622965943;
    let latitudeDelta = transformLatitude(longitude - 105, latitude - 35);
    let longitudeDelta = transformLongitude(longitude - 105, latitude - 35);
    const latitudeRadians = radians(latitude);
    let magic = Math.sin(latitudeRadians);
    magic = 1 - eccentricitySquared * magic * magic;
    const squareRootMagic = Math.sqrt(magic);
    latitudeDelta = latitudeDelta * 180
      / ((axis * (1 - eccentricitySquared) / (magic * squareRootMagic)) * PI);
    longitudeDelta = longitudeDelta * 180
      / ((axis / squareRootMagic) * Math.cos(latitudeRadians) * PI);
    return [
      Math.round((latitude + latitudeDelta) * 1e7) / 1e7,
      Math.round((longitude + longitudeDelta) * 1e7) / 1e7,
    ];
  }

  function distanceMeters(start, end) {
    const latitude = radians((start[0] + end[0]) * 0.5);
    const north = radians(end[0] - start[0]) * EARTH_RADIUS_M;
    const east = radians(end[1] - start[1])
      * Math.cos(latitude) * EARTH_RADIUS_M;
    return Math.hypot(east, north);
  }

  function routeModel(points) {
    const gcj02 = points.map(wgs84ToGcj02);
    const cumulative = new Float64Array(gcj02.length);
    for (let index = 1; index < gcj02.length; index += 1) {
      cumulative[index] = cumulative[index - 1]
        + distanceMeters(gcj02[index - 1], gcj02[index]);
    }
    return { points: gcj02, cumulative, total: cumulative.at(-1) || 0 };
  }

  function routePoint(route, offsetMeters) {
    const offset = Math.max(0, Math.min(route.total, offsetMeters));
    let endIndex = 1;
    while (endIndex < route.cumulative.length
      && route.cumulative[endIndex] < offset) endIndex += 1;
    if (endIndex >= route.points.length) return route.points.at(-1);
    const startIndex = Math.max(0, endIndex - 1);
    const segmentLength = route.cumulative[endIndex] - route.cumulative[startIndex];
    const fraction = segmentLength > 0
      ? (offset - route.cumulative[startIndex]) / segmentLength
      : 0;
    return [
      route.points[startIndex][0]
        + (route.points[endIndex][0] - route.points[startIndex][0]) * fraction,
      route.points[startIndex][1]
        + (route.points[endIndex][1] - route.points[startIndex][1]) * fraction,
    ];
  }

  function routeHeading(route, offsetMeters) {
    const behind = routePoint(route, offsetMeters - 11);
    const ahead = routePoint(route, offsetMeters + 11);
    const latitudeA = radians(behind[0]);
    const latitudeB = radians(ahead[0]);
    const longitudeDelta = radians(ahead[1] - behind[1]);
    const y = Math.sin(longitudeDelta) * Math.cos(latitudeB);
    const x = Math.cos(latitudeA) * Math.sin(latitudeB)
      - Math.sin(latitudeA) * Math.cos(latitudeB) * Math.cos(longitudeDelta);
    const baseHeading = (Math.atan2(y, x) * 180 / PI + 360) % 360;
    const progress = route.total > 0 ? offsetMeters / route.total : 0;
    return (baseHeading + Math.sin(progress * PI * 8) * 1.8 + 360) % 360;
  }

  function sourcePoint(point) {
    return [Number(point[0]) / 1e6, Number(point[1]) / 1e6];
  }

  function project(point, camera, headingDegrees) {
    const referenceLatitude = radians(camera[0]);
    const east = radians(point[1] - camera[1])
      * Math.cos(referenceLatitude) * EARTH_RADIUS_M;
    const north = radians(point[0] - camera[0]) * EARTH_RADIUS_M;
    const heading = radians(headingDegrees);
    const right = east * Math.cos(heading) - north * Math.sin(heading);
    const forward = east * Math.sin(heading) + north * Math.cos(heading);
    return [
      VEHICLE_X + right * PIXELS_PER_METER,
      VEHICLE_Y - forward * PIXELS_PER_METER,
    ];
  }

  function traceFeature(context, feature, camera, heading, closePath) {
    const points = feature.points_e6;
    if (!Array.isArray(points) || points.length < (closePath ? 3 : 2)) return false;
    context.beginPath();
    points.forEach((point, index) => {
      const [x, y] = project(sourcePoint(point), camera, heading);
      if (index === 0) context.moveTo(x, y);
      else context.lineTo(x, y);
    });
    if (closePath) context.closePath();
    return true;
  }

  function renderScene(context, scene, route, progressMeters) {
    context.save();
    context.clearRect(0, 0, CANVAS_SIZE, CANVAS_SIZE);
    context.fillStyle = "#030506";
    context.fillRect(0, 0, CANVAS_SIZE, CANVAS_SIZE);
    context.beginPath();
    context.arc(CANVAS_SIZE / 2, CANVAS_SIZE / 2, CANVAS_SIZE / 2, 0, PI * 2);
    context.clip();

    const camera = routePoint(route, progressMeters);
    const heading = routeHeading(route, progressMeters);

    for (const building of scene.buildings) {
      if (!traceFeature(context, building, camera, heading, true)) continue;
      const landmark = building.class === "landmark";
      context.fillStyle = landmark ? "#1d272d" : "#12191d";
      context.strokeStyle = landmark ? "#3b4850" : "#273137";
      context.lineWidth = landmark ? 1.45 : 1;
      context.fill();
      context.stroke();
    }

    for (const road of scene.roads) {
      const style = ROAD_STYLES[road.class] || ROAD_STYLES.service;
      if (style.casing > 0 && traceFeature(context, road, camera, heading, false)) {
        context.strokeStyle = "#11171a";
        context.lineWidth = style.casing;
        context.lineCap = "round";
        context.lineJoin = "round";
        context.stroke();
      }
      if (!traceFeature(context, road, camera, heading, false)) continue;
      context.strokeStyle = style.color;
      context.lineWidth = style.width;
      context.lineCap = "round";
      context.lineJoin = "round";
      context.stroke();
    }

    context.restore();
  }

  function validScene(scene) {
    return scene?.coordinate_system === "GCJ-02"
      && Array.isArray(scene.roads) && scene.roads.length > 0
      && Array.isArray(scene.buildings) && scene.buildings.length > 0;
  }

  function create({ ring, runtime, isVisible }) {
    if (!(ring instanceof HTMLElement) || !runtime) return null;
    const canvas = document.createElement("canvas");
    canvas.className = "map-scene-overlay";
    canvas.width = CANVAS_SIZE;
    canvas.height = CANVAS_SIZE;
    canvas.hidden = true;
    canvas.setAttribute("aria-hidden", "true");
    ring.prepend(canvas);

    const context = canvas.getContext("2d", { alpha: false });
    let scene = null;
    let route = null;
    let animationFrame = 0;
    let stopped = false;
    let displayedProgress = Number.NaN;
    let previousTime = performance.now();

    const sceneUrl = new URL(
      "../../../shared/demo_fixture/jinan_map_scene_sample.json",
      location.href,
    );
    const routeUrl = new URL(
      "../../../shared/demo_fixture/jinan_big_data_to_inspur.json",
      location.href,
    );

    function frame(now) {
      if (stopped) return;
      const visible = Boolean(scene && route && isVisible())
        && Number(runtime.getPage?.() ?? 0) === 0;
      canvas.hidden = !visible;
      ring.classList.toggle("has-map-scene", visible);

      if (visible) {
        const target = Math.max(0, Math.min(route.total,
          Number(runtime.getRouteProgress?.() ?? 0)));
        if (!Number.isFinite(displayedProgress) || Math.abs(target - displayedProgress) > 170) {
          displayedProgress = target;
        } else {
          const elapsed = Math.min(100, Math.max(0, now - previousTime));
          const blend = 1 - Math.exp(-elapsed / 135);
          displayedProgress += (target - displayedProgress) * blend;
        }
        renderScene(context, scene, route, displayedProgress);
      }
      previousTime = now;
      animationFrame = requestAnimationFrame(frame);
    }

    Promise.all([
      fetch(sceneUrl, { cache: "no-store" }).then((response) => {
        if (!response.ok) throw new Error(`scene HTTP ${response.status}`);
        return response.json();
      }),
      fetch(routeUrl, { cache: "no-store" }).then((response) => {
        if (!response.ok) throw new Error(`route HTTP ${response.status}`);
        return response.json();
      }),
    ]).then(([sceneDocument, routeDocument]) => {
      if (!validScene(sceneDocument) || !Array.isArray(routeDocument.route_wgs84)
        || routeDocument.route_wgs84.length < 2) {
        throw new Error("invalid map-scene fixture");
      }
      scene = sceneDocument;
      route = routeModel(routeDocument.route_wgs84);
      canvas.dataset.roadCount = String(scene.roads.length);
      canvas.dataset.buildingCount = String(scene.buildings.length);
      canvas.dataset.sceneRevision = String(scene.scene_revision ?? "unknown");
      window.__MOTO_MAP_SCENE_QA__ = Object.freeze({
        source: scene.source?.provider ?? "unknown",
        roads: scene.roads.length,
        buildings: scene.buildings.length,
        routePoints: route.points.length,
      });
    }).catch((error) => {
      ring.classList.remove("has-map-scene");
      canvas.remove();
      console.warn("MOTO GPS real map scene unavailable; no synthetic fallback was drawn.", error);
    });

    animationFrame = requestAnimationFrame(frame);
    return Object.freeze({
      destroy() {
        stopped = true;
        cancelAnimationFrame(animationFrame);
        ring.classList.remove("has-map-scene");
        canvas.remove();
      },
    });
  }

  window.MotoMapSceneOverlay = Object.freeze({ create });
})();
