(() => {
  "use strict";
  const params = new URLSearchParams(location.search);
  if (["demo", "deviceState", "api"].some(key => params.has(key))) {
    location.replace(new URL(`/moto-gps/ride.html${location.search}${location.hash}`, location.origin));
    return;
  }
  // 滚动显现动效：AOS，尊重系统减弱动态设置
  if (window.AOS) {
    AOS.init({
      duration: 700,
      easing: "ease-out-cubic",
      offset: 90,
      once: true,
      disable: () => window.matchMedia("(prefers-reduced-motion: reduce)").matches
    });
  }
  // GitHub Star 数：官方 API 直取，失败则只显示 Star 字样
  const starCount = document.querySelector("[data-star-count]");
  if (starCount) {
    fetch("https://api.github.com/repos/mx3353672833-debug/moto-gps-waveshare", {headers: {Accept: "application/vnd.github+json"}})
      .then(response => response.ok ? response.json() : Promise.reject(response.status))
      .then(repo => {
        const count = Number(repo.stargazers_count);
        if (!Number.isFinite(count)) return;
        starCount.textContent = count >= 1000 ? `${(count / 1000).toFixed(1)}k` : String(count);
        starCount.hidden = false;
      })
      .catch(() => {});
  }
  const input = document.querySelector("#faq-search");
  const entries = [...document.querySelectorAll(".faq-list details")];
  const count = document.querySelector("#faq-count");
  const empty = document.querySelector(".faq-empty");
  if (input && count && empty) {
    document.querySelector(".faq-tools").hidden = false;
    const originals = entries.map(entry => entry.textContent.toLocaleLowerCase());
    input.addEventListener("input", () => {
      const words = input.value.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
      let found = 0;
      entries.forEach((entry, i) => {
        const match = words.every(word => originals[i].includes(word));
        entry.hidden = !match;
        entry.open = match && words.length > 0;
        if (match) found++;
      });
      count.textContent = `${found} 个问题`;
      empty.hidden = found > 0;
    });
    count.textContent = `${entries.length} 个问题`;
  }
  const form = document.querySelector("#contact-form");
  const status = document.querySelector("#contact-status");
  if (!form || !status) return;
  const submit = form.querySelector('button[type="submit"]');
  form.addEventListener("submit", async event => {
    event.preventDefault();
    if (submit.disabled || !form.reportValidity()) return;
    const data = new FormData(form);
    submit.disabled = true;
    submit.textContent = "正在发送…";
    status.textContent = "";
    status.dataset.error = "false";
    try {
      const response = await fetch("/api/contact", {
        method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify(Object.fromEntries(["name", "email", "question", "website"].map(key => [key, String(data.get(key) || "").trim()]))),
        credentials: "same-origin"
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok || result.ok !== true) {
        throw new Error(result.message || (response.status === 429 ? "留言发送得有些频繁，请稍后再试。" : "暂时没有发送成功，请稍后再试。"));
      }
      status.textContent = "留言已发送，谢谢。留下邮箱的话，我会通过邮件回复你。";
      form.reset();
    } catch (error) {
      status.dataset.error = "true";
      status.textContent = error instanceof TypeError ? "连接中断，暂时无法确认发送结果。请稍后再查看或联系 malerxv@gmail.com。" : error.message;
    } finally {
      submit.disabled = false;
      submit.textContent = "发送留言 ↗";
    }
  });
})();
