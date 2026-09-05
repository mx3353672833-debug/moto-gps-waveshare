(function () {
  "use strict";

  const NAV_STATE = ["空闲", "等待定位", "路线规划", "导航中", "重新规划", "已到达"];
  const MANEUVER = {
    unknown: 0,
    continue: 1,
    slight_left: 2,
    left: 3,
    sharp_left: 4,
    u_turn_left: 5,
    slight_right: 6,
    right: 7,
    sharp_right: 8,
    u_turn_right: 9,
    roundabout: 10,
    exit: 11,
    arrive: 12,
  };
  const TRAFFIC = { unknown: 0, free_flow: 1, slow: 2, congested: 3, severe: 4 };
  const API_BASE = (() => {
    const override = new URLSearchParams(location.search).get("api");
    if (override) return new URL(override, location.href);
    if (["127.0.0.1", "localhost"].includes(location.hostname)) {
      return new URL("http://127.0.0.1:8787/");
    }
    return new URL("./api/", location.href);
  })();
  const LOCAL_DEMO =
    ["127.0.0.1", "localhost"].includes(location.hostname) &&
    new URLSearchParams(location.search).get("demo") === "1";
  const DEVICE_STATE_PREVIEW = (() => {
    const requested = new URLSearchParams(location.search).get("deviceState");
    const states = { offline: 0, connecting: 1, success: 2, ready: 3, planning: 4 };
    return Object.prototype.hasOwnProperty.call(states, requested)
      ? { name: requested, value: states[requested] }
      : null;
  })();
  const LOCAL_TEST_POSITION = (() => {
    if (!["127.0.0.1", "localhost"].includes(location.hostname)) return null;
    const encoded = new URLSearchParams(location.search).get("testPosition");
    if (!encoded) return null;
    const [latitude, longitude] = encoded.split(",").map(Number);
    return Number.isFinite(latitude) && Number.isFinite(longitude)
      ? { latitude, longitude }
      : null;
  })();

  const state = {
    runtime: null,
    runtimeReady: false,
    backendReady: false,
    backendProvider: "unknown",
    currentPosition: null,
    orientationHeading: null,
    destination: null,
    route: null,
    view: "setup",
    navigating: false,
    arrivedAnnounced: false,
    watchId: null,
    wakeLock: null,
    searchController: null,
    searchTimer: 0,
    locationPending: false,
    lastRouteGeneration: 0,
    lastVisibilityPause: false,
    activeRouteRequestId: null,
    activeTrafficRequestId: null,
    mapSceneOverlay: null,
  };

  const elements = {
    setupView: document.querySelector("#setup-view"),
    routeView: document.querySelector("#route-view"),
    navigationView: document.querySelector("#navigation-view"),
    homeButton: document.querySelector("#home-button"),
    systemState: document.querySelector("#system-state"),
    systemStateLabel: document.querySelector("#system-state-label"),
    systemStateDetail: document.querySelector("#system-state-detail"),
    locationCard: document.querySelector("#location-card"),
    locationTitle: document.querySelector("#location-title"),
    locationCopy: document.querySelector("#location-copy"),
    locationButton: document.querySelector("#location-button"),
    searchForm: document.querySelector("#search-form"),
    destinationQuery: document.querySelector("#destination-query"),
    clearSearch: document.querySelector("#clear-search"),
    searchFeedback: document.querySelector("#search-feedback"),
    placeResults: document.querySelector("#place-results"),
    routeBack: document.querySelector("#route-back"),
    routeDestination: document.querySelector("#route-destination"),
    routeDuration: document.querySelector("#route-duration"),
    routeDistance: document.querySelector("#route-distance"),
    routeTraffic: document.querySelector("#route-traffic"),
    routeStatus: document.querySelector("#route-status"),
    startNavigation: document.querySelector("#start-navigation"),
    rideInstrument: document.querySelector("#ride-instrument"),
    previewInstrument: document.querySelector(".instrument--preview"),
    rideDestination: document.querySelector("#ride-destination"),
    rideAlert: document.querySelector("#ride-alert"),
    endNavigation: document.querySelector("#end-navigation"),
    remainingDistance: document.querySelector("#remaining-distance"),
    arrivalTime: document.querySelector("#arrival-time"),
    rideConnection: document.querySelector("#ride-connection"),
    wakeState: document.querySelector("#wake-state"),
    locationAccuracy: document.querySelector("#location-accuracy"),
    statusDialog: document.querySelector("#status-dialog"),
    dialogBackend: document.querySelector("#dialog-backend"),
    dialogRuntime: document.querySelector("#dialog-runtime"),
    dialogLocation: document.querySelector("#dialog-location"),
    dialogNetwork: document.querySelector("#dialog-network"),
    toast: document.querySelector("#toast"),
    canvas: document.querySelector("#canvas"),
  };

  let placeholderContext = elements.canvas.getContext("2d");
  let toastTimer = 0;

  function drawPlaceholder() {
    if (!placeholderContext) return;
    const context = placeholderContext;
    const scale = elements.canvas.width / 360;
    context.fillStyle = "#050607";
    context.fillRect(0, 0, elements.canvas.width, elements.canvas.height);
    context.save();
    context.scale(scale, scale);
    context.strokeStyle = "#27302f";
    context.lineWidth = 18;
    context.lineCap = "round";
    context.beginPath();
    context.moveTo(180, 260);
    context.lineTo(180, 202);
    context.lineTo(146, 165);
    context.lineTo(213, 96);
    context.stroke();
    context.strokeStyle = "#f4f5ef";
    context.lineWidth = 9;
    context.stroke();
    context.fillStyle = "#f4f5ef";
    context.beginPath();
    context.moveTo(180, 177);
    context.lineTo(165, 208);
    context.lineTo(195, 208);
    context.closePath();
    context.fill();
    context.fillStyle = "#74817e";
    context.textAlign = "center";
    context.font = "700 11px ui-monospace, monospace";
    context.fillText("SHARED NAV LOADING", 180, 312);
    context.restore();
  }

  function showToast(message, duration = 2600) {
    window.clearTimeout(toastTimer);
    elements.toast.textContent = message;
    elements.toast.hidden = false;
    toastTimer = window.setTimeout(() => {
      elements.toast.hidden = true;
    }, duration);
  }

  function setFeedback(message, tone = "info") {
    elements.searchFeedback.dataset.tone = tone;
    elements.searchFeedback.textContent = message;
  }

  function setRouteStatus(message, tone = "info") {
    elements.routeStatus.dataset.tone = tone;
    elements.routeStatus.textContent = message;
  }

  function setRideAlert(message) {
    elements.rideAlert.textContent = message;
    elements.rideAlert.hidden = !message;
  }

  function setView(view) {
    state.view = view;
    document.body.dataset.view = view;
    elements.setupView.hidden = view !== "setup";
    elements.routeView.hidden = view !== "route";
    elements.navigationView.hidden = view !== "navigation";
    const ring = document.querySelector(".screen-ring");
    if (ring && view === "navigation") elements.rideInstrument.append(ring);
    if (ring && view === "route") elements.previewInstrument.append(ring);
    window.scrollTo({ top: 0, behavior: "instant" });
  }

  function updateSystemState() {
    let label = "可以导航";
    let detail = "定位与路线已就绪";
    let tone = "ready";

    if (!state.backendReady) {
      label = "服务未配置";
      detail = ["fixture", "disabled"].includes(state.backendProvider) ? "需要高德 Web 服务 Key" : "路线接口不可用";
      tone = "error";
    } else if (!state.runtimeReady) {
      label = "画面加载中";
      detail = "共享 LVGL 运行时";
      tone = "loading";
    } else if (!state.currentPosition) {
      label = state.locationPending ? "正在定位" : "等待定位";
      detail = "点击启用当前位置";
      tone = "loading";
    }

    elements.systemState.dataset.tone = tone;
    elements.systemStateLabel.textContent = label;
    elements.systemStateDetail.textContent = detail;
    elements.dialogBackend.textContent = state.backendReady ? "已连接高德" : "不可用于真实导航";
    elements.dialogRuntime.textContent = state.runtimeReady ? "LVGL 已运行" : "加载中";
    elements.dialogLocation.textContent = state.currentPosition ? `已定位 · ±${Math.round(state.currentPosition.coords.accuracy)} m` : "未授权";
    elements.dialogNetwork.textContent = navigator.onLine ? "在线" : "离线";

    const canSearch = state.backendReady && state.runtimeReady && Boolean(state.currentPosition);
    elements.destinationQuery.disabled = !canSearch;
    if (canSearch && document.activeElement !== elements.destinationQuery && state.view === "setup") {
      elements.destinationQuery.placeholder = "输入城市和地点，例如：济南奥体中心";
    }
  }

  async function fetchJson(url, options = {}) {
    const response = await fetch(url, {
      cache: "no-store",
      ...options,
      headers: { Accept: "application/json", ...(options.headers ?? {}) },
    });
    let body;
    try {
      body = await response.json();
    } catch {
      throw new Error(`服务返回了无法读取的数据（HTTP ${response.status}）`);
    }
    if (!response.ok) {
      const error = new Error(body?.error?.message || `请求失败（HTTP ${response.status}）`);
      error.code = body?.error?.code;
      error.retryable = body?.error?.retryable;
      throw error;
    }
    return body;
  }

  async function checkBackend() {
    if (LOCAL_DEMO) {
      state.backendProvider = "fixture";
      state.backendReady = true;
      updateSystemState();
      return;
    }
    try {
      const health = await fetchJson(new URL("healthz", API_BASE));
      state.backendProvider = String(health.provider ?? "unknown");
      state.backendReady = health.ready_for_live_navigation === true;
      if (!state.backendReady) {
        setFeedback("服务器已启动，但还没有配置高德 Web 服务 Key。", "error");
      }
    } catch {
      state.backendProvider = "offline";
      state.backendReady = false;
      setFeedback("真实路线服务尚未上线，当前无法开始导航。", "error");
    }
    updateSystemState();
  }

  function normalizeHeading(coords) {
    if (Number.isFinite(coords.heading) && Number(coords.speed ?? 0) >= 1) {
      return Number(coords.heading);
    }
    if (Number.isFinite(state.orientationHeading)) return state.orientationHeading;
    return 0;
  }

  function feedPosition(position) {
    state.currentPosition = position;
    state.locationPending = false;
    const accuracy = Math.round(position.coords.accuracy);
    elements.locationCard.classList.add("is-ready");
    elements.locationTitle.textContent = accuracy <= 25 ? "位置已锁定" : "定位精度较低";
    elements.locationCopy.textContent = `当前精度约 ±${accuracy} 米${accuracy > 50 ? "，请到开阔处等待" : "，持续实时更新"}`;
    elements.locationButton.textContent = "重新定位";
    elements.locationAccuracy.textContent = `GPS ${accuracy} m`;
    elements.dialogLocation.textContent = `已定位 · ±${accuracy} m`;

    if (state.runtimeReady) {
      const coords = position.coords;
      state.runtime.fix(
        coords.latitude,
        coords.longitude,
        coords.accuracy,
        Number.isFinite(coords.speed) ? coords.speed : 0,
        normalizeHeading(coords),
        position.timestamp || Date.now(),
      );
    }
    updateSystemState();
  }

  function locationErrorMessage(error) {
    if (error?.code === 1) return "定位权限被拒绝，请在 Safari 网站设置中允许位置访问。";
    if (error?.code === 2) return "暂时无法取得位置，请到室外或检查系统定位服务。";
    if (error?.code === 3) return "定位超时，请稍后重试。";
    return "定位失败，请检查系统定位权限。";
  }

  function onLocationError(error) {
    state.locationPending = false;
    elements.locationCard.classList.remove("is-ready");
    elements.locationTitle.textContent = "没有拿到位置";
    elements.locationCopy.textContent = locationErrorMessage(error);
    elements.locationButton.textContent = "重试定位";
    setFeedback(locationErrorMessage(error), "error");
    updateSystemState();
  }

  function positionOptions() {
    return { enableHighAccuracy: true, maximumAge: 1000, timeout: 12000 };
  }

  function startPositionWatch() {
    if (state.watchId !== null) navigator.geolocation.clearWatch(state.watchId);
    state.watchId = navigator.geolocation.watchPosition(feedPosition, onLocationError, positionOptions());
  }

  function requestLocation() {
    if (LOCAL_TEST_POSITION) {
      const position = {
        coords: {
          latitude: LOCAL_TEST_POSITION.latitude,
          longitude: LOCAL_TEST_POSITION.longitude,
          accuracy: 4,
          speed: 12,
          heading: 35,
        },
        timestamp: Date.now(),
      };
      feedPosition(position);
      setFeedback("本地测试位置已就绪，现在输入目的地。", "info");
      elements.destinationQuery.focus({ preventScroll: true });
      return;
    }
    if (!window.isSecureContext || !navigator.geolocation) {
      onLocationError({ code: 0 });
      elements.locationCopy.textContent = "手机定位要求 HTTPS 页面和支持定位的浏览器。";
      return;
    }
    state.locationPending = true;
    elements.locationTitle.textContent = "正在锁定位置";
    elements.locationCopy.textContent = "请在系统提示中允许精确位置。";
    elements.locationButton.textContent = "定位中…";
    updateSystemState();
    navigator.geolocation.getCurrentPosition(
      (position) => {
        feedPosition(position);
        startPositionWatch();
        if (state.backendReady && state.runtimeReady) {
          setFeedback("位置已就绪，现在输入目的地。", "info");
          elements.destinationQuery.focus({ preventScroll: true });
        }
      },
      onLocationError,
      positionOptions(),
    );
  }

  function renderPlaces(places) {
    elements.placeResults.replaceChildren();
    for (const [index, place] of places.entries()) {
      const item = document.createElement("li");
      item.className = "place-result";
      const button = document.createElement("button");
      button.type = "button";
      const indexLabel = document.createElement("span");
      indexLabel.className = "result-index";
      indexLabel.textContent = String(index + 1).padStart(2, "0");
      const copy = document.createElement("span");
      copy.className = "result-copy";
      const name = document.createElement("b");
      name.textContent = place.name || "未命名地点";
      const address = document.createElement("span");
      address.textContent = [place.display_area, place.address].filter(Boolean).join(" · ") || "地址未提供";
      const arrow = document.createElement("span");
      arrow.className = "result-arrow";
      arrow.textContent = "›";
      copy.append(name, address);
      button.append(indexLabel, copy, arrow);
      button.addEventListener("click", () => selectDestination(place));
      item.append(button);
      elements.placeResults.append(item);
    }
  }

  async function searchPlaces(rawQuery) {
    const query = String(rawQuery ?? "").trim();
    elements.clearSearch.hidden = query.length === 0;
    if (query.length < 2) {
      if (state.searchController) state.searchController.abort();
      elements.placeResults.replaceChildren();
      setFeedback(query.length ? "至少输入两个字。" : "输入城市和目的地名称。", "info");
      return;
    }
    if (!state.backendReady) {
      setFeedback("高德路线服务尚未配置，暂时不能搜索。", "error");
      return;
    }

    if (state.searchController) state.searchController.abort();
    state.searchController = new AbortController();
    setFeedback("正在搜索高德地点…", "loading");
    try {
      const url = new URL("v1/places", API_BASE);
      url.searchParams.set("keywords", query);
      const result = await fetchJson(url, { signal: state.searchController.signal });
      renderPlaces(Array.isArray(result.places) ? result.places : []);
      setFeedback(result.places?.length ? `找到 ${result.places.length} 个地点` : "没有找到，试试加上城市或区县。", "info");
    } catch (error) {
      if (error?.name === "AbortError") return;
      elements.placeResults.replaceChildren();
      setFeedback(error?.code === "SERVER_MISCONFIGURED" ? "服务器缺少高德 Key。" : "搜索失败，请检查网络后重试。", "error");
    }
  }

  function currentFix() {
    if (!state.currentPosition) return null;
    return {
      coordinate_system: "WGS84",
      longitude_deg: state.currentPosition.coords.longitude,
      latitude_deg: state.currentPosition.coords.latitude,
    };
  }

  function selectedDestinationPoint() {
    return state.destination?.location ?? null;
  }

  function routeRequestBody(command) {
    const destination = selectedDestinationPoint();
    return {
      protocol_version: 1,
      request_id: command.requestId,
      route_mode: "driving",
      origin: {
        coordinate_system: "WGS84",
        longitude_deg: command.origin.longitude,
        latitude_deg: command.origin.latitude,
      },
      destination: {
        coordinate_system: "WGS84",
        longitude_deg: command.destination.longitude,
        latitude_deg: command.destination.latitude,
      },
      is_reroute: Boolean(command.isReroute),
      ...(state.route?.route_id ? { previous_route_id: state.route.route_id } : {}),
      ...(state.destination?.id ? { destination_poi_id: state.destination.id } : {}),
    };
  }

  function feedRouteToRuntime(requestId, route) {
    state.runtime.routeBegin(
      requestId,
      String(route.route_id ?? ""),
      Number(route.total_distance_m ?? 0),
      Number(route.total_duration_s ?? 0),
      Number(route.generated_at_ms ?? Date.now()),
    );
    for (const point of route.polyline ?? []) {
      state.runtime.routeAddPoint(Number(point.latitude_deg), Number(point.longitude_deg));
    }
    for (const maneuver of route.maneuvers ?? []) {
      state.runtime.routeAddManeuver(
        Number(maneuver.id ?? 0),
        MANEUVER[maneuver.type] ?? MANEUVER.unknown,
        Number(maneuver.route_offset_m ?? 0),
        String(maneuver.road_name ?? ""),
        String(maneuver.instruction ?? ""),
        Number(maneuver.roundabout_exit ?? 0),
      );
    }
    for (const segment of route.traffic ?? []) {
      state.runtime.routeAddTraffic(
        Number(segment.start_offset_m ?? 0),
        Number(segment.end_offset_m ?? 0),
        TRAFFIC[segment.level] ?? TRAFFIC.unknown,
      );
    }
    state.runtime.routeCommit(Date.now());
  }

  function trafficLabel(route) {
    let severity = 0;
    for (const segment of route?.traffic ?? []) {
      severity = Math.max(severity, TRAFFIC[segment.level] ?? 0);
    }
    return ["未知", "畅通", "缓行", "拥堵", "严重拥堵"][severity];
  }

  function formatDistance(meters) {
    const value = Number(meters);
    if (!Number.isFinite(value)) return "--";
    if (value >= 1000) return `${(value / 1000).toFixed(value >= 10000 ? 0 : 1)} km`;
    return `${Math.max(0, Math.round(value))} m`;
  }

  function formatDuration(seconds) {
    const minutes = Math.max(0, Math.round(Number(seconds ?? 0) / 60));
    if (minutes < 60) return `${minutes} 分钟`;
    return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`;
  }

  function updateRouteSummary(route) {
    elements.routeDuration.textContent = formatDuration(route.total_duration_s);
    elements.routeDistance.textContent = formatDistance(route.total_distance_m);
    elements.routeTraffic.textContent = trafficLabel(route);
  }

  async function handleRouteRequest(command) {
    if (!state.destination || !state.runtime) return;
    state.activeRouteRequestId = Number(command.requestId);
    if (command.isReroute) {
      if (state.navigating) setRideAlert("已偏离路线，正在重新规划…");
      setRouteStatus("检测到偏航，正在从当前位置重新规划…");
    } else {
      setRouteStatus("正在根据实时路况规划路线…");
    }

    try {
      const envelope = await fetchJson(new URL("v1/routes", API_BASE), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(routeRequestBody(command)),
      });
      if (state.activeRouteRequestId !== Number(command.requestId)) return;
      if (Number(envelope.request_id) !== Number(command.requestId)) {
        throw new Error("route response request_id mismatch");
      }
      state.activeRouteRequestId = null;
      state.route = envelope.route;
      feedRouteToRuntime(command.requestId, envelope.route);
      updateRouteSummary(envelope.route);
      elements.startNavigation.disabled = false;
      setRouteStatus(command.isReroute ? "路线已重新规划。" : "路线已按当前实时路况生成。", "info");
      if (state.navigating) {
        setRideAlert("");
        showToast(command.isReroute ? "已切换到新路线" : "路线已更新");
      }
    } catch (error) {
      if (state.activeRouteRequestId !== Number(command.requestId)) return;
      state.activeRouteRequestId = null;
      state.runtime.routeFailed(command.requestId, error?.retryable === false ? 0 : 1, Date.now());
      const message = error?.code === "SERVER_MISCONFIGURED" ? "服务器还没有配置高德 Key。" : "路线规划失败，请检查网络后重试。";
      setRouteStatus(message, "error");
      if (state.navigating) setRideAlert("重规划失败，继续沿用当前路线。联网后会自动重试。");
    }
  }

  async function handleTrafficRequest(command) {
    const origin = currentFix();
    const destination = selectedDestinationPoint();
    if (!origin || !destination || !state.runtime) {
      state.runtime?.trafficFailed(command.requestId, Date.now());
      return;
    }
    state.activeTrafficRequestId = Number(command.requestId);
    try {
      const body = {
        protocol_version: 1,
        request_id: command.requestId,
        route_mode: "driving",
        origin,
        destination,
        is_reroute: false,
        previous_route_id: command.routeId,
        ...(state.destination?.id ? { destination_poi_id: state.destination.id } : {}),
      };
      const envelope = await fetchJson(new URL("v1/routes", API_BASE), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (state.activeTrafficRequestId !== Number(command.requestId)) return;
      state.activeTrafficRequestId = null;
      const progress = Math.max(0, Number(state.runtime.getRouteProgress()));
      state.runtime.trafficBegin(
        command.requestId,
        command.routeId,
        Number(envelope.route.total_duration_s ?? 0),
        Date.now(),
      );
      for (const segment of envelope.route.traffic ?? []) {
        state.runtime.trafficAddSegment(
          progress + Number(segment.start_offset_m ?? 0),
          progress + Number(segment.end_offset_m ?? 0),
          TRAFFIC[segment.level] ?? TRAFFIC.unknown,
        );
      }
      state.runtime.trafficCommit();
      elements.routeTraffic.textContent = trafficLabel(envelope.route);
    } catch {
      if (state.activeTrafficRequestId !== Number(command.requestId)) return;
      state.activeTrafficRequestId = null;
      state.runtime.trafficFailed(command.requestId, Date.now());
    }
  }

  function selectDestination(place) {
    if (!state.runtime || !state.currentPosition) {
      showToast("定位和共享导航画面还未就绪");
      return;
    }
    state.destination = place;
    state.route = null;
    state.navigating = false;
    state.arrivedAnnounced = false;
    state.activeRouteRequestId = null;
    state.activeTrafficRequestId = null;
    elements.routeDestination.textContent = [place.name, place.display_area].filter(Boolean).join(" · ");
    elements.rideDestination.textContent = place.name || "目的地";
    elements.routeDuration.textContent = "--";
    elements.routeDistance.textContent = "--";
    elements.routeTraffic.textContent = "--";
    elements.startNavigation.disabled = true;
    setRouteStatus("正在规划实时路线…");
    setView("route");
    state.runtime.setNetwork(navigator.onLine ? 1 : 0);
    state.runtime.start(place.location.latitude_deg, place.location.longitude_deg);
    feedPosition(state.currentPosition);
  }

  async function requestOrientationPermission() {
    if (typeof window.DeviceOrientationEvent === "undefined") return;
    if (typeof window.DeviceOrientationEvent.requestPermission === "function") {
      try {
        const result = await window.DeviceOrientationEvent.requestPermission();
        if (result !== "granted") return;
      } catch {
        return;
      }
    }
    window.addEventListener(
      "deviceorientation",
      (event) => {
        if (Number.isFinite(event.webkitCompassHeading)) {
          state.orientationHeading = Number(event.webkitCompassHeading);
        } else if (event.absolute && Number.isFinite(event.alpha)) {
          state.orientationHeading = (360 - Number(event.alpha)) % 360;
        }
      },
      { passive: true },
    );
  }

  async function acquireWakeLock() {
    if (!("wakeLock" in navigator) || document.visibilityState !== "visible") {
      elements.wakeState.innerHTML = "<i></i> 请保持屏幕常亮";
      return false;
    }
    try {
      state.wakeLock = await navigator.wakeLock.request("screen");
      elements.wakeState.innerHTML = "<i></i> 屏幕常亮中";
      state.wakeLock.addEventListener("release", () => {
        state.wakeLock = null;
        elements.wakeState.innerHTML = "<i></i> 常亮已释放";
      });
      return true;
    } catch {
      elements.wakeState.innerHTML = "<i></i> 请手动保持亮屏";
      return false;
    }
  }

  async function startNavigation() {
    if (!state.route || !state.currentPosition) return;
    state.navigating = true;
    state.arrivedAnnounced = false;
    await Promise.allSettled([requestOrientationPermission(), acquireWakeLock()]);
    setView("navigation");
    setRideAlert("");
    if ("speechSynthesis" in window) {
      const utterance = new SpeechSynthesisUtterance("导航开始，请保持页面在前台");
      utterance.lang = "zh-CN";
      window.speechSynthesis.cancel();
      window.speechSynthesis.speak(utterance);
    }
  }

  async function cancelNavigation({ confirmEnd = false } = {}) {
    if (confirmEnd && state.navigating && !window.confirm("结束当前导航并返回搜索？")) return;
    state.navigating = false;
    state.destination = null;
    state.route = null;
    state.arrivedAnnounced = false;
    state.activeRouteRequestId = null;
    state.activeTrafficRequestId = null;
    state.runtime?.cancel();
    if (state.wakeLock) {
      try { await state.wakeLock.release(); } catch {}
      state.wakeLock = null;
    }
    if ("speechSynthesis" in window) window.speechSynthesis.cancel();
    setRideAlert("");
    setView("setup");
  }

  function updateRideReadouts() {
    if (!state.runtimeReady || !state.runtime) return;
    const navState = state.runtime.getNavState();
    const remainingDistance = state.runtime.getRemainingDistance();
    const remainingDuration = state.runtime.getRemainingDuration();
    elements.remainingDistance.textContent = formatDistance(remainingDistance);
    const arrival = new Date(Date.now() + Math.max(0, remainingDuration) * 1000);
    elements.arrivalTime.textContent = remainingDuration > 0
      ? arrival.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false })
      : "--:--";
    elements.rideConnection.textContent = navigator.onLine ? "在线" : "离线";

    if (state.navigating && navState === 4) setRideAlert("已偏离路线，正在重新规划…");
    if (state.navigating && navState === 5 && !state.arrivedAnnounced) {
      state.arrivedAnnounced = true;
      setRideAlert("已到达目的地");
      if ("speechSynthesis" in window) {
        const utterance = new SpeechSynthesisUtterance("已到达目的地");
        utterance.lang = "zh-CN";
        window.speechSynthesis.speak(utterance);
      }
    }

    const generation = state.runtime.getRouteGeneration();
    if (generation !== state.lastRouteGeneration) state.lastRouteGeneration = generation;
    elements.dialogRuntime.textContent = `LVGL 已运行 · ${NAV_STATE[navState] ?? "未知"}`;
  }

  function attachRuntime(runtime) {
    state.runtime = runtime;
    state.runtimeReady = true;
    runtime.initialize();
    if (DEVICE_STATE_PREVIEW) {
      state.currentPosition = {
        coords: {
          latitude: 36.6745,
          longitude: 117.1305,
          accuracy: 4,
          speed: 0,
          heading: 0,
        },
        timestamp: Date.now(),
      };
      state.destination = { name: "圆屏连接状态预览", location: {} };
      state.navigating = false;
      elements.routeDestination.textContent = "圆屏连接状态预览";
      setRouteStatus("当前为固件连接生命周期验收画面。", "ready");
      setView("route");
      updateSystemState();
      // Emscripten invokes onRuntimeInitialized immediately before entering
      // main(). Defer one browser task so moto_nav_ui_create() cannot reset
      // the requested lifecycle frame.
      window.setTimeout(
        () => runtime.previewPhoneLifecycle(DEVICE_STATE_PREVIEW.value),
        0,
      );
      return;
    }
    if (LOCAL_DEMO) {
      state.currentPosition = {
        coords: {
          latitude: 36.6748039,
          longitude: 117.1224488,
          accuracy: 4,
          speed: 12,
          heading: 180,
        },
        timestamp: Date.now(),
      };
      state.destination = {
        name: "山东省大数据产业基地D栋 → 浪潮集团(总部)",
        location: {},
      };
      state.navigating = true;
      elements.rideDestination.textContent = "D栋 → 浪潮集团总部";
      setView("navigation");
      updateSystemState();
      // onRuntimeInitialized fires immediately before Emscripten enters
      // main(); wait one browser task so moto_nav_ui_create() cannot reset
      // the connected state and real-road fixture we are about to install.
      window.setTimeout(() => {
        runtime.setPhoneConnection(2);
        runtime.demoScenario(0);
      }, 0);
      state.mapSceneOverlay?.destroy();
      state.mapSceneOverlay = window.MotoMapSceneOverlay?.create({
        ring: document.querySelector(".screen-ring"),
        runtime,
        isVisible: () => state.view === "navigation",
      }) ?? null;
      return;
    }
    window.setTimeout(() => runtime.setPhoneConnection(2), 0);
    runtime.setNetwork(navigator.onLine ? 1 : 0);
    if (state.currentPosition) feedPosition(state.currentPosition);
    updateSystemState();
  }

  function claimCanvas() {
    if (!placeholderContext) return elements.canvas;
    const replacement = elements.canvas.cloneNode(false);
    elements.canvas.replaceWith(replacement);
    elements.canvas = replacement;
    placeholderContext = null;
    return replacement;
  }

  function runtimeFailed(message) {
    state.runtimeReady = false;
    elements.dialogRuntime.textContent = message;
    setFeedback("共享导航画面加载失败，请刷新页面。", "error");
    updateSystemState();
  }

  function networkChanged() {
    state.runtime?.setNetwork(navigator.onLine ? 1 : 0);
    elements.dialogNetwork.textContent = navigator.onLine ? "在线" : "离线";
    elements.rideConnection.textContent = navigator.onLine ? "在线" : "离线";
    if (!navigator.onLine && state.navigating) {
      setRideAlert("网络已断开，继续沿用已下载路线；恢复后自动刷新。 ");
    } else if (navigator.onLine && state.navigating) {
      setRideAlert("");
      state.runtime?.tick(Date.now());
    }
  }

  function visibilityChanged() {
    if (!state.navigating) return;
    if (document.visibilityState === "hidden") {
      state.lastVisibilityPause = true;
      return;
    }
    acquireWakeLock();
    if (state.lastVisibilityPause) {
      state.lastVisibilityPause = false;
      setRideAlert("页面刚恢复，正在重新校准位置与路线…");
      navigator.geolocation?.getCurrentPosition(
        (position) => {
          feedPosition(position);
          state.runtime?.tick(Date.now());
          window.setTimeout(() => setRideAlert(""), 1800);
        },
        onLocationError,
        positionOptions(),
      );
    }
  }

  elements.locationButton.addEventListener("click", requestLocation);
  elements.searchForm.addEventListener("submit", (event) => {
    event.preventDefault();
    window.clearTimeout(state.searchTimer);
    searchPlaces(elements.destinationQuery.value);
  });
  elements.destinationQuery.addEventListener("input", () => {
    window.clearTimeout(state.searchTimer);
    const query = elements.destinationQuery.value;
    elements.clearSearch.hidden = query.length === 0;
    state.searchTimer = window.setTimeout(() => searchPlaces(query), 480);
  });
  elements.clearSearch.addEventListener("click", () => {
    elements.destinationQuery.value = "";
    elements.placeResults.replaceChildren();
    elements.clearSearch.hidden = true;
    setFeedback("输入城市和目的地名称。", "info");
    elements.destinationQuery.focus();
  });
  elements.routeBack.addEventListener("click", () => cancelNavigation());
  elements.startNavigation.addEventListener("click", startNavigation);
  elements.endNavigation.addEventListener("click", () => cancelNavigation({ confirmEnd: true }));
  elements.homeButton.addEventListener("click", () => {
    if (state.view !== "setup") cancelNavigation({ confirmEnd: state.navigating });
  });
  elements.systemState.addEventListener("click", () => elements.statusDialog.showModal());
  window.addEventListener("online", networkChanged);
  window.addEventListener("offline", networkChanged);
  document.addEventListener("visibilitychange", visibilityChanged);

  window.MotoNavController = Object.freeze({
    attachRuntime,
    claimCanvas,
    runtimeFailed,
    handleRouteRequest,
    handleTrafficRequest,
  });

  drawPlaceholder();
  setView("setup");
  updateSystemState();
  checkBackend();
  window.setInterval(() => {
    if (state.runtimeReady) state.runtime.tick(Date.now());
    updateRideReadouts();
  }, 1000);
})();
