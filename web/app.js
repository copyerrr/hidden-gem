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

const writeDialog = document.getElementById("writeDialog");
const writeForm = document.getElementById("writeForm");
const writeError = document.getElementById("writeError");
const detailDialog = document.getElementById("detailDialog");
const detailBody = document.getElementById("detailBody");
const detailClose = document.getElementById("detailClose");
const recommendBtn = document.getElementById("recommendBtn");
const replyList = document.getElementById("replyList");
const replyForm = document.getElementById("replyForm");
const replyInput = document.getElementById("replyInput");

const DEFAULT_YM = "201201";
const DEFAULT_LIMIT = "30";
/** DB Member에 있는 기본 작성자 (로그인 없이 사용) */
const DEFAULT_MEMBER_ID = "minji_test";

/** @type {Array<object>} */
let currentGems = [];
/** @type {Array<object>} */
let boardPosts = [];
/** @type {object|null} */
let currentDetail = null;
let activeTab = "ai";

function gemKey(gem) {
  return `${gem.resNm}|${gem.sido || ""}`;
}

function showStatus(el, message, type = "info") {
  el.hidden = false;
  el.className = `status ${type}`;
  el.textContent = message;
}

function hideStatus(el) {
  el.hidden = true;
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
  showStatus(statusEl, "AI 추천 목록을 불러오는 중…", "info");
  resultsEl.innerHTML = "";

  const params = new URLSearchParams({ ym: DEFAULT_YM, limit: DEFAULT_LIMIT });

  try {
    const res = await fetch(`/api/hidden-gems?${params}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "조회 실패");

    currentGems = data.gems || [];
    hideStatus(statusEl);
    renderGems(currentGems);
    loadThumbnails(currentGems);
  } catch (e) {
    showStatus(statusEl, e.message || "오류가 발생했습니다.", "error");
  }
}

function boardThumb(post) {
  const letter = (post.locationTitle || post.nickname || "?").charAt(0);
  if (post.imageUrl) {
    return `<img src="${escapeHtml(post.imageUrl)}" alt="" loading="lazy" onerror="this.remove()" /><span class="thumb-letter">${escapeHtml(letter)}</span>`;
  }
  return `<span class="thumb-letter">${escapeHtml(letter)}</span>`;
}

function renderBoardLists() {
  for (const id of ["boardListDomestic", "boardListForeign"]) {
    const el = document.getElementById(id);
    if (!boardPosts.length) {
      el.innerHTML = `<li class="empty-state">아직 게시글이 없습니다. 글쓰기로 첫 글을 남겨 보세요.</li>`;
      continue;
    }
    el.innerHTML = boardPosts
      .map((p) => {
        const title = p.locationTitle || "장소 미정";
        const preview = (p.content || "").replace(/\s+/g, " ").slice(0, 80);
        return `
          <li class="post-item board-item" data-post-id="${p.postId}">
            <div class="post-thumb" aria-hidden="true">${boardThumb(p)}</div>
            <div class="post-body">
              <h2 class="post-title">${escapeHtml(title)}</h2>
              <p class="post-meta">${escapeHtml(p.nickname || p.memberId)} · 추천 ${p.recommendCount} · 댓글 ${p.replyCount}</p>
              <p class="post-meta">${escapeHtml(preview)}</p>
            </div>
          </li>`;
      })
      .join("");
  }

  document.querySelectorAll(".board-item").forEach((li) => {
    li.addEventListener("click", () => openDetail(Number(li.dataset.postId)));
  });
}

async function loadBoardPosts() {
  const statusIds = ["boardStatusDomestic", "boardStatusForeign"];
  for (const id of statusIds) {
    showStatus(document.getElementById(id), "게시글을 불러오는 중…", "info");
  }
  const qs = `?memberId=${encodeURIComponent(DEFAULT_MEMBER_ID)}`;
  try {
    const res = await fetch(`/api/posts${qs}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "게시글 조회 실패");
    boardPosts = data.posts || [];
    for (const id of statusIds) hideStatus(document.getElementById(id));
    renderBoardLists();
  } catch (e) {
    for (const id of statusIds) {
      showStatus(document.getElementById(id), e.message || "오류", "error");
    }
  }
}

