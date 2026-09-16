(() => {
  "use strict";
  // Preserve previously shared links to the browser navigation/debug surface.
  const params = new URLSearchParams(location.search);
  if (["demo", "deviceState", "api"].some(key => params.has(key))) {
    location.replace(new URL(`ride.html${location.search}${location.hash}`, location.href));
    return;
  }
  const input = document.querySelector("#faq-search");
  const entries = [...document.querySelectorAll(".faq-list details")];
  const count = document.querySelector("#faq-count");
  const empty = document.querySelector(".faq-empty");
  if (!input || !count || !empty) return;
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
})();
