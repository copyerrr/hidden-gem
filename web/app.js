const sortSelect = document.getElementById("sort");
const sidoSelect = document.getElementById("sidoSelect");
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
const writeCancelBtn = document.getElementById("writeCancelBtn");
const writeImage = document.getElementById("writeImage");
const writeImagePreview = document.getElementById("writeImagePreview");
const writeTitle = document.getElementById("writeTitle");
const writeSubmit = document.getElementById("writeSubmit");
const editPostBtn = document.getElementById("editPostBtn");
const deletePostBtn = document.getElementById("deletePostBtn");
const detailDialog = document.getElementById("detailDialog");
const detailBody = document.getElementById("detailBody");
const detailClose = document.getElementById("detailClose");
const recommendBtn = document.getElementById("recommendBtn");
const replyList = document.getElementById("replyList");
const replyForm = document.getElementById("replyForm");
const replyInput = document.getElementById("replyInput");
const placeDialog = document.getElementById("placeDialog");
const placeBody = document.getElementById("placeBody");
const placeClose = document.getElementById("placeClose");

const authDialog = document.getElementById("authDialog");
const authForm = document.getElementById("authForm");
const authError = document.getElementById("authError");
const authLabel = document.getElementById("authLabel");
const authOpenBtn = document.getElementById("authOpenBtn");
const logoutBtn = document.getElementById("logoutBtn");
const authCancelBtn = document.getElementById("authCancelBtn");
const authTitle = document.getElementById("authTitle");
const authSubmit = document.getElementById("authSubmit");
const authNickWrap = document.getElementById("authNickWrap");
const authMemberId = document.getElementById("authMemberId");
const authPassword = document.getElementById("authPassword");
const authNickname = document.getElementById("authNickname");

const DEFAULT_YM = "201201";
const DEFAULT_LIMIT = "30";
const AUTH_STORAGE_KEY = "hiddengem_user";

/** @type {{ memberId: string, nickname: string } | null} */
let currentUser = null;
/** @type {"login"|"register"} */
let authMode = "login";

/** @type {Array<object>} */
let currentGems = [];
/** @type {Array<object>} */
let boardPosts = [];
/** @type {object|null} */
let currentDetail = null;
let activeTab = "ai";
/** @type {Map<string, object>} */
const placeDetailCache = new Map();
/** @type {Map<string, Promise<object>>} */
const placeDetailInflight = new Map();
const PLACE_PREFETCH_CONCURRENCY = 2;
/** @type {{ sido: string[] } | null} */
let regionData = null;
/** @type {string|null} data URL for pending write image */
let pendingImageDataUrl = null;
/** @type {number|null} 수정 중인 게시글 id */
let editingPostId = null;
/** 수정 시 기존 사진 URL (새 사진 없으면 유지) */
let existingImageUrl = "";
/** 수정 시 사진 제거 여부 */
let removeExistingImage = false;

function gemKey(gem) {
  return `${gem.resNm}|${gem.sido || ""}`;
}

function currentMemberId() {
  return currentUser?.memberId || "";
}

function loadStoredUser() {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (parsed && parsed.memberId) {
      return { memberId: String(parsed.memberId), nickname: String(parsed.nickname || parsed.memberId) };
    }
  } catch {
    /* ignore */
  }
  return null;
}

function saveUser(user) {
  currentUser = user;
  if (user) {
    localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(user));
  } else {
    localStorage.removeItem(AUTH_STORAGE_KEY);
  }
  renderAuthBar();
}

function renderAuthBar() {
  if (currentUser) {
    authLabel.textContent = `${currentUser.nickname || currentUser.memberId}님`;
    authOpenBtn.hidden = true;
    logoutBtn.hidden = false;
  } else {
    authLabel.textContent = "로그인이 필요합니다";
    authOpenBtn.hidden = false;
    logoutBtn.hidden = true;
  }
}

function requireLogin(message = "로그인이 필요합니다.") {
  if (currentUser?.memberId) return true;
  alert(message);
  openAuthDialog("login");
  return false;
}