async function openDetail(postId) {
  const qs = `?memberId=${encodeURIComponent(DEFAULT_MEMBER_ID)}`;
  try {
    const res = await fetch(`/api/posts/${postId}${qs}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "조회 실패");
    currentDetail = data;
    renderDetail();
    detailDialog.showModal();
  } catch (e) {
    alert(e.message || "게시글을 열 수 없습니다.");
  }
}

function renderDetail() {
  const p = currentDetail;
  if (!p) return;
  detailBody.innerHTML = `
    <h2 class="detail-title">${escapeHtml(p.locationTitle || "장소 미정")}</h2>
    <p class="detail-meta">${escapeHtml(p.nickname || p.memberId)} · ${escapeHtml(p.regDate || "")}</p>
    <p class="detail-meta">${escapeHtml(p.address || "")}</p>
    <p class="detail-content">${escapeHtml(p.content || "")}</p>
    <p class="detail-meta">추천 ${p.recommendCount} · 댓글 ${(p.replies || []).length}</p>
  `;
  recommendBtn.textContent = p.recommended ? `추천 취소 (${p.recommendCount})` : `추천 (${p.recommendCount})`;
  recommendBtn.classList.toggle("on", !!p.recommended);

  const replies = p.replies || [];
  replyList.innerHTML = replies.length
    ? replies
        .map(
          (r) => `
      <li class="reply-item">
        <strong>${escapeHtml(r.nickname || r.memberId)}</strong>
        ${escapeHtml(r.content || "")}
      </li>`
        )
        .join("")
    : `<li class="reply-item">아직 댓글이 없습니다.</li>`;
}

function escapeHtml(s) {
  return String(s)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function switchTab(tabId) {
  activeTab = tabId;
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
  if (tabId === "domestic" || tabId === "foreign") {
    loadBoardPosts();
  }
}

tabs.forEach((tab) => {
  tab.addEventListener("click", () => switchTab(tab.dataset.tab));
});

sortSelect.addEventListener("change", () => {
  if (currentGems.length) renderGems(currentGems);
});

writeBtn.addEventListener("click", () => {
  writeError.hidden = true;
  writeDialog.showModal();
});

writeForm.addEventListener("submit", async (e) => {
  const submitter = e.submitter;
  if (submitter && submitter.value === "cancel") {
    writeError.hidden = true;
    return;
  }
  e.preventDefault();
  writeError.hidden = true;
  const payload = {
    memberId: DEFAULT_MEMBER_ID,
    locationTitle: document.getElementById("writeLocation").value.trim(),
    address: document.getElementById("writeAddress").value.trim(),
    content: document.getElementById("writeContent").value.trim(),
  };
  try {
    const res = await fetch("/api/posts", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "등록 실패");
    writeDialog.close();
    writeForm.reset();
    if (activeTab === "ai") switchTab("domestic");
    else await loadBoardPosts();
    if (data.postId) openDetail(data.postId);
  } catch (err) {
    writeError.hidden = false;
    writeError.textContent = err.message || "등록 실패";
  }
});

detailClose.addEventListener("click", () => detailDialog.close());

recommendBtn.addEventListener("click", async () => {
  if (!currentDetail) return;
  try {
    const res = await fetch(`/api/posts/${currentDetail.postId}/recommend`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ memberId: DEFAULT_MEMBER_ID }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "추천 실패");
    await openDetail(currentDetail.postId);
    loadBoardPosts();
  } catch (err) {
    alert(err.message || "추천 실패");
  }
});

replyForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!currentDetail) return;
  const content = replyInput.value.trim();
  if (!content) return;
  try {
    const res = await fetch(`/api/posts/${currentDetail.postId}/replies`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ memberId: DEFAULT_MEMBER_ID, content }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "댓글 등록 실패");
    replyInput.value = "";
    await openDetail(currentDetail.postId);
    loadBoardPosts();
  } catch (err) {
    alert(err.message || "댓글 등록 실패");
  }
});

loadHiddenGems();
