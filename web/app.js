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
  my: document.getElementById("panel-my"),
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
const LANG_STORAGE_KEY = "hiddengem_lang";
/** @type {'ko'|'en'} */
let uiLang = localStorage.getItem(LANG_STORAGE_KEY) === "en" ? "en" : "ko";
const UI_I18N = {
  ko: {
    tabAi: "AI 추천",
    tabDomestic: "내국인",
    tabForeign: "외국인",
    tabMy: "마이",
    needLogin: "로그인이 필요합니다",
    loginJoin: "로그인 / 가입",
    logout: "로그아웃",
    write: "글쓰기",
    region: "지역",
    nationwide: "전국",
    sort: "정렬",
    sortGem: "추천순",
    sortForeign: "외국인 많은 순",
    sortDomestic: "내국인 많은 순",
    myPosts: "내가 쓴 글",
    myLiked: "추천한 글",
    translating: "번역 중…",
    foreignVisitors: "외국인",
    domesticVisitors: "내국인",
  },
  en: {
    tabAi: "AI Picks",
    tabDomestic: "Locals",
    tabForeign: "Visitors",
    tabMy: "My",
    needLogin: "Sign in required",
    loginJoin: "Sign in / Join",
    logout: "Log out",
    write: "Write",
    region: "Region",
    nationwide: "All",
    sort: "Sort",
    sortGem: "Recommended",
    sortForeign: "Most foreign visitors",
    sortDomestic: "Most local visitors",
    myPosts: "My posts",
    myLiked: "Liked",
    translating: "Translating…",
    foreignVisitors: "Foreign",
    domesticVisitors: "Local",
  },
};

function t(key) {
  return (UI_I18N[uiLang] && UI_I18N[uiLang][key]) || UI_I18N.ko[key] || key;
}

function syncLangButtons() {
  document.querySelectorAll(".lang-btn").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.lang === uiLang);
  });
}

function applyChromeI18n() {
  document.querySelectorAll(".tab").forEach((tab) => {
    const id = tab.dataset.tab;
    if (id === "ai") tab.textContent = t("tabAi");
    else if (id === "domestic") tab.textContent = t("tabDomestic");
    else if (id === "foreign") tab.textContent = t("tabForeign");
    else if (id === "my") tab.textContent = t("tabMy");
  });
  if (!currentUser) authLabel.textContent = t("needLogin");
  authOpenBtn.textContent = t("loginJoin");
  logoutBtn.textContent = t("logout");
  writeBtn.title = t("write");
  writeBtn.textContent = t("write");
  const regionLabel = document.querySelector('label[for="sidoSelect"]');
  const sortLabel = document.querySelector('label[for="sort"]');
  if (regionLabel) regionLabel.textContent = t("region");
  if (sortLabel) sortLabel.textContent = t("sort");
  const sort = document.getElementById("sort");
  if (sort) {
    const opts = sort.options;
    if (opts[0]) opts[0].textContent = t("sortGem");
    if (opts[1]) opts[1].textContent = t("sortForeign");
    if (opts[2]) opts[2].textContent = t("sortDomestic");
  }
  const firstSido = sidoSelect?.options?.[0];
  if (firstSido && !firstSido.value) firstSido.textContent = t("nationwide");
  document.querySelectorAll(".my-subtab").forEach((btn) => {
    if (btn.dataset.myView === "posts") btn.textContent = t("myPosts");
    if (btn.dataset.myView === "liked") btn.textContent = t("myLiked");
  });
}