function setAuthMode(mode) {
  authMode = mode === "register" ? "register" : "login";
  authTitle.textContent = authMode === "register" ? "회원가입" : "로그인";
  authSubmit.textContent = authMode === "register" ? "가입하기" : "로그인";
  // 닉네임은 회원가입에만 표시 (CSS display가 hidden을 덮지 않도록)
  if (authMode === "register") {
    authNickWrap.removeAttribute("hidden");
  } else {
    authNickWrap.setAttribute("hidden", "");
    authNickname.value = "";
  }
  authPassword.autocomplete = authMode === "register" ? "new-password" : "current-password";
  authError.hidden = true;
  document.querySelectorAll(".auth-mode").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.mode === authMode);
  });
}

function openAuthDialog(mode = "login") {
  setAuthMode(mode);
  authForm.reset();
  authError.hidden = true;
  authDialog.showModal();
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

function formatDist(m) {
  if (m == null || Number.isNaN(Number(m))) return "";
  const n = Number(m);
  if (n >= 1000) return (n / 1000).toFixed(1) + "km";
  return Math.round(n) + "m";
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
        <li class="post-item gem-item" data-key="${escapeHtml(key)}" role="button" tabindex="0">
          <div class="post-thumb" aria-hidden="true">
            ${thumbHtml(gem)}
          </div>
          <div class="post-body">
            <h2 class="post-title">${escapeHtml(gem.resNm)}</h2>
            <p class="post-meta">${escapeHtml(location)} · 외국인 ${formatNum(gem.foreignVisitors)} · 내국인 ${formatNum(gem.domesticVisitors)}</p>
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

async function fetchPlaceDetail(gem) {
  const key = gemKey(gem);
  if (placeDetailCache.has(key)) {
    return placeDetailCache.get(key);
  }
  if (placeDetailInflight.has(key)) {
    return placeDetailInflight.get(key);
  }
  const request = (async () => {
    const params = new URLSearchParams({
      resNm: gem.resNm || "",
      sido: gem.sido || "",
      gungu: gem.gungu || "",
    });
    const res = await fetch(`/api/place-detail?${params}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "상세 조회 실패");
    placeDetailCache.set(key, data);
    return data;
  })().finally(() => {
    placeDetailInflight.delete(key);
  });
  placeDetailInflight.set(key, request);
  return request;
}

async function prefetchPlaceDetails(gems) {
  if (!gems.length) return;
  const queue = gems.filter((g) => !placeDetailCache.has(gemKey(g)));
  if (!queue.length) return;

  const worker = async () => {
    while (queue.length) {
      const gem = queue.shift();
      if (!gem) break;
      try {
        await fetchPlaceDetail(gem);
      } catch {
        /* 개별 실패는 클릭 시 재시도 */
      }
    }
  };

  const n = Math.min(PLACE_PREFETCH_CONCURRENCY, queue.length);
  await Promise.all(Array.from({ length: n }, () => worker()));
}

async function loadHiddenGems() {
  showStatus(statusEl, "AI 추천 목록을 불러오는 중…", "info");
  resultsEl.innerHTML = "";
  placeDetailCache.clear();
  placeDetailInflight.clear();

  const params = new URLSearchParams({ ym: DEFAULT_YM, limit: DEFAULT_LIMIT });
  const sido = sidoSelect?.value || "";
  if (sido) params.set("sido", sido);

  try {
    const res = await fetch(`/api/hidden-gems?${params}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "조회 실패");

    currentGems = data.gems || [];
    hideStatus(statusEl);
    renderGems(currentGems);
    if (!currentGems.length) {
      resultsEl.innerHTML = "";
      const li = document.createElement("li");
      li.className = "empty-state";
      li.textContent = sido
        ? "이 지역에 해당하는 추천 장소가 없습니다."
        : "추천 장소가 없습니다.";
      resultsEl.appendChild(li);
      return;
    }
    await loadThumbnails(currentGems);
    prefetchPlaceDetails(currentGems);
  } catch (e) {
    showStatus(statusEl, e.message || "오류가 발생했습니다.", "error");
  }
}

async function loadRegions() {
  try {
    const res = await fetch(`/api/regions?ym=${encodeURIComponent(DEFAULT_YM)}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "지역 목록 실패");
    regionData = { sido: data.sido || [] };
    sidoSelect.innerHTML = `<option value="">전국</option>`;
    for (const s of regionData.sido) {
      const opt = document.createElement("option");
      opt.value = s;
      opt.textContent = s;
      sidoSelect.appendChild(opt);
    }
  } catch {
    /* 지역 필터 없이도 전국 목록은 동작 */
  }
}

async function openPlaceDetail(gem) {
  placeDialog.showModal();
  const key = gemKey(gem);
  const cached = placeDetailCache.get(key);

  if (cached) {
    renderPlaceDetail(gem, cached);
    return;
  }

  placeBody.innerHTML = `<div class="place-loading">관광 정보를 불러오는 중…</div>`;

  try {
    const data = await fetchPlaceDetail(gem);
    if (gemKey(gem) === key) {
      renderPlaceDetail(gem, data);
    }
  } catch (e) {
    placeBody.innerHTML = `<div class="place-empty">${escapeHtml(e.message || "불러오지 못했습니다.")}</div>`;
  }
}

function nearbyCardsHtml(list) {
  if (!list || !list.length) {
    return `<p class="nearby-empty">주변에 표시할 장소가 없습니다.</p>`;
  }
  return `<div class="nearby-row">${list
    .map((p) => {
      const letter = (p.title || "?").charAt(0);
      const thumb = p.image
        ? `<img src="${escapeHtml(p.image)}" alt="" loading="lazy" onerror="this.remove()" />`
        : `<span>${escapeHtml(letter)}</span>`;
      return `
        <article class="nearby-card">
          <div class="nearby-thumb">${thumb}</div>
          <div class="nearby-meta">
            <h4 class="nearby-title">${escapeHtml(p.title || "")}</h4>
            <p class="nearby-dist">${escapeHtml(formatDist(p.dist))}${p.addr ? " · " + escapeHtml(p.addr) : ""}</p>
          </div>
        </article>`;
    })
    .join("")}</div>`;
}

function renderPlaceDetail(gem, data) {
  const location = [gem.sido, gem.gungu].filter(Boolean).join(" ");
  const title = data.found ? data.title || gem.resNm : gem.resNm;
  const letter = (title || "?").charAt(0);
  const heroImg = data.image || gem.thumbnail;

  if (!data.found) {
    placeBody.innerHTML = `
      <div class="place-hero"><div class="place-hero-fallback">${escapeHtml(letter)}</div></div>
      <div class="place-content">
        <div>
          <p class="place-kicker">${escapeHtml(location || "위치 미상")}</p>
          <h2 class="place-title">${escapeHtml(gem.resNm)}</h2>
          <div class="place-stats">
            <span class="place-chip">외국인 ${formatNum(gem.foreignVisitors)}</span>
            <span class="place-chip">내국인 ${formatNum(gem.domesticVisitors)}</span>
          </div>
        </div>
        <p class="nearby-empty">${escapeHtml(data.message || "상세 정보를 찾지 못했습니다.")}</p>
      </div>`;
    return;
  }

  const infoList = (data.info || [])
    .map(
      (row) => `
      <li class="place-info-item">
        <span class="place-info-label">${escapeHtml(row.label)}</span>
        <span class="place-info-value">${escapeHtml(row.value)}</span>
      </li>`
    )
    .join("");

  const overview = data.overview || "";
  const showMore = overview.length > 220;
  const telRaw = String(data.tel || "").split(/[,\n]/)[0].trim();

  placeBody.innerHTML = `
    <div class="place-hero">
      ${
        heroImg
          ? `<img src="${escapeHtml(heroImg)}" alt="" data-fallback="${escapeHtml(letter)}" onerror="this.outerHTML='<div class=place-hero-fallback>'+this.dataset.fallback+'</div>'" />`
          : `<div class="place-hero-fallback">${escapeHtml(letter)}</div>`
      }
    </div>
    <div class="place-content">
      <header>
        <p class="place-kicker">${escapeHtml(data.addr || location || "")}</p>
        <h2 class="place-title">${escapeHtml(title)}</h2>
        <div class="place-stats">
          <span class="place-chip">외국인 ${formatNum(gem.foreignVisitors)}</span>
          <span class="place-chip">내국인 ${formatNum(gem.domesticVisitors)}</span>
        </div>
      </header>

      <section>
        <h3 class="place-section-title">소개</h3>
        ${
          overview
            ? `<p class="place-overview" id="placeOverview">${escapeHtml(overview)}</p>
               ${showMore ? `<button type="button" class="place-more" id="placeMoreBtn">더 보기</button>` : ""}`
            : `<p class="nearby-empty">소개글이 없습니다.</p>`
        }
      </section>

      <section>
        <h3 class="place-section-title">이용 정보</h3>
        ${
          infoList
            ? `<ul class="place-info-list">${infoList}</ul>`
            : `<p class="nearby-empty">이용 정보가 없습니다.</p>`
        }
        ${
          data.homepage || data.tel
            ? `<div class="place-links" style="margin-top:0.7rem">
                ${data.homepage ? `<a class="place-link" href="${escapeHtml(data.homepage)}" target="_blank" rel="noopener">홈페이지</a>` : ""}
                ${data.tel ? `<a class="place-link" href="tel:${escapeHtml(telRaw)}">전화</a>` : ""}
              </div>`
            : ""
        }
      </section>

      <section>
        <h3 class="place-section-title">근처 맛집</h3>
        ${nearbyCardsHtml(data.restaurants)}
      </section>

      <section>
        <h3 class="place-section-title">근처에 가볼 곳</h3>
        ${nearbyCardsHtml(data.attractions)}
      </section>
    </div>`;

  const moreBtn = document.getElementById("placeMoreBtn");
  const overviewEl = document.getElementById("placeOverview");
  if (moreBtn && overviewEl) {
    moreBtn.addEventListener("click", () => {
      const open = overviewEl.classList.toggle("expanded");
      moreBtn.textContent = open ? "접기" : "더 보기";
    });
  }
}

function boardThumb(post) {
  const letter = (post.locationTitle || post.nickname || "?").charAt(0);
  if (post.imageUrl) {
    return `<img src="${escapeHtml(post.imageUrl)}" alt="" loading="lazy" onerror="this.remove();this.parentElement.querySelector('.thumb-fallback')?.removeAttribute('hidden')" /><span class="thumb-letter thumb-fallback" hidden>${escapeHtml(letter)}</span>`;
  }
  return `<span class="thumb-letter">${escapeHtml(letter)}</span>`;
}

function boardCategoryForTab(tabId) {
  return tabId === "foreign" ? "FOREIGN" : "DOMESTIC";
}

function renderBoardList(listEl, posts) {
  if (!posts.length) {
    listEl.innerHTML = `<li class="empty-state">아직 게시글이 없습니다. 글쓰기로 첫 글을 남겨 보세요.</li>`;
    return;
  }
  listEl.innerHTML = posts
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

  listEl.querySelectorAll(".board-item").forEach((li) => {
    li.addEventListener("click", () => openDetail(Number(li.dataset.postId)));
  });
}

async function loadBoardPosts(tabId = activeTab) {
  const category = boardCategoryForTab(tabId);
  const statusId = category === "FOREIGN" ? "boardStatusForeign" : "boardStatusDomestic";
  const listId = category === "FOREIGN" ? "boardListForeign" : "boardListDomestic";
  const statusElBoard = document.getElementById(statusId);
  const listEl = document.getElementById(listId);

  showStatus(statusElBoard, "게시글을 불러오는 중…", "info");
  const qs = new URLSearchParams({
    memberId: currentMemberId(),
    category,
  });
  try {
    const res = await fetch(`/api/posts?${qs}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "게시글 조회 실패");
    boardPosts = data.posts || [];
    hideStatus(statusElBoard);
    renderBoardList(listEl, boardPosts);
  } catch (e) {
    showStatus(statusElBoard, e.message || "오류", "error");
  }
}

async function openDetail(postId) {
  const qs = `?memberId=${encodeURIComponent(currentMemberId())}`;
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
  const img = p.imageUrl
    ? `<div class="detail-image"><img src="${escapeHtml(p.imageUrl)}" alt="" loading="lazy" /></div>`
    : "";
  detailBody.innerHTML = `
    ${img}
    <h2 class="detail-title">${escapeHtml(p.locationTitle || "장소 미정")}</h2>
    <p class="detail-meta">${escapeHtml(p.nickname || p.memberId)} · ${escapeHtml(p.regDate || "")}</p>
    <p class="detail-meta">${escapeHtml(p.address || "")}</p>
    <p class="detail-content">${escapeHtml(p.content || "")}</p>
    <p class="detail-meta">추천 ${p.recommendCount} · 댓글 ${(p.replies || []).length}</p>
  `;
  recommendBtn.textContent = p.recommended ? `추천 취소 (${p.recommendCount})` : `추천 (${p.recommendCount})`;
  recommendBtn.classList.toggle("on", !!p.recommended);

  const isOwner = !!(currentUser && p.memberId && currentUser.memberId === p.memberId);
  editPostBtn.hidden = !isOwner;
  deletePostBtn.hidden = !isOwner;

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
    loadBoardPosts(tabId);
  }
}

function resetWriteForm() {
  writeForm.reset();
  pendingImageDataUrl = null;
  editingPostId = null;
  existingImageUrl = "";
  removeExistingImage = false;
  writeImagePreview.hidden = true;
  writeImagePreview.innerHTML = "";
  writeError.hidden = true;
  if (writeTitle) writeTitle.textContent = "글쓰기";
  if (writeSubmit) writeSubmit.textContent = "등록";
}

function openWriteForEdit(post) {
  resetWriteForm();
  editingPostId = post.postId;
  existingImageUrl = post.imageUrl || "";
  if (writeTitle) writeTitle.textContent = "글 수정";
  if (writeSubmit) writeSubmit.textContent = "수정 저장";
  document.getElementById("writeCategory").value = post.category === "FOREIGN" ? "FOREIGN" : "DOMESTIC";
  document.getElementById("writeLocation").value = post.locationTitle || "";
  document.getElementById("writeAddress").value = post.address || "";
  document.getElementById("writeContent").value = post.content || "";
  if (existingImageUrl) {
    writeImagePreview.hidden = false;
    writeImagePreview.innerHTML = `
      <img src="${escapeHtml(existingImageUrl)}" alt="미리보기" />
      <button type="button" id="clearWriteImageBtn" class="btn-ghost" style="margin-top:0.4rem">사진 제거</button>`;
    document.getElementById("clearWriteImageBtn")?.addEventListener("click", () => {
      existingImageUrl = "";
      removeExistingImage = true;
      pendingImageDataUrl = null;
      writeImage.value = "";
      writeImagePreview.hidden = true;
      writeImagePreview.innerHTML = "";
    });
  }
  writeDialog.showModal();
}

/** 선택한 이미지를 JPEG data URL로 리사이즈 (업로드 용량 절약) */
function fileToCompressedDataUrl(file) {
  return new Promise((resolve, reject) => {
    if (!file || !file.type.startsWith("image/")) {
      reject(new Error("이미지 파일만 올릴 수 있습니다."));
      return;
    }
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("파일을 읽지 못했습니다."));
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => reject(new Error("이미지를 열지 못했습니다."));
      img.onload = () => {
        const maxSide = 960;
        let { width, height } = img;
        if (width > maxSide || height > maxSide) {
          const scale = maxSide / Math.max(width, height);
          width = Math.round(width * scale);
          height = Math.round(height * scale);
        }
        const canvas = document.createElement("canvas");
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext("2d");
        ctx.drawImage(img, 0, 0, width, height);
        resolve(canvas.toDataURL("image/jpeg", 0.72));
      };
      img.src = reader.result;
    };
    reader.readAsDataURL(file);
  });
}

