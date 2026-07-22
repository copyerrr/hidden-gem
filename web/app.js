const sortSelect = document.getElementById("sort");
const statusEl = document.getElementById("status");
const resultsEl = document.getElementById("results");
const writeBtn = document.getElementById("writeBtn");
const tabs = document.querySelectorAll(".tab");
const panels = {
  ai: document.getElementById("panel-ai"),
  domestic: document.getElementById("panel-domestic"),
  foreign: document.getElementById("panel-foreign"),
};

const DEFAULT_YM = "201201";
const DEFAULT_LIMIT = "30";

/** @type {Array<object>} */
let currentGems = [];

function gemKey(gem) {
  return `${gem.resNm}|${gem.sido || ""}`;
}

function showStatus(message, type = "info") {
  statusEl.hidden = false;
  statusEl.className = `status ${type}`;
  statusEl.textContent = message;
}

function hideStatus() {
  statusEl.hidden = true;
}

function formatNum(n) {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + "M";
  if (n >= 1_000) return (n / 1_000).toFixed(1) + "K";
  return String(Math.round(n));
}

function sortGems(gems, sortKey) {
  const sorted = [...gems];
  switch (sortKey) {
    case "foreign":
      sorted.sort((a, b) => b.foreignVisitors - a.foreignVisitors);
      break;
    case "domestic":
      sorted.sort((a, b) => b.domesticVisitors - a.domesticVisitors);
      break;
    default:
      sorted.sort((a, b) => b.gemScore - a.gemScore);
  }
  return sorted;
}

function thumbHtml(gem) {
  const letter = gem.resNm ? gem.resNm.charAt(0) : "?";
  if (gem.thumbnail) {
    return `<img src="${escapeHtml(gem.thumbnail)}" alt="" loading="lazy" onerror="this.remove();this.parentElement.querySelector('.thumb-fallback')?.removeAttribute('hidden')" /><span class="thumb-letter thumb-fallback" hidden>${escapeHtml(letter)}</span>`;
  }
  return `<span class="thumb-letter">${escapeHtml(letter)}</span>`;
}

function renderGems(gems) {
  if (!gems || gems.length === 0) {
    resultsEl.innerHTML = "";
    const li = document.createElement("li");
    li.className = "empty-state";
    li.textContent = "추천 게시글이 없습니다.";
    resultsEl.appendChild(li);
    return;
  }

  const sorted = sortGems(gems, sortSelect.value);

  resultsEl.innerHTML = sorted
    .map((gem) => {
      const location = [gem.sido, gem.gungu].filter(Boolean).join(" ");
      const key = gemKey(gem);
      return `
        <li class="post-item" data-key="${escapeHtml(key)}">
          <div class="post-thumb" aria-hidden="true">
            ${thumbHtml(gem)}
          </div>
          <div class="post-body">
            <h2 class="post-title">${escapeHtml(gem.resNm)}</h2>
            <p class="post-meta">${escapeHtml(location)} · 외국인 ${formatNum(gem.foreignVisitors)} · 내국인 ${formatNum(gem.domesticVisitors)}</p>
            <span class="post-badge">히든젬 +${gem.gemScore}</span>
          </div>
        </li>`;
    })
    .join("");
}

function applyThumbnails(thumbnails) {
  for (const gem of currentGems) {
    const url = thumbnails[gemKey(gem)];
    if (url) gem.thumbnail = url;
  }
  for (const li of resultsEl.querySelectorAll(".post-item")) {
    const key = li.dataset.key;
    const url = thumbnails[key];
    if (!url) continue;
    const thumb = li.querySelector(".post-thumb");
    if (!thumb || thumb.querySelector("img")) continue;
    const letter = key.charAt(0);
    thumb.innerHTML = `<img src="${escapeHtml(url)}" alt="" loading="lazy" onerror="this.remove();this.parentElement.querySelector('.thumb-fallback')?.removeAttribute('hidden')" /><span class="thumb-letter thumb-fallback" hidden>${escapeHtml(letter)}</span>`;
  }
}

async function loadThumbnails(gems) {
  if (!gems.length) return;
  const body = gems.map((g) => `${g.resNm}\t${g.sido || ""}`).join("\n");
  try {
    const res = await fetch("/api/thumbnails", {
      method: "POST",
      headers: { "Content-Type": "text/plain; charset=utf-8" },
      body,
    });
    const data = await res.json();
    if (!res.ok) return;
    applyThumbnails(data.thumbnails || {});
  } catch {
    /* 사진 없이 목록만 표시 */
  }
}

async function loadHiddenGems() {
  showStatus("AI 추천 목록을 불러오는 중…", "info");
  resultsEl.innerHTML = "";

  const params = new URLSearchParams({ ym: DEFAULT_YM, limit: DEFAULT_LIMIT });

  try {
    const res = await fetch(`/api/hidden-gems?${params}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "조회 실패");

    currentGems = data.gems || [];
    hideStatus();
    renderGems(currentGems);
    loadThumbnails(currentGems);
  } catch (e) {
    showStatus(e.message || "오류가 발생했습니다.", "error");
  }
}

function escapeHtml(s) {
  return String(s)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function switchTab(tabId) {
  tabs.forEach((tab) => {
    const isActive = tab.dataset.tab === tabId;
    tab.classList.toggle("active", isActive);
    tab.setAttribute("aria-selected", String(isActive));
  });
  Object.entries(panels).forEach(([id, panel]) => {
    const isActive = id === tabId;
    panel.classList.toggle("active", isActive);
    panel.hidden = !isActive;
  });
}

tabs.forEach((tab) => {
  tab.addEventListener("click", () => switchTab(tab.dataset.tab));
});

sortSelect.addEventListener("change", () => {
  if (currentGems.length) renderGems(currentGems);
});

writeBtn.addEventListener("click", () => {
  alert("글쓰기 기능은 준비 중입니다.");
});

loadHiddenGems();