async function translateBatch(texts, targetLang = "EN") {
  const list = (texts || []).map((x) => (x == null ? "" : String(x)));
  if (!list.length || list.every((x) => !x.trim())) {
    return list.slice();
  }
  const res = await fetch("/api/translate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ texts: list, targetLang }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || "Translation failed");
  return data.translations || list;
}

function displayGemName(gem) {
  return uiLang === "en" && gem.resNmEn ? gem.resNmEn : gem.resNm;
}

function displayGemLocation(gem) {
  const raw = [gem.sido, gem.gungu].filter(Boolean).join(" ");
  return uiLang === "en" && gem.locationEn ? gem.locationEn : raw;
}

async function translateCurrentGems() {
  if (uiLang !== "en" || !currentGems.length) return;
  const need = currentGems.filter((g) => !g.resNmEn);
  if (!need.length) return;
  const texts = [];
  for (const g of need) {
    texts.push(g.resNm || "");
    texts.push([g.sido, g.gungu].filter(Boolean).join(" "));
  }
  const tr = await translateBatch(texts, "EN");
  for (let i = 0; i < need.length; i++) {
    need[i].resNmEn = tr[i * 2] || need[i].resNm;
    need[i].locationEn = tr[i * 2 + 1] || [need[i].sido, need[i].gungu].filter(Boolean).join(" ");
  }
}

async function translatePosts(posts) {
  if (uiLang !== "en" || !posts?.length) return;
  const need = posts.filter((p) => !p._en);
  if (!need.length) return;
  const texts = [];
  for (const p of need) {
    texts.push(p.content || "");
    texts.push(p.locationTitle || "");
    texts.push(p.address || "");
  }
  const tr = await translateBatch(texts, "EN");
  for (let i = 0; i < need.length; i++) {
    need[i]._en = {
      content: tr[i * 3] || need[i].content,
      locationTitle: tr[i * 3 + 1] || need[i].locationTitle,
      address: tr[i * 3 + 2] || need[i].address,
    };
  }
}

function postField(p, field) {
  if (uiLang === "en" && p._en && p._en[field] != null) return p._en[field];
  return p[field];
}

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
  if (activeTab === "my") {
    loadMyPage();
  } else if (activeTab === "domestic" || activeTab === "foreign") {
    loadBoardPosts(activeTab);
  }
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
  renderMyHeader();
}