async function uploadImageDataUrl(dataUrl) {
  const res = await fetch("/api/upload", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ imageBase64: dataUrl, contentType: "image/jpeg" }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || "사진 업로드 실패");
  return data.url;
}

tabs.forEach((tab) => {
  tab.addEventListener("click", () => switchTab(tab.dataset.tab));
});

sortSelect.addEventListener("change", () => {
  if (currentGems.length) renderGems(currentGems);
});

sidoSelect.addEventListener("change", () => {
  loadHiddenGems();
});

authOpenBtn.addEventListener("click", () => openAuthDialog("login"));
logoutBtn.addEventListener("click", () => {
  saveUser(null);
});
authCancelBtn.addEventListener("click", () => {
  authError.hidden = true;
  authDialog.close();
});
document.querySelectorAll(".auth-mode").forEach((btn) => {
  btn.addEventListener("click", () => setAuthMode(btn.dataset.mode));
});

authForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  authError.hidden = true;
  const memberId = authMemberId.value.trim();
  const password = authPassword.value;
  const nickname = authNickname.value.trim();
  if (!memberId || !password) {
    authError.hidden = false;
    authError.textContent = "아이디와 비밀번호를 입력하세요.";
    return;
  }
  try {
    const endpoint = authMode === "register" ? "/api/register" : "/api/login";
    const payload =
      authMode === "register"
        ? { memberId, password, nickname }
        : { memberId, password };
    const res = await fetch(endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "실패");
    saveUser({ memberId: data.memberId, nickname: data.nickname || data.memberId });
    authDialog.close();
  } catch (err) {
    authError.hidden = false;
    authError.textContent = err.message || "실패";
  }
});

