(function () {
  "use strict";

  const buildId = "20260905-realmap1";
  const runtimeUrl = `./runtime/moto-gps-device.js?v=${buildId}`;
  const controller = window.MotoNavController;
  if (!controller) return;

  window.MotoNavLiveBridge = Object.freeze({
    onRouteRequest(command) {
      controller.handleRouteRequest(command);
    },
    onTrafficRequest(command) {
      controller.handleTrafficRequest(command);
    },
  });

  function createApi(module) {
    return Object.freeze({
      initialize: module.cwrap("moto_web_live_initialize", null, []),
      start: module.cwrap("moto_web_live_start", null, ["number", "number"]),
      cancel: module.cwrap("moto_web_live_cancel", null, []),
      setNetwork: module.cwrap("moto_web_live_set_network", null, ["number"]),
      fix: module.cwrap("moto_web_live_fix", null, ["number", "number", "number", "number", "number", "number"]),
      tick: module.cwrap("moto_web_live_tick", null, ["number"]),
      routeBegin: module.cwrap("moto_web_live_route_begin", null, ["number", "string", "number", "number", "number"]),
      routeAddPoint: module.cwrap("moto_web_live_route_add_point", null, ["number", "number"]),
      routeAddManeuver: module.cwrap("moto_web_live_route_add_maneuver", null, ["number", "number", "number", "string", "string", "number"]),
      routeAddTraffic: module.cwrap("moto_web_live_route_add_traffic", null, ["number", "number", "number"]),
      routeCommit: module.cwrap("moto_web_live_route_commit", null, ["number"]),
      routeFailed: module.cwrap("moto_web_live_route_failed", null, ["number", "number", "number"]),
      trafficBegin: module.cwrap("moto_web_live_traffic_begin", null, ["number", "string", "number", "number"]),
      trafficAddSegment: module.cwrap("moto_web_live_traffic_add_segment", null, ["number", "number", "number"]),
      trafficCommit: module.cwrap("moto_web_live_traffic_commit", null, []),
      trafficFailed: module.cwrap("moto_web_live_traffic_failed", null, ["number", "number"]),
      getNavState: module.cwrap("moto_web_live_get_nav_state", "number", []),
      getRemainingDistance: module.cwrap("moto_web_live_get_remaining_distance", "number", []),
      getRemainingDuration: module.cwrap("moto_web_live_get_remaining_duration", "number", []),
      getRouteGeneration: module.cwrap("moto_web_live_get_route_generation", "number", []),
      getRouteProgress: module.cwrap("moto_web_live_get_route_progress", "number", []),
      getOffRoute: module.cwrap("moto_web_live_get_off_route", "number", []),
      getPage: module.cwrap("moto_web_get_page", "number", []),
      demoScenario: module.cwrap("moto_web_set_scenario", null, ["number"]),
      demoPage: module.cwrap("moto_web_set_page", null, ["number"]),
      previewPhoneLifecycle: module.cwrap("moto_web_preview_phone_lifecycle", null, ["number"]),
      setPhoneConnection: module.cwrap("moto_web_set_phone_connection", null, ["number"]),
    });
  }

  function mountRuntime() {
    const canvas = controller.claimCanvas();
    window.Module = {
      canvas,
      locateFile(path) {
        return `./runtime/${path}?v=${buildId}`;
      },
      print() {},
      printErr(text) {
        if (String(text).startsWith("Looks like you are rendering without using requestAnimationFrame")) return;
        console.error(text);
      },
      onAbort(reason) {
        controller.runtimeFailed(`WASM 已中止：${String(reason)}`);
      },
      onExit(status) {
        if (status !== 0) controller.runtimeFailed(`WASM 异常退出：${status}`);
      },
      onRuntimeInitialized() {
        controller.attachRuntime(createApi(window.Module));
      },
    };

    const script = document.createElement("script");
    script.src = runtimeUrl;
    script.async = true;
    script.addEventListener("error", () => controller.runtimeFailed("WASM 加载失败"));
    document.body.append(script);
  }

  fetch(runtimeUrl, { method: "HEAD", cache: "no-store" })
    .then((response) => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      mountRuntime();
    })
    .catch(() => controller.runtimeFailed("共享导航画面未构建"));
})();