function renderMyHeader() {
  const avatar = document.getElementById("myAvatar");
  const nameEl = document.getElementById("myName");
  const subEl = document.getElementById("mySub");
  if (!avatar || !nameEl || !subEl) return;
  if (currentUser) {
    const nick = currentUser.nickname || currentUser.memberId;
    avatar.textContent = String(nick).charAt(0);
    nameEl.textContent = nick;
    subEl.textContent = `@${currentUser.memberId}`;
  } else {
    avatar.textContent = "?";
    nameEl.textContent = "게스트";
    subEl.textContent = "로그인하면 내 글·추천 목록을 볼 수 있어요";
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
      const location = displayGemLocation(gem);
      const name = displayGemName(gem);
      const key = gemKey(gem);
      return `
        <li class="post-item gem-item" data-key="${escapeHtml(key)}" role="button" tabindex="0">
          <div class="post-thumb" aria-hidden="true">
            ${thumbHtml(gem)}
          </div>
          <div class="post-body">
            <h2 class="post-title">${escapeHtml(name)}</h2>
            <p class="post-meta">${escapeHtml(location)} · ${t("foreignVisitors")} ${formatNum(gem.foreignVisitors)} · ${t("domesticVisitors")} ${formatNum(gem.domesticVisitors)}</p>
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
  renderGems(currentGems);
}

async function loadThumbnails(gems) {
  if (!gems.length) return { thumbnails: {}, apiLimited: false };
  const body = gems.map((g) => `${g.resNm}\t${g.sido || ""}`).join("\n");
  try {
    const res = await fetch("/api/thumbnails", {
      method: "POST",
      headers: { "Content-Type": "text/plain; charset=utf-8" },
      body,
    });
    const data = await res.json();
    if (!res.ok) return { thumbnails: {}, apiLimited: !!data.apiLimited };
    const thumbnails = data.thumbnails || {};
    for (const gem of gems) {
      const url = thumbnails[gemKey(gem)];
      if (url) gem.thumbnail = url;
    }
    return { thumbnails, apiLimited: !!data.apiLimited };
  } catch {
    return { thumbnails: {}, apiLimited: false };
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
  showStatus(statusEl, uiLang === "en" ? "Loading AI picks…" : "AI 추천 목록을 불러오는 중…", "info");
  resultsEl.innerHTML = "";
  placeDetailCache.clear();
  placeDetailInflight.clear();

  // 사진·상세 없는 곳을 걸러내므로 후보를 넉넉히 가져온 뒤 통과분만 표시
  const params = new URLSearchParams({ ym: DEFAULT_YM, limit: "100" });
  const sido = sidoSelect?.value || "";
  if (sido) params.set("sido", sido);

  try {
    const res = await fetch(`/api/hidden-gems?${params}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "조회 실패");

    let gems = data.gems || [];
    if (!gems.length) {
      currentGems = [];
      hideStatus(statusEl);
      resultsEl.innerHTML = "";
      const li = document.createElement("li");
      li.className = "empty-state";
      li.textContent = sido
        ? uiLang === "en"
          ? "No recommendations for this region."
          : "이 지역에 해당하는 추천 장소가 없습니다."
        : uiLang === "en"
          ? "No recommendations."
          : "추천 장소가 없습니다.";
      resultsEl.appendChild(li);
      return;
    }

    showStatus(
      statusEl,
      uiLang === "en" ? "Loading AI picks" : "AI 계산중",
      "info"
    );
    const thumbMeta = await loadThumbnails(gems);
    await prefetchPlaceDetails(gems);

    const showLimit = Number(DEFAULT_LIMIT) || 30;
    const enriched = gems.filter((g) => {
      if (!g.thumbnail) return false;
      const detail = placeDetailCache.get(gemKey(g));
      return !!(detail && detail.found === true);
    });

    let apiLimited =
      !!(thumbMeta && thumbMeta.apiLimited) ||
      [...placeDetailCache.values()].some((d) => d && d.apiLimited);

    if (enriched.length) {
      gems = enriched.length > showLimit ? enriched.slice(0, showLimit) : enriched;
    } else if (apiLimited || gems.length) {
      // 한도 초과·API 실패 시 빈 화면 대신 통계 목록이라도 표시
      gems = gems.length > showLimit ? gems.slice(0, showLimit) : gems;
      apiLimited = true;
    } else {
      gems = [];
    }

    currentGems = gems;
    if (uiLang === "en" && currentGems.length) {
      showStatus(statusEl, t("translating"), "info");
      try {
        await translateCurrentGems();
      } catch (err) {
        console.warn(err);
      }
    }
    if (apiLimited && currentGems.length && !enriched.length) {
      showStatus(
        statusEl,
        uiLang === "en"
          ? "Tour API daily quota exceeded — showing names/stats only. Photos return tomorrow (or with an upgraded key)."
          : "관광공사 API 일일 호출 한도 초과 — 이름·통계만 표시합니다. 사진·상세는 내일(또는 운영계정)에 다시 불러올 수 있습니다.",
        "error"
      );
    } else {
      hideStatus(statusEl);
    }
    renderGems(currentGems);
    if (!currentGems.length) {
      resultsEl.innerHTML = "";
      const li = document.createElement("li");
      li.className = "empty-state";
      li.textContent =
        uiLang === "en"
          ? "No places with both photo and details were found."
          : "사진과 상세 정보가 모두 있는 추천 장소가 없습니다.";
      resultsEl.appendChild(li);
    }
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
    await maybeTranslatePlace(gem, cached);
    renderPlaceDetail(gem, cached);
    return;
  }

  placeBody.innerHTML = `<div class="place-loading">${uiLang === "en" ? "Loading…" : "관광 정보를 불러오는 중…"}</div>`;

  try {
    const data = await fetchPlaceDetail(gem);
    if (gemKey(gem) === key) {
      await maybeTranslatePlace(gem, data);
      renderPlaceDetail(gem, data);
    }
  } catch (e) {
    placeBody.innerHTML = `<div class="place-empty">${escapeHtml(e.message || "불러오지 못했습니다.")}</div>`;
  }
}