writeBtn.addEventListener("click", () => {
  if (!requireLogin("글쓰기는 로그인 후 이용할 수 있습니다.")) return;
  resetWriteForm();
  const cat = document.getElementById("writeCategory");
  if (cat) {
    cat.value = activeTab === "foreign" ? "FOREIGN" : "DOMESTIC";
  }
  writeDialog.showModal();
});

writeCancelBtn.addEventListener("click", () => {
  resetWriteForm();
  writeDialog.close();
});

writeImage.addEventListener("change", async () => {
  writeError.hidden = true;
  pendingImageDataUrl = null;
  removeExistingImage = false;
  writeImagePreview.hidden = true;
  writeImagePreview.innerHTML = "";
  const file = writeImage.files?.[0];
  if (!file) return;
  try {
    pendingImageDataUrl = await fileToCompressedDataUrl(file);
    writeImagePreview.hidden = false;
    writeImagePreview.innerHTML = `<img src="${pendingImageDataUrl}" alt="미리보기" />`;
  } catch (err) {
    writeImage.value = "";
    writeError.hidden = false;
    writeError.textContent = err.message || "사진을 불러오지 못했습니다.";
  }
});

writeForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!requireLogin("글쓰기는 로그인 후 이용할 수 있습니다.")) return;
  writeError.hidden = true;
  const category = document.getElementById("writeCategory")?.value || "DOMESTIC";
  const content = document.getElementById("writeContent").value.trim();
  if (!content) {
    writeError.hidden = false;
    writeError.textContent = "내용을 입력하세요.";
    return;
  }

  const submitBtn = document.getElementById("writeSubmit");
  submitBtn.disabled = true;
  try {
    const payload = {
      memberId: currentMemberId(),
      locationTitle: document.getElementById("writeLocation").value.trim(),
      address: document.getElementById("writeAddress").value.trim(),
      content,
      category,
    };

    if (editingPostId) {
      if (pendingImageDataUrl) {
        payload.imageUrl = await uploadImageDataUrl(pendingImageDataUrl);
      } else if (removeExistingImage) {
        payload.imageUrl = "";
      }
      // imageUrl 미포함 = 기존 사진 유지
      const res = await fetch(`/api/posts/${editingPostId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "수정 실패");
      const savedId = editingPostId;
      writeDialog.close();
      resetWriteForm();
      detailDialog.close();
      const targetTab = category === "FOREIGN" ? "foreign" : "domestic";
      if (activeTab !== targetTab) switchTab(targetTab);
      else await loadBoardPosts(targetTab);
      await openDetail(savedId);
    } else {
      let imageUrl = "";
      if (pendingImageDataUrl) {
        imageUrl = await uploadImageDataUrl(pendingImageDataUrl);
      }
      payload.imageUrl = imageUrl;
      const res = await fetch("/api/posts", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "등록 실패");
      writeDialog.close();
      resetWriteForm();
      const targetTab = category === "FOREIGN" ? "foreign" : "domestic";
      if (activeTab !== targetTab) switchTab(targetTab);
      else await loadBoardPosts(targetTab);
      if (data.postId) openDetail(data.postId);
    }
  } catch (err) {
    writeError.hidden = false;
    writeError.textContent = err.message || "저장 실패";
  } finally {
    submitBtn.disabled = false;
  }
});

editPostBtn.addEventListener("click", () => {
  if (!currentDetail) return;
  if (!requireLogin("로그인이 필요합니다.")) return;
  if (currentUser.memberId !== currentDetail.memberId) {
    alert("본인 글만 수정할 수 있습니다.");
    return;
  }
  openWriteForEdit(currentDetail);
});

deletePostBtn.addEventListener("click", async () => {
  if (!currentDetail) return;
  if (!requireLogin("로그인이 필요합니다.")) return;
  if (currentUser.memberId !== currentDetail.memberId) {
    alert("본인 글만 삭제할 수 있습니다.");
    return;
  }
  if (!confirm("이 게시글을 삭제할까요?")) return;
  try {
    const res = await fetch(`/api/posts/${currentDetail.postId}`, {
      method: "DELETE",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ memberId: currentMemberId() }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "삭제 실패");
    detailDialog.close();
    currentDetail = null;
    if (activeTab === "domestic" || activeTab === "foreign") {
      await loadBoardPosts(activeTab);
    }
  } catch (err) {
    alert(err.message || "삭제 실패");
  }
});
detailClose.addEventListener("click", () => detailDialog.close());
placeClose.addEventListener("click", () => placeDialog.close());

resultsEl.addEventListener("click", (e) => {
  const li = e.target.closest(".gem-item");
  if (!li) return;
  const gem = currentGems.find((g) => gemKey(g) === li.dataset.key);
  if (gem) openPlaceDetail(gem);
});

resultsEl.addEventListener("keydown", (e) => {
  if (e.key !== "Enter" && e.key !== " ") return;
  const li = e.target.closest(".gem-item");
  if (!li) return;
  e.preventDefault();
  const gem = currentGems.find((g) => gemKey(g) === li.dataset.key);
  if (gem) openPlaceDetail(gem);
});

recommendBtn.addEventListener("click", async () => {
  if (!currentDetail) return;
  if (!requireLogin("추천은 로그인 후 이용할 수 있습니다.")) return;
  try {
    const res = await fetch(`/api/posts/${currentDetail.postId}/recommend`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ memberId: currentMemberId() }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "추천 실패");
    await openDetail(currentDetail.postId);
    if (activeTab === "domestic" || activeTab === "foreign") {
      loadBoardPosts(activeTab);
    }
  } catch (err) {
    alert(err.message || "추천 실패");
  }
});

replyForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!currentDetail) return;
  if (!requireLogin("댓글은 로그인 후 이용할 수 있습니다.")) return;
  const content = replyInput.value.trim();
  if (!content) return;
  try {
    const res = await fetch(`/api/posts/${currentDetail.postId}/replies`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ memberId: currentMemberId(), content }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "댓글 등록 실패");
    replyInput.value = "";
    await openDetail(currentDetail.postId);
    if (activeTab === "domestic" || activeTab === "foreign") {
      loadBoardPosts(activeTab);
    }
  } catch (err) {
    alert(err.message || "댓글 등록 실패");
  }
});

currentUser = loadStoredUser();
renderAuthBar();
loadRegions().then(() => loadHiddenGems());
