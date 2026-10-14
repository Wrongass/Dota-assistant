// Live-драфт: ходы вводятся здесь, советы считает сервер (POST /api/draft).
(() => {
  const root = document.getElementById("draft");
  const HEROES = JSON.parse(root.dataset.heroes);
  const OPPONENTS = JSON.parse(root.dataset.opponents);
  const ORDER = JSON.parse(root.dataset.order);
  const byId = new Map(HEROES.map((h) => [h.id, h]));
  const $ = (id) => document.getElementById(id);

  // Сокращения, которыми героев называют в игре (дополняются инициалами: WK, OD, PA…)
  const SLANG = {
    "вк": "Wraith King", "ам": "Anti-Mage", "пудж": "Pudge", "инвокер": "Invoker", "инвок": "Invoker",
    "леш": "Leshrac", "лешрак": "Leshrac", "од": "Outworld Devourer", "лич": "Lich", "лион": "Lion",
    "лина": "Lina", "вайпер": "Viper", "тини": "Tiny", "свен": "Sven", "сларк": "Slark",
    "кунка": "Kunkka", "куннка": "Kunkka", "варлок": "Warlock", "ворлок": "Warlock", "рубик": "Rubick",
    "энигма": "Enigma", "дб": "Dawnbreaker", "даунбрейкер": "Dawnbreaker", "огр": "Ogre Magi",
    "кентавр": "Centaur Warrunner", "цм": "Crystal Maiden", "см": "Crystal Maiden", "вс": "Vengeful Spirit",
    "венга": "Vengeful Spirit", "грим": "Grimstroke", "дизраптор": "Disruptor", "никс": "Nyx Assassin",
    "сб": "Spirit Breaker", "бара": "Spirit Breaker", "дк": "Dragon Knight", "некр": "Necrophos",
    "некрофос": "Necrophos", "войд": "Faceless Void", "фв": "Faceless Void", "пл": "Phantom Lancer",
    "па": "Phantom Assassin", "сф": "Shadow Fiend", "шторм": "Storm Spirit", "эмбер": "Ember Spirit",
    "котл": "Keeper of the Light", "котел": "Keeper of the Light", "бх": "Bounty Hunter", "ww": "Winter Wyvern",
    "вивер": "Winter Wyvern", "андерлорд": "Underlord", "андер": "Underlord", "дроу": "Drow Ranger",
    "джагер": "Juggernaut", "джаг": "Juggernaut", "ведьмак": "Witch Doctor", "вд": "Witch Doctor",
    "шаман": "Shadow Shaman", "раста": "Shadow Shaman", "акс": "Axe", "мипо": "Meepo", "тид": "Tidehunter",
    "тайд": "Tidehunter", "хускар": "Huskar", "луна": "Luna", "мирана": "Mirana", "уилоу": "Dark Willow",
    "вилоу": "Dark Willow", "худ": "Hoodwink", "худвинк": "Hoodwink", "джакиро": "Jakiro", "абаддон": "Abaddon",
    "аба": "Abaddon", "омни": "Omniknight", "клинкз": "Clinkz", "санд": "Sand King", "ск": "Sand King",
  };
  const NAME_TO_ID = new Map(HEROES.map((h) => [h.name.toLowerCase(), h.id]));
  const ALIASES = new Map();
  for (const h of HEROES) {
    const words = h.name.toLowerCase().replace(/'/g, "").split(/[\s-]+/);
    if (words.length > 1) ALIASES.set(words.map((w) => w[0]).join(""), h.id);
  }
  for (const [alias, name] of Object.entries(SLANG)) {
    const id = NAME_TO_ID.get(name.toLowerCase());
    if (id) ALIASES.set(alias, id);
  }

  // ------------------------------------------------------------ состояние
  let state = { opponent: root.dataset.defaultOpponent, weFirst: true, heroes: [] };
  const key = () => "draft:" + state.opponent;
  const load = () => {
    try {
      const saved = JSON.parse(localStorage.getItem(key()) || "null");
      if (saved) Object.assign(state, { weFirst: saved.weFirst, heroes: saved.heroes || [] });
    } catch (e) { /* без localStorage просто начинаем заново */ }
  };
  const save = () => {
    try { localStorage.setItem(key(), JSON.stringify({ weFirst: state.weFirst, heroes: state.heroes })); } catch (e) { /* ок */ }
  };
  const used = () => new Set(state.heroes.filter((h) => h > 0));
  const slot = (i) => ORDER[i];
  const isOurs = (i) => slot(i).firstTeam === state.weFirst;

  // ------------------------------------------------------------ ввод ходов
  function apply(heroId) {
    if (state.heroes.length >= ORDER.length || used().has(heroId)) return;
    state.heroes.push(heroId);
    afterChange();
  }
  function afterChange() {
    save();
    history.replaceState(null, "", `#first=${state.weFirst ? "us" : "them"}&heroes=${state.heroes.join(",")}`);
    $("search").value = "";
    renderSearch();
    renderBoard();
    requestAdvice();
    $("search").focus();
  }
  $("undo").onclick = () => { state.heroes.pop(); afterChange(); };
  $("skip").onclick = () => { if (state.heroes.length < ORDER.length) { state.heroes.push(0); afterChange(); } };
  $("reset").onclick = () => { if (confirmReset()) { state.heroes = []; afterChange(); } };
  // без браузерного confirm(): второй клик в течение 3 секунд подтверждает сброс
  let resetArmed = 0;
  function confirmReset() {
    const now = Date.now();
    if (now - resetArmed < 3000) { resetArmed = 0; $("reset").textContent = "Сбросить"; return true; }
    resetArmed = now;
    $("reset").textContent = "Точно сбросить?";
    setTimeout(() => { $("reset").textContent = "Сбросить"; }, 3000);
    return false;
  }
  document.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "z") { e.preventDefault(); $("undo").click(); }
  });

  // ------------------------------------------------------------ поиск
  function search(q) {
    q = q.trim().toLowerCase().replace(/'/g, "");
    if (!q) return [];
    const taken = used();
    const scored = [];
    const alias = ALIASES.get(q);
    for (const h of HEROES) {
      if (taken.has(h.id)) continue;
      const name = h.name.toLowerCase().replace(/'/g, "");
      let score = -1;
      if (h.id === alias || name === q) score = 0;
      else if (name.startsWith(q)) score = 1;
      else if (name.split(/[\s-]+/).some((w) => w.startsWith(q))) score = 2;
      else if (name.includes(q)) score = 3;
      if (score >= 0) scored.push([score, h]);
    }
    return scored.sort((a, b) => a[0] - b[0] || a[1].name.localeCompare(b[1].name)).map((x) => x[1]);
  }
  function renderSearch() {
    const found = search($("search").value);
    $("search-hint").textContent = found.length ? "Enter → " + found.slice(0, 5).map((h) => h.name).join(", ") : "";
    document.querySelectorAll("#grid .hero").forEach((b) => {
      const q = $("search").value.trim();
      b.classList.toggle("match", q !== "" && found.some((h) => h.id === Number(b.dataset.id)));
    });
  }
  $("search").addEventListener("input", renderSearch);
  $("search").addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      const found = search($("search").value);
      if (found.length) apply(found[0].id);
    } else if (e.key === "Escape") {
      $("search").value = "";
      renderSearch();
    }
  });

  // ------------------------------------------------------------ отрисовка
  const heroChip = (id, cls = "") => `<span class="chip ${cls}">${byId.get(id)?.name ?? "—"}</span>`;

  function renderTurn() {
    const step = state.heroes.length;
    const turn = $("turn");
    ["panel-their-next", "panel-bans", "panel-picks"].forEach((p) => $(p).classList.remove("focus"));
    if (step >= ORDER.length) {
      turn.textContent = "Драфт завершён";
      turn.className = "turn";
      return;
    }
    const s = slot(step), ours = isOurs(step);
    turn.textContent = `Ход ${step + 1}/${ORDER.length}: ${s.pick ? "ПИК" : "БАН"} ${ours ? "НАШ" : "ИХ"}`;
    turn.className = "turn " + (ours ? "ours-turn" : "their-turn");
    $(ours ? (s.pick ? "panel-picks" : "panel-bans") : (s.pick ? "panel-their-next" : "panel-bans")).classList.add("focus");
  }

  function renderBoard(advice) {
    renderTurn();
    const parts = { ourBans: [], ourPicks: [], theirBans: [], theirPicks: [] };
    state.heroes.forEach((h, i) => {
      if (h <= 0) return;
      const ours = isOurs(i);
      parts[(ours ? "our" : "their") + (slot(i).pick ? "Picks" : "Bans")].push(h);
    });
    $("our-bans").innerHTML = parts.ourBans.map((h) => heroChip(h, "ban")).join("") || "—";
    $("their-bans").innerHTML = parts.theirBans.map((h) => heroChip(h, "ban")).join("") || "—";
    const assignLine = (a) => `<div class="assign"><b>${a.name}</b> → ${a.player} <span class="muted">${pct(a.probability)}${
      a.alternative ? ` (${a.alternative} ${pct(a.alternativeProbability)})` : ""}</span></div>`;
    const our = advice?.ourAssignment ?? [];
    const their = advice?.theirAssignment ?? [];
    $("our-picks").innerHTML = parts.ourPicks.length
      ? parts.ourPicks.map((h) => { const a = our.find((x) => x.heroId === h); return a ? assignLine(a) : `<div class="assign"><b>${byId.get(h).name}</b></div>`; }).join("")
      : "—";
    $("their-picks").innerHTML = parts.theirPicks.length
      ? parts.theirPicks.map((h) => { const a = their.find((x) => x.heroId === h); return a ? assignLine(a) : `<div class="assign"><b>${byId.get(h).name}</b></div>`; }).join("")
      : "—";
    const taken = used();
    document.querySelectorAll("#grid .hero").forEach((b) => {
      b.disabled = taken.has(Number(b.dataset.id)) || state.heroes.length >= ORDER.length;
    });
  }

  const pct = (x) => (100 * x).toFixed(x < 0.1 ? 1 : 0) + "%";
  const reasons = (r) => (r.reasons || []).map((x) => `<span class="reason">${x}</span>`).join(" ");
  const pickable = (r, extra) => `<li><button class="hero-pick" data-id="${r.heroId}">${r.name}</button> ${extra} ${reasons(r)}</li>`;

  function renderAdvice(a) {
    $("their-next").innerHTML = a.theirNextPicks.map((r) =>
      pickable(r, `<b>${pct(r.score)}</b> <span class="muted">${r.player}</span>`)).join("");
    $("bans").innerHTML = a.banSuggestions.map((r) => pickable(r, `<b>${r.score.toFixed(2)}</b>`)).join("");
    $("picks").innerHTML = a.pickSuggestions.map((r) =>
      pickable(r, `<b>${r.score.toFixed(0)}</b> <span class="muted">${r.player}</span>`)).join("");
    $("their-likely-bans").innerHTML = a.theirLikelyBans.length
      ? a.theirLikelyBans.map((r) => `<span class="chip" title="${r.reasons.join(", ")}">${r.name} ×${r.score}</span>`).join("")
      : '<span class="muted">нет истории банов</span>';
    renderBoard(a);
  }
  root.addEventListener("click", (e) => {
    const b = e.target.closest(".hero-pick, #grid .hero");
    if (b && !b.disabled) apply(Number(b.dataset.id));
  });

  // ------------------------------------------------------------ сервер
  let inflight = null;
  async function requestAdvice() {
    if (inflight) inflight.abort();
    inflight = new AbortController();
    const started = performance.now();
    try {
      const res = await fetch("/api/draft", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ opponent: state.opponent, weFirst: state.weFirst, heroes: state.heroes }),
        signal: inflight.signal,
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || res.status);
      renderAdvice(data);
      $("timing").textContent = `пересчёт ${Math.round(performance.now() - started)} мс`;
    } catch (e) {
      if (e.name !== "AbortError") $("timing").textContent = "ошибка: " + e.message;
    }
  }

  // ------------------------------------------------------------ старт
  const ATTR = { str: "Сила", agi: "Ловкость", int: "Интеллект", all: "Универсальные" };
  $("grid").innerHTML = Object.entries(ATTR).map(([attr, title]) =>
    `<div class="attr"><div class="small muted">${title}</div>${HEROES.filter((h) => h.attr === attr)
      .map((h) => `<button class="hero" data-id="${h.id}">${h.name}</button>`).join("")}</div>`).join("");

  $("opponent").innerHTML = OPPONENTS.map((o) => `<option value="${o.href}">${o.label}</option>`).join("");
  $("opponent").value = state.opponent;
  $("opponent").onchange = (e) => {
    state.opponent = e.target.value;
    state.heroes = [];
    state.weFirst = true;
    load();
    $("first").value = state.weFirst ? "us" : "them";
    afterChange();
  };
  $("first").onchange = (e) => { state.weFirst = e.target.value === "us"; afterChange(); };

  load();
  // Драфт из адреса: /draft?opponent=X#first=us&heroes=23,22,0,42 (0 — пропущенный ход) — чтобы поделиться ссылкой
  const hash = new URLSearchParams(location.hash.slice(1));
  if (hash.has("heroes")) {
    state.heroes = hash.get("heroes").split(",").filter((x) => x !== "").map(Number).slice(0, ORDER.length);
    state.weFirst = hash.get("first") !== "them";
    save();
  }
  $("first").value = state.weFirst ? "us" : "them";
  renderBoard();
  requestAdvice();
})();