async function maybeTranslatePlace(gem, data) {
  if (uiLang !== "en" || !data || data._enReady) return;
  try {
    const texts = [];
    const slots = [];
    const push = (value, apply) => {
      texts.push(value == null ? "" : String(value));
      slots.push(apply);
    };
    push(data.title || gem.resNm, (v) => {
      data.titleEn = v;
    });
    push(data.addr || "", (v) => {
      data.addrEn = v;
    });
    push(data.overview || "", (v) => {
      data.overviewEn = v;
    });
    push(data.message || "", (v) => {
      data.messageEn = v;
    });
    for (const row of data.info || []) {
      push(row.label || "", (v) => {
        row.labelEn = v;
      });
      push(row.value || "", (v) => {
        row.valueEn = v;
      });
    }
    for (const n of [...(data.restaurants || []), ...(data.attractions || [])]) {
      push(n.title || "", (v) => {
        n.titleEn = v;
      });
      push(n.addr || "", (v) => {
        n.addrEn = v;
      });
    }
    const tr = await translateBatch(texts, "EN");
    slots.forEach((apply, i) => apply(tr[i] || texts[i]));
    data._enReady = true;
  } catch (err) {
    console.warn(err);
  }
}

function nearbyCardsHtml(list) {
  const filtered = (list || []).filter((p) => p && p.contentId && p.image && p.title);
  if (!filtered.length) {
    return `<p class="nearby-empty">${uiLang === "en" ? "No nearby places with photos." : "사진이 있는 주변 장소가 없습니다."}</p>`;
  }
  return `<div class="nearby-row">${filtered
    .map((p) => {
      const title = uiLang === "en" && p.titleEn ? p.titleEn : p.title;
      const addr = uiLang === "en" && p.addrEn ? p.addrEn : p.addr;
      return `
        <article class="nearby-card" role="button" tabindex="0"
          data-content-id="${escapeHtml(p.contentId)}"
          data-title="${escapeHtml(p.title || "")}"
          data-addr="${escapeHtml(p.addr || "")}"
          data-image="${escapeHtml(p.image || "")}">
          <div class="nearby-thumb"><img src="${escapeHtml(p.image)}" alt="" loading="lazy" /></div>
          <div class="nearby-meta">
            <h4 class="nearby-title">${escapeHtml(title || "")}</h4>
            <p class="nearby-dist">${escapeHtml(formatDist(p.dist))}${addr ? " · " + escapeHtml(addr) : ""}</p>
          </div>
        </article>`;
    })
    .join("")}</div>`;
}

function bindNearbyCards() {
  placeBody.querySelectorAll(".nearby-card[data-content-id]").forEach((card) => {
    const open = () => {
      openNearbyPlace({
        contentId: card.dataset.contentId,
        title: card.dataset.title || "",
        addr: card.dataset.addr || "",
        image: card.dataset.image || "",
      });
    };
    card.addEventListener("click", open);
    card.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        open();
      }
    });
  });
}

