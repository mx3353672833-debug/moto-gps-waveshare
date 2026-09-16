/* Existing Glimpse screen recordings, reused without synthesizing screen data.
 * HTML: [data-round-demo] > [data-round-screen], plus buttons anywhere inside
 * the container with data-round-tab="nav|speed|music|compass|...".
 * Optional: [data-round-tabs], [data-round-play], [data-round-description].
 * Public API: RoundScreenDemos.init(root), select(container, mode), play,
 * pause, destroy. Containers may be Elements or CSS selectors.
 */
(() => {
  "use strict";

  const modes = Object.freeze({
    nav: { label: "导航", description: "查看导航页面的动态演示。路线、位置和数值为演示内容。" },
    speed: { label: "速度", description: "查看速度页面的动态读数。画面中的数值为演示内容。" },
    music: { label: "音乐", description: "查看音乐页面的曲目与播放状态演示。" },
    compass: { label: "指南针", description: "查看方向变化时的指南针页面演示。" },
    offline: { label: "等待手机", description: "圆屏等待手机连接时的界面演示。" },
    connecting: { label: "建立连接", description: "手机与圆屏建立蓝牙连接时的界面演示。" },
    success: { label: "连接成功", description: "连接成功后，圆屏进入待命状态。" },
    ready: { label: "等待目的地", description: "圆屏已就绪，在手机 App 中选择目的地。" },
    planning: { label: "规划路线", description: "手机规划路线时，圆屏显示等待状态。" },
  });
  const controllers = new Map();
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const saveData = Boolean(navigator.connection && navigator.connection.saveData);
  let nextID = 0;

  function element(value) {
    return typeof value === "string" ? document.querySelector(value) : value;
  }

  function create(container) {
    if (controllers.has(container)) return controllers.get(container);
    const screen = container.querySelector("[data-round-screen]");
    if (!screen) return null;

    const buttons = Array.from(container.querySelectorAll("[data-round-tab]"))
      .filter((button) => Object.hasOwn(modes, button.dataset.roundTab));
    const descriptions = container.querySelectorAll("[data-round-description]");
    const playButtons = container.querySelectorAll("[data-round-play]");
    const cleanup = [];
    const id = ++nextID;
    if (!screen.id) screen.id = `round-demo-screen-${id}`;

    const poster = screen.querySelector("[data-round-poster]") || document.createElement("img");
    poster.dataset.roundPoster = "";
    poster.classList.add("round-demo-poster");
    poster.alt = "";
    poster.setAttribute("aria-hidden", "true");
    poster.decoding = "async";
    poster.loading = "lazy";
    if (!poster.parentNode) screen.append(poster);

    const video = document.createElement("video");
    video.className = "round-demo-video";
    video.muted = true;
    video.defaultMuted = true;
    video.playsInline = true;
    video.loop = true;
    video.preload = "none";
    video.setAttribute("muted", "");
    video.setAttribute("playsinline", "");
    video.setAttribute("aria-hidden", "true");
    video.disablePictureInPicture = true;
    video.tabIndex = -1;
    screen.append(video);

    const fallback = document.createElement("p");
    fallback.className = "round-demo-fallback";
    fallback.hidden = true;
    fallback.setAttribute("role", "status");
    screen.append(fallback);

    const state = {
      mode: null,
      source: null,
      generation: 0,
      visible: false,
      userPaused: false,
      manualPlayback: false,
      failed: false,
      playing: false,
      attempting: false,
      destroyed: false,
    };
    const base = new URL(container.dataset.demoBase || "./assets/videos/", document.baseURI);
    const listen = (target, name, listener, options) => {
      target.addEventListener(name, listener, options);
      cleanup.push(() => target.removeEventListener(name, listener, options));
    };

    function syncControls() {
      container.dataset.demoPlaying = String(state.playing);
      playButtons.forEach((button) => {
        button.textContent = state.playing ? "暂停演示" : "播放演示";
        button.setAttribute("aria-label", `${state.playing ? "暂停" : "播放"}${modes[state.mode].label}演示`);
        button.setAttribute("aria-controls", screen.id);
      });
    }

    function allowedToPlay() {
      return !state.destroyed && state.visible && !document.hidden && !state.userPaused &&
        !state.failed && ((!reducedMotion.matches && !saveData) || state.manualPlayback);
    }

    function syncPlayback() {
      const allowed = allowedToPlay();
      video.autoplay = allowed;
      if (!allowed) {
        video.pause();
        state.playing = false;
        syncControls();
        return;
      }
      if (!state.source) {
        // Attach only the selected source, only when visible. Inactive modes
        // are never separate video elements and cannot download in parallel.
        state.source = new URL(`${state.mode}.mp4`, base).href;
        video.src = state.source;
        video.load();
      }
      if (state.attempting || !video.paused) return;
      const generation = state.generation;
      state.attempting = true;
      const promise = video.play();
      if (!promise || typeof promise.then !== "function") {
        state.attempting = false;
        return;
      }
      promise.then(() => {
        if (state.destroyed || generation !== state.generation) return;
        state.attempting = false;
        if (!allowedToPlay()) video.pause();
      }).catch((error) => {
        if (state.destroyed || generation !== state.generation) return;
        state.attempting = false;
        // A source switch or offscreen pause can abort an outstanding play.
        // An autoplay denial keeps the poster and offers an explicit button.
        if (error.name === "AbortError") {
          // Visibility can return before a pending pause abort is delivered.
          // Reconcile once more so the visible screen does not stay frozen.
          if (allowedToPlay()) requestAnimationFrame(() => {
            if (!state.destroyed && generation === state.generation) syncPlayback();
          });
        } else {
          state.userPaused = true;
          state.playing = false;
          syncControls();
        }
      });
    }

    function choose(mode, manual = false) {
      if (!Object.hasOwn(modes, mode)) return false;
      if (state.mode === mode) return true;
      state.generation += 1;
      state.mode = mode;
      state.source = null;
      state.failed = false;
      state.playing = false;
      state.attempting = false;
      video.pause();
      if (video.hasAttribute("src")) {
        video.removeAttribute("src");
        video.load();
      }
      screen.dataset.videoReady = "false";
      fallback.hidden = true;
      const posterURL = new URL(`${mode}-poster.jpg`, base).href;
      poster.src = posterURL;
      video.poster = posterURL;
      container.dataset.demoMode = mode;

      let selectedButton;
      buttons.forEach((button, index) => {
        const selected = button.dataset.roundTab === mode;
        if (selected) selectedButton = button;
        button.classList.toggle("is-active", selected);
        button.dataset.demoSelected = String(selected);
        if (button.tagName === "BUTTON") button.type = "button";
        const tablist = button.closest("[data-round-tabs]");
        if (tablist && container.contains(tablist)) {
          tablist.setAttribute("role", "tablist");
          button.setAttribute("role", "tab");
          button.setAttribute("aria-selected", String(selected));
          button.removeAttribute("aria-pressed");
          button.tabIndex = selected ? 0 : -1;
          if (!button.id) button.id = `round-demo-tab-${id}-${index}`;
        } else {
          button.setAttribute("aria-pressed", String(selected));
        }
        button.setAttribute("aria-controls", screen.id);
      });
      const label = `${container.dataset.demoLabel || "圆屏演示"}：${modes[mode].label}`;
      screen.setAttribute("aria-label", label);
      if (selectedButton && selectedButton.getAttribute("role") === "tab") {
        screen.setAttribute("role", "tabpanel");
        screen.setAttribute("aria-labelledby", selectedButton.id);
        screen.tabIndex = 0;
      } else {
        screen.setAttribute("role", "img");
        screen.removeAttribute("aria-labelledby");
        screen.removeAttribute("tabindex");
      }
      const description = selectedButton?.dataset.demoDescription || modes[mode].description;
      descriptions.forEach((node) => { node.textContent = description; });
      syncControls();
      syncPlayback();
      container.dispatchEvent(new CustomEvent("rounddemochange", {
        bubbles: true, detail: { mode, label: modes[mode].label, description, manual },
      }));
      return true;
    }

    function play() {
      state.userPaused = false;
      state.manualPlayback = true;
      if (state.failed) {
        state.failed = false;
        state.source = null;
        fallback.hidden = true;
      }
      syncPlayback();
    }

    function pause() {
      state.userPaused = true;
      state.manualPlayback = false;
      syncPlayback();
    }

    buttons.forEach((button) => {
      listen(button, "click", () => choose(button.dataset.roundTab, true));
      listen(button, "keydown", (event) => {
        if (event.altKey || event.ctrlKey || event.metaKey) return;
        const list = button.closest("[data-round-tabs]");
        const peers = list ? buttons.filter((candidate) => candidate.closest("[data-round-tabs]") === list) : buttons;
        const vertical = list?.getAttribute("aria-orientation") === "vertical";
        const backward = vertical ? "ArrowUp" : "ArrowLeft";
        const forward = vertical ? "ArrowDown" : "ArrowRight";
        let index = peers.indexOf(button);
        if (event.key === backward) index = (index - 1 + peers.length) % peers.length;
        else if (event.key === forward) index = (index + 1) % peers.length;
        else if (event.key === "Home") index = 0;
        else if (event.key === "End") index = peers.length - 1;
        else return;
        event.preventDefault();
        peers[index].focus();
        choose(peers[index].dataset.roundTab, true);
      });
    });
    playButtons.forEach((button) => {
      if (button.tagName === "BUTTON") button.type = "button";
      listen(button, "click", () => { state.playing ? pause() : play(); });
    });
    listen(video, "loadeddata", () => {
      if (state.source && video.currentSrc === state.source && !state.failed) {
        screen.dataset.videoReady = "true";
      }
    });
    listen(video, "playing", () => {
      if (!allowedToPlay()) { video.pause(); return; }
      state.playing = true;
      screen.dataset.videoReady = "true";
      syncControls();
    });
    listen(video, "pause", () => { state.playing = false; syncControls(); });
    listen(video, "error", () => {
      if (!state.source || (video.currentSrc && video.currentSrc !== state.source)) return;
      state.failed = true;
      state.playing = false;
      state.attempting = false;
      screen.dataset.videoReady = "false";
      fallback.textContent = "视频暂时无法播放，当前显示该页面的静态画面。";
      fallback.hidden = false;
      syncControls();
    });
    listen(poster, "error", () => {
      if (screen.dataset.videoReady === "true") return;
      fallback.textContent = `${modes[state.mode].label}演示暂时无法显示。`;
      fallback.hidden = false;
    });
    listen(document, "visibilitychange", syncPlayback);
    const motionChanged = () => {
      state.manualPlayback = false;
      syncPlayback();
    };
    if (reducedMotion.addEventListener) listen(reducedMotion, "change", motionChanged);
    else {
      reducedMotion.addListener(motionChanged);
      cleanup.push(() => reducedMotion.removeListener(motionChanged));
    }

    let observer;
    function measureVisibility() {
      const bounds = screen.getBoundingClientRect();
      state.visible = bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 &&
        bounds.top < window.innerHeight && bounds.right > 0 && bounds.left < window.innerWidth;
      syncPlayback();
    }
    if ("IntersectionObserver" in window) {
      observer = new IntersectionObserver((entries) => {
        state.visible = entries.some((entry) => entry.isIntersecting && entry.intersectionRatio >= 0.1);
        syncPlayback();
      }, { threshold: [0, 0.1] });
      observer.observe(screen);
    } else {
      listen(window, "scroll", measureVisibility, { passive: true });
      listen(window, "resize", measureVisibility, { passive: true });
    }

    const controller = {
      select: (mode) => choose(mode, true), play, pause,
      destroy() {
        state.destroyed = true;
        state.generation += 1;
        observer?.disconnect();
        cleanup.forEach((remove) => remove());
        video.pause();
        video.removeAttribute("src");
        video.load();
        video.remove();
        fallback.remove();
        screen.dataset.videoReady = "false";
        controllers.delete(container);
      },
    };
    controllers.set(container, controller);
    const initial = container.dataset.demoInitial;
    choose(Object.hasOwn(modes, initial) ? initial : buttons[0]?.dataset.roundTab || "nav");
    if (!observer) measureVisibility();
    return controller;
  }

  function init(root = document) {
    const targets = Array.from(root.querySelectorAll("[data-round-demo]"));
    if (root.matches?.("[data-round-demo]")) targets.unshift(root);
    targets.forEach(create);
  }

  function apply(container, action, ...args) {
    const target = element(container);
    if (!target) return false;
    const controller = controllers.get(target) || create(target);
    return controller ? controller[action](...args) : false;
  }

  window.RoundScreenDemos = Object.freeze({
    init, modes,
    select: (container, mode) => apply(container, "select", mode),
    play: (container) => apply(container, "play"),
    pause: (container) => apply(container, "pause"),
    destroy: (container) => {
      const target = element(container);
      controllers.get(target)?.destroy();
    },
  });
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", () => init(), { once: true });
  else init();
})();