async function openNearbyPlace(place) {
  if (!place?.contentId) return;
  const gem = {
    resNm: place.title || "장소",
    sido: "",
    gungu: "",
    foreignVisitors: 0,
    domesticVisitors: 0,
    thumbnail: place.image || "",
  };
  if (!placeDialog.open) placeDialog.showModal();
  placeBody.innerHTML = `<div class="place-loading">${uiLang === "en" ? "Loading…" : "불러오는 중…"}</div>`;
  try {
    const params = new URLSearchParams({
      contentId: place.contentId,
      resNm: place.title || "",
    });
    const res = await fetch(`/api/place-detail?${params}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "상세 조회 실패");
    if (!data.found) {
      placeBody.innerHTML = `<div class="place-empty">${escapeHtml(
        data.message || (uiLang === "en" ? "Details not available." : "상세 정보가 없습니다.")
      )}</div>`;
      return;
    }
    placeDetailCache.set("cid:" + place.contentId, data);
    await maybeTranslatePlace(gem, data);
    renderPlaceDetail(gem, data);
  } catch (e) {
    placeBody.innerHTML = `<div class="place-empty">${escapeHtml(e.message || "불러오지 못했습니다.")}</div>`;
  }
}

function renderPlaceDetail(gem, data) {
  const location = displayGemLocation(gem);
  const title = displayGemName(gem);
  const letter = (title || "?").charAt(0);
  const heroImg = data.image || gem.thumbnail;
  const apiTitle =
    uiLang === "en"
      ? data.titleEn || data.title || ""
      : data.title || "";
  // 관광공사 명칭이 통계명과 다를 때만 보조로 표시 (오매칭 상호는 숨김)
  const showApiTitle =
    apiTitle &&
    apiTitle.replace(/\s+/g, "") !== String(gem.resNm || "").replace(/\s+/g, "") &&
    (String(apiTitle).includes(String(gem.resNm || "").slice(0, 4)) ||
      String(gem.resNm || "").includes(String(apiTitle).slice(0, 4)));

  if (!data.found) {
    placeBody.innerHTML = `
      <div class="place-hero"><div class="place-hero-fallback">${escapeHtml(letter)}</div></div>
      <div class="place-content">
        <div>
          <p class="place-kicker">${escapeHtml(location || (uiLang === "en" ? "Unknown" : "위치 미상"))}</p>
          <h2 class="place-title">${escapeHtml(displayGemName(gem))}</h2>
          <div class="place-stats">
            <span class="place-chip">${t("foreignVisitors")} ${formatNum(gem.foreignVisitors)}</span>
            <span class="place-chip">${t("domesticVisitors")} ${formatNum(gem.domesticVisitors)}</span>
          </div>
        </div>
        <p class="nearby-empty">${escapeHtml(
          (uiLang === "en" && data.messageEn) || data.message || (uiLang === "en" ? "Details not found." : "상세 정보를 찾지 못했습니다.")
        )}</p>
      </div>`;
    return;
  }

  const infoList = (data.info || [])
    .map((row) => {
      const label = uiLang === "en" && row.labelEn ? row.labelEn : row.label;
      const value = uiLang === "en" && row.valueEn ? row.valueEn : row.value;
      return `
      <li class="place-info-item">
        <span class="place-info-label">${escapeHtml(label)}</span>
        <span class="place-info-value">${escapeHtml(value)}</span>
      </li>`;
    })
    .join("");

  const overview =
    uiLang === "en" && data.overviewEn ? data.overviewEn : data.overview || "";
  const showMore = overview.length > 220;
  const telRaw = String(data.tel || "").split(/[,\n]/)[0].trim();
  const addr =
    uiLang === "en" && data.addrEn ? data.addrEn : data.addr || "";

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
        <p class="place-kicker">${escapeHtml(addr || location || "")}</p>
        <h2 class="place-title">${escapeHtml(title)}</h2>
        ${
          showApiTitle
            ? `<p class="place-api-title">${escapeHtml(apiTitle)}</p>`
            : ""
        }
        <div class="place-stats">
          <span class="place-chip">${t("foreignVisitors")} ${formatNum(gem.foreignVisitors)}</span>
          <span class="place-chip">${t("domesticVisitors")} ${formatNum(gem.domesticVisitors)}</span>
        </div>
      </header>

      <section>
        <h3 class="place-section-title">${uiLang === "en" ? "About" : "소개"}</h3>
        ${
          overview
            ? `<p class="place-overview" id="placeOverview">${escapeHtml(overview)}</p>
               ${showMore ? `<button type="button" class="place-more" id="placeMoreBtn">${uiLang === "en" ? "More" : "더 보기"}</button>` : ""}`
            : `<p class="nearby-empty">${uiLang === "en" ? "No description." : "소개글이 없습니다."}</p>`
        }
      </section>

      <section>
        <h3 class="place-section-title">${uiLang === "en" ? "Visitor info" : "이용 정보"}</h3>
        ${
          infoList
            ? `<ul class="place-info-list">${infoList}</ul>`
            : `<p class="nearby-empty">${uiLang === "en" ? "No visitor info." : "이용 정보가 없습니다."}</p>`
        }
        ${
          data.homepage || data.tel
            ? `<div class="place-links" style="margin-top:0.7rem">
                ${data.homepage ? `<a class="place-link" href="${escapeHtml(data.homepage)}" target="_blank" rel="noopener">${uiLang === "en" ? "Website" : "홈페이지"}</a>` : ""}
                ${data.tel ? `<a class="place-link" href="tel:${escapeHtml(telRaw)}">${uiLang === "en" ? "Call" : "전화"}</a>` : ""}
              </div>`
            : ""
        }
      </section>

      <section>
        <h3 class="place-section-title">${uiLang === "en" ? "Nearby food" : "근처 맛집"}</h3>
        ${nearbyCardsHtml(data.restaurants)}
      </section>

      <section>
        <h3 class="place-section-title">${uiLang === "en" ? "Nearby spots" : "근처에 가볼 곳"}</h3>
        ${nearbyCardsHtml(data.attractions)}
      </section>
    </div>`;

  const moreBtn = document.getElementById("placeMoreBtn");
  const overviewEl = document.getElementById("placeOverview");
  if (moreBtn && overviewEl) {
    moreBtn.addEventListener("click", () => {
      const open = overviewEl.classList.toggle("expanded");
      moreBtn.textContent = open
        ? uiLang === "en"
          ? "Less"
          : "접기"
        : uiLang === "en"
          ? "More"
          : "더 보기";
    });
  }
  bindNearbyCards();
}

let myView = "posts"; // posts | liked

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

function parseServerDate(regDate, regAt) {
  if (typeof regAt === "number" && Number.isFinite(regAt)) return regAt;
  if (regAt != null && regAt !== "" && !Number.isNaN(Number(regAt))) return Number(regAt);
  if (!regDate) return NaN;
  let s = String(regDate).trim().replace(" ", "T").replace(/\.\d+$/, "");
  // DB DATETIME은 UTC 저장 — 오프셋 없으면 UTC(Z)로 해석
  if (!/[zZ]|[+-]\d{2}:?\d{2}$/.test(s)) {
    s += "Z";
  }
  return Date.parse(s);
}

function formatRelativeTime(regDate, regAt) {
  if (!regDate && (regAt == null || regAt === "")) return "";
  const t = parseServerDate(regDate, regAt);
  if (Number.isNaN(t)) return String(regDate || "").slice(0, 16);
  const diff = Date.now() - t;
  const m = Math.floor(diff / 60000);
  if (m < 1) return "방금";
  if (m < 60) return `${m}분`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간`;
  const d = Math.floor(h / 24);
  if (d < 7) return `${d}일`;
  return formatKoreanDate(regDate, regAt);
}

function formatKoreanDate(regDate, regAt) {
  const t = parseServerDate(regDate, regAt);
  if (Number.isNaN(t)) return String(regDate || "").slice(0, 16);
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(t));
}

function categoryLabel(cat) {
  return cat === "FOREIGN" ? "외국인" : "내국인";
}

function threadCardHtml(p, { showCategory = false } = {}) {
  const name = p.nickname || p.memberId || "익명";
  const letter = String(name).charAt(0);
  const locationTitle = postField(p, "locationTitle");
  const address = postField(p, "address");
  const content = postField(p, "content");
  const place = locationTitle
    ? `<span class="thread-place">${escapeHtml(locationTitle)}${address ? ` · ${escapeHtml(address)}` : ""}</span>`
    : "";
  const media = p.imageUrl
    ? `<div class="thread-media"><img src="${escapeHtml(p.imageUrl)}" alt="" loading="lazy" /></div>`
    : "";
  const cat = showCategory
    ? `<span class="thread-cat">${escapeHtml(categoryLabel(p.category))}</span>`
    : "";
  const on = p.recommended ? "on" : "";
  return `
    <li class="thread-item" data-post-id="${p.postId}">
      <div class="thread-avatar" aria-hidden="true">${escapeHtml(letter)}</div>
      <div class="thread-main">
        <div class="thread-head">
          <span class="thread-name">${escapeHtml(name)}</span>
          <span class="thread-handle">@${escapeHtml(p.memberId || "")}</span>
          <span class="thread-time">· ${escapeHtml(formatRelativeTime(p.regDate, p.regAt))}</span>
          ${cat}
        </div>
        ${place}
        <p class="thread-text">${escapeHtml(content || "")}</p>
        ${media}
        <div class="thread-actions">
          <button type="button" class="thread-action thread-rec ${on}" data-action="recommend" aria-label="recommend">
            ♥ ${Number(p.recommendCount) || 0}
          </button>
          <button type="button" class="thread-action" data-action="reply" aria-label="reply">
            💬 ${Number(p.replyCount) || 0}
          </button>
        </div>
      </div>
    </li>`;
}

function bindThreadFeed(listEl) {
  listEl.querySelectorAll(".thread-item").forEach((li) => {
    li.addEventListener("click", (e) => {
      const actionBtn = e.target.closest("[data-action]");
      const postId = Number(li.dataset.postId);
      if (actionBtn?.dataset.action === "recommend") {
        e.preventDefault();
        e.stopPropagation();
        toggleRecommendFromFeed(postId, actionBtn);
        return;
      }
      openDetail(postId);
    });
  });
}

function renderBoardList(listEl, posts, opts = {}) {
  if (!posts.length) {
    listEl.innerHTML = `<li class="empty-state">${opts.emptyText || "아직 게시글이 없습니다. 글쓰기로 첫 글을 남겨 보세요."}</li>`;
    return;
  }
  listEl.innerHTML = posts.map((p) => threadCardHtml(p, opts)).join("");
  bindThreadFeed(listEl);
}

async function toggleRecommendFromFeed(postId, btn) {
  if (!requireLogin("추천은 로그인 후 이용할 수 있습니다.")) return;
  try {
    const res = await fetch(`/api/posts/${postId}/recommend`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ memberId: currentMemberId() }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "추천 실패");
    btn.classList.toggle("on", !!data.recommended);
    btn.innerHTML = `♥ ${Number(data.recommendCount) || 0}`;
    const cached = boardPosts.find((p) => p.postId === postId);
    if (cached) {
      cached.recommended = !!data.recommended;
      cached.recommendCount = Number(data.recommendCount) || 0;
    }
    if (currentDetail?.postId === postId) {
      currentDetail.recommended = !!data.recommended;
      currentDetail.recommendCount = Number(data.recommendCount) || 0;
      renderDetail();
    }
  } catch (err) {
    alert(err.message || "추천 실패");
  }
}

async function loadBoardPosts(tabId = activeTab) {
  const category = boardCategoryForTab(tabId);
  const statusId = category === "FOREIGN" ? "boardStatusForeign" : "boardStatusDomestic";
  const listId = category === "FOREIGN" ? "boardListForeign" : "boardListDomestic";
  const statusElBoard = document.getElementById(statusId);
  const listEl = document.getElementById(listId);

  showStatus(statusElBoard, "피드를 불러오는 중…", "info");
  const qs = new URLSearchParams({
    memberId: currentMemberId(),
    category,
  });
  try {
    const res = await fetch(`/api/posts?${qs}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "게시글 조회 실패");
    boardPosts = data.posts || [];
    if (uiLang === "en" && boardPosts.length) {
      showStatus(statusElBoard, t("translating"), "info");
      try {
        await translatePosts(boardPosts);
      } catch (err) {
        console.warn(err);
      }
    }
    hideStatus(statusElBoard);
    renderBoardList(listEl, boardPosts);
  } catch (e) {
    showStatus(statusElBoard, e.message || "오류", "error");
  }
}

async function loadMyPage() {
  const statusElBoard = document.getElementById("boardStatusMy");
  const listEl = document.getElementById("boardListMy");
  renderMyHeader();

  if (!currentMemberId()) {
    hideStatus(statusElBoard);
    listEl.innerHTML = `<li class="empty-state">${
      uiLang === "en"
        ? "Sign in to see your posts and liked posts."
        : "로그인하면 내가 쓴 글과 추천한 글을 볼 수 있습니다."
    }</li>`;
    return;
  }

  const qs = new URLSearchParams({ memberId: currentMemberId() });
  if (myView === "liked") {
    qs.set("likedBy", currentMemberId());
  } else {
    qs.set("author", currentMemberId());
  }

  showStatus(statusElBoard, uiLang === "en" ? "Loading…" : "불러오는 중…", "info");
  try {
    const res = await fetch(`/api/posts?${qs}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "조회 실패");
    boardPosts = data.posts || [];
    if (uiLang === "en" && boardPosts.length) {
      showStatus(statusElBoard, t("translating"), "info");
      try {
        await translatePosts(boardPosts);
      } catch (err) {
        console.warn(err);
      }
    }
    hideStatus(statusElBoard);
    renderBoardList(listEl, boardPosts, {
      showCategory: true,
      emptyText:
        myView === "liked"
          ? uiLang === "en"
            ? "No liked posts yet. Tap ♥ on the feed."
            : "아직 추천한 글이 없습니다. 피드에서 ♥를 눌러 보세요."
          : uiLang === "en"
            ? "You have not written any posts yet."
            : "아직 작성한 글이 없습니다. 글쓰기로 남겨 보세요.",
    });
  } catch (e) {
    showStatus(statusElBoard, e.message || "오류", "error");
  }
}

function setMyView(view) {
  myView = view === "liked" ? "liked" : "posts";
  document.querySelectorAll(".my-subtab").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.myView === myView);
  });
  loadMyPage();
}

async function openDetail(postId) {
  const qs = `?memberId=${encodeURIComponent(currentMemberId())}`;
  try {
    const res = await fetch(`/api/posts/${postId}${qs}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "조회 실패");
    currentDetail = data;
    if (uiLang === "en") {
      try {
        await translatePosts([currentDetail]);
        const replies = currentDetail.replies || [];
        const need = replies.filter((r) => !r._enContent && r.content);
        if (need.length) {
          const tr = await translateBatch(
            need.map((r) => r.content || ""),
            "EN"
          );
          need.forEach((r, i) => {
            r._enContent = tr[i] || r.content;
          });
        }
      } catch (err) {
        console.warn(err);
      }
    }
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
    <h2 class="detail-title">${escapeHtml(postField(p, "locationTitle") || (uiLang === "en" ? "Untitled place" : "장소 미정"))}</h2>
    <p class="detail-meta">${escapeHtml(p.nickname || p.memberId)} · ${escapeHtml(formatKoreanDate(p.regDate || "", p.regAt))}</p>
    <p class="detail-meta">${escapeHtml(postField(p, "address") || "")}</p>
    <p class="detail-content">${escapeHtml(postField(p, "content") || "")}</p>
    <p class="detail-meta">${uiLang === "en" ? "Likes" : "추천"} ${p.recommendCount} · ${uiLang === "en" ? "Comments" : "댓글"} ${(p.replies || []).length}</p>
  `;
  recommendBtn.textContent = p.recommended
    ? `${uiLang === "en" ? "Unlike" : "추천 취소"} (${p.recommendCount})`
    : `${uiLang === "en" ? "Like" : "추천"} (${p.recommendCount})`;
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
        ${escapeHtml((uiLang === "en" && r._enContent) || r.content || "")}
      </li>`
        )
        .join("")
    : `<li class="reply-item">${uiLang === "en" ? "No comments yet." : "아직 댓글이 없습니다."}</li>`;
}

function escapeHtml(s) {
  return String(s)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function refreshActiveFeed() {
  if (activeTab === "domestic" || activeTab === "foreign") {
    return loadBoardPosts(activeTab);
  }
  if (activeTab === "my") {
    return loadMyPage();
  }
  return Promise.resolve();
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
  } else if (tabId === "my") {
    loadMyPage();
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
    await refreshActiveFeed();
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
    await refreshActiveFeed();
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
    await refreshActiveFeed();
  } catch (err) {
    alert(err.message || "댓글 등록 실패");
  }
});

document.querySelectorAll(".my-subtab").forEach((btn) => {
  btn.addEventListener("click", () => setMyView(btn.dataset.myView));
});

async function setUiLang(lang) {
  uiLang = lang === "en" ? "en" : "ko";
  localStorage.setItem(LANG_STORAGE_KEY, uiLang);
  syncLangButtons();
  applyChromeI18n();
  renderAuthBar();
  try {
    if (activeTab === "ai") {
      if (uiLang === "en" && currentGems.length) {
        showStatus(statusEl, t("translating"), "info");
        await translateCurrentGems();
        hideStatus(statusEl);
      }
      renderGems(currentGems);
    } else if (activeTab === "domestic" || activeTab === "foreign") {
      await loadBoardPosts(activeTab);
    } else if (activeTab === "my") {
      await loadMyPage();
    }
  } catch (err) {
    alert(err.message || (uiLang === "en" ? "Translation failed" : "번역 실패"));
  }
}

document.querySelectorAll(".lang-btn").forEach((btn) => {
  btn.addEventListener("click", () => setUiLang(btn.dataset.lang));
});

currentUser = loadStoredUser();
renderAuthBar();
syncLangButtons();
applyChromeI18n();
loadRegions().then(() => loadHiddenGems());
