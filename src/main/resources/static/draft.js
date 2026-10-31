// Live-драфт: ходы вводятся здесь, советы считает сервер (POST /api/draft).
(() => {
  const root = document.getElementById("draft");
  const HEROES = JSON.parse(root.dataset.heroes);
  const OPPONENTS = JSON.parse(root.dataset.opponents);
  const ORDER = JSON.parse(root.dataset.order);
  const POOL = JSON.parse(root.dataset.pool); // id героя → ["Wrongass (поз. 2)", …]
  const byId = new Map(HEROES.map((h) => [h.id, h]));
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

  // Сокращения, которыми героев называют в игре (дополняются инициалами: WK, OD, PA…).
  // Ищутся и по началу: «кунк» → Kunkka.
  const SLANG = {
    "вк": "Wraith King", "ам": "Anti-Mage", "пудж": "Pudge", "инвокер": "Invoker", "инвок": "Invoker",
    "леш": "Leshrac", "лешрак": "Leshrac", "од": "Outworld Devourer", "лич": "Lich", "лион": "Lion",
    "лина": "Lina", "вайпер": "Viper", "тини": "Tiny", "свен": "Sven", "сларк": "Slark",
    "кунка": "Kunkka", "куннка": "Kunkka", "варлок": "Warlock", "ворлок": "Warlock", "рубик": "Rubick",
    "энигма": "Enigma", "дб": "Dawnbreaker", "даунбрейкер": "Dawnbreaker", "огр": "Ogre Magi",
    "кентавр": "Centaur Warrunner", "цм": "Crystal Maiden", "см": "Crystal Maiden", "вс": "Vengeful Spirit",
    "венга": "Vengeful Spirit", "грим": "Grimstroke", "гримстрок": "Grimstroke", "дизраптор": "Disruptor",
    "никс": "Nyx Assassin", "сб": "Spirit Breaker", "бара": "Spirit Breaker", "дк": "Dragon Knight",
    "некр": "Necrophos", "некрофос": "Necrophos", "войд": "Faceless Void", "фв": "Faceless Void",
    "пл": "Phantom Lancer", "па": "Phantom Assassin", "сф": "Shadow Fiend", "шторм": "Storm Spirit",
    "эмбер": "Ember Spirit", "котл": "Keeper of the Light", "котел": "Keeper of the Light", "бх": "Bounty Hunter",
    "ww": "Winter Wyvern", "вивер": "Winter Wyvern", "виверна": "Winter Wyvern", "андерлорд": "Underlord",
    "андер": "Underlord", "дроу": "Drow Ranger", "джагер": "Juggernaut", "джаг": "Juggernaut",
    "джагернаут": "Juggernaut", "ведьмак": "Witch Doctor", "вд": "Witch Doctor", "шаман": "Shadow Shaman",
    "раста": "Shadow Shaman", "акс": "Axe", "мипо": "Meepo", "тид": "Tidehunter", "тайд": "Tidehunter",
    "тайдхантер": "Tidehunter", "хускар": "Huskar", "луна": "Luna", "мирана": "Mirana", "уилоу": "Dark Willow",
    "вилоу": "Dark Willow", "худ": "Hoodwink", "худвинк": "Hoodwink", "джакиро": "Jakiro", "абаддон": "Abaddon",
    "аба": "Abaddon", "омни": "Omniknight", "клинкз": "Clinkz", "санд": "Sand King", "ск": "Sand King",
    "легионка": "Legion Commander", "лц": "Legion Commander", "найт": "Night Stalker", "нс": "Night Stalker",
    "спектра": "Spectre", "спектр": "Spectre", "вр": "Windranger", "виндра": "Windranger",
    "виндрейнджер": "Windranger", "слардар": "Slardar", "разор": "Razor", "бист": "Beastmaster",
    "бистмастер": "Beastmaster", "снайпер": "Sniper", "тимбер": "Timbersaw", "лайфстилер": "Lifestealer",
    "гуль": "Lifestealer", "наикс": "Lifestealer", "силенсер": "Silencer", "сайленсер": "Silencer",
    "текис": "Techies", "течис": "Techies", "квопа": "Queen of Pain", "квоп": "Queen of Pain",
    "королева": "Queen of Pain", "рингмастер": "Ring Master", "феникс": "Phoenix", "веном": "Venomancer",
    "веномансер": "Venomancer", "снапфаер": "Snapfire", "снеп": "Snapfire", "ларго": "Largo", "энча": "Enchantress",
    "чен": "Chen", "зевс": "Zeus", "пак": "Puck", "пук": "Puck", "морф": "Morphling", "монки": "Monkey King",
    "мк": "Monkey King", "урса": "Ursa", "медуза": "Medusa", "дарк сир": "Dark Seer", "дс": "Dark Seer",
    "доом": "Doom", "дум": "Doom", "марс": "Mars", "брю": "Brewmaster",
    "антимаг": "Anti-Mage", "эс": "Earth Spirit", "эшейкер": "Earthshaker", "шейкер": "Earthshaker",
    "ио": "Io", "висп": "Io", "оракл": "Oracle", "дазл": "Dazzle", "бейн": "Bane", "варик": "Warlock",
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
  const ROSTERS = JSON.parse(root.dataset.rosters); // {"ours": [...], "<slug>": [...]}

  // ------------------------------------------------------------ состояние
  let state = { opponent: root.dataset.defaultOpponent, weFirst: true, heroes: [], assign: {} };
  let advice = null;
  let editIndex = null; // исправляемый ход (клик по ленте)
  let pendingWarn = null; // герой, для которого показано предупреждение: второй ввод — подтверждение
  let selected = 0; // выбранная строка в списке поиска
  const key = () => "draft:" + state.opponent;
  const load = () => {
    try {
      const saved = JSON.parse(localStorage.getItem(key()) || "null");
      if (saved) Object.assign(state, { weFirst: saved.weFirst, heroes: saved.heroes || [], assign: saved.assign || {} });
    } catch (e) { /* без localStorage просто начинаем заново */ }
  };
  const save = () => {
    try {
      localStorage.setItem(key(), JSON.stringify({ weFirst: state.weFirst, heroes: state.heroes, assign: state.assign }));
    } catch (e) { /* ок */ }
  };
  const used = () => new Set(state.heroes.filter((h) => h > 0));
  const slot = (i) => ORDER[i];
  const isOurs = (i) => slot(i).firstTeam === state.weFirst;
  const name = (id) => byId.get(id)?.name ?? "—";
  const icon = (id) => heroIcon(id, name(id));
  const pct = (x) => (100 * x).toFixed(x < 0.1 ? 1 : 0) + "%";

  // ------------------------------------------------------------ ввод ходов
  /** Предупреждение перед подозрительным ходом: наш бан нашего героя или наш пик не из наших пулов. */
  function warning(i, heroId) {
    if (i >= ORDER.length) return null;
    const owners = POOL[heroId];
    if (isOurs(i) && !slot(i).pick && owners) {
      return `${name(heroId)} — герой наших: ${owners.join(", ")}. Это НАШ бан. Ещё раз Enter или клик — забанить.`;
    }
    if (isOurs(i) && slot(i).pick && !owners) {
      return `${name(heroId)} нет в наших пулах. Это НАШ пик? Ещё раз Enter или клик — внести.`;
    }
    return null;
  }

  function apply(heroId) {
    const target = editIndex ?? state.heroes.length;
    if (target >= ORDER.length) return;
    if (used().has(heroId) && state.heroes[target] !== heroId) return;
    const w = warning(target, heroId);
    if (w && pendingWarn !== heroId) {
      pendingWarn = heroId;
      showWarn(w);
      return;
    }
    pendingWarn = null;
    if (editIndex !== null) {
      const old = state.heroes[editIndex];
      if (old > 0) delete state.assign[old];
      state.heroes[editIndex] = heroId;
      editIndex = null;
    } else {
      state.heroes.push(heroId);
    }
    afterChange();
  }
  function showWarn(text) {
    $("warn").textContent = text;
    $("warn").hidden = !text;
  }
  function afterChange() {
    pendingWarn = null;
    showWarn("");
    // ручное назначение — только для героев, которые ещё в пиках
    const picks = new Set(state.heroes.filter((h, i) => h > 0 && slot(i).pick));
    Object.keys(state.assign).forEach((h) => { if (!picks.has(Number(h))) delete state.assign[h]; });
    save();
    const assign = Object.entries(state.assign).map(([h, p]) => `${h}:${encodeURIComponent(p)}`).join(",");
    history.replaceState(null, "", `#first=${state.weFirst ? "us" : "them"}&heroes=${state.heroes.join(",")}`
      + (assign ? `&assign=${assign}` : ""));
    $("search").value = "";
    selected = 0;
    renderSearch();
    renderBoard();
    requestAdvice();
    $("search").focus();
  }
  $("undo").onclick = () => {
    if (editIndex !== null) { editIndex = null; renderBoard(); return; }
    const h = state.heroes.pop();
    if (h > 0) delete state.assign[h];
    afterChange();
  };
  $("skip").onclick = () => {
    if (editIndex !== null) { state.heroes[editIndex] = 0; editIndex = null; afterChange(); return; }
    if (state.heroes.length < ORDER.length) { state.heroes.push(0); afterChange(); }
  };
  $("reset").onclick = () => { if (confirmReset()) { state.heroes = []; state.assign = {}; editIndex = null; afterChange(); } };
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
    if (e.key === "Escape" && !$("drawer").hidden) closeDrawer();
  });

  // ------------------------------------------------------------ поиск
  function search(q) {
    q = q.trim().toLowerCase().replace(/'/g, "");
    if (!q) return [];
    const taken = used();
    const current = editIndex !== null ? state.heroes[editIndex] : -1;
    const exact = ALIASES.get(q);
    const prefixAliases = new Set();
    if (q.length >= 2) {
      for (const [alias, id] of ALIASES) if (alias.startsWith(q)) prefixAliases.add(id);
    }
    const scored = [];
    for (const h of HEROES) {
      if (taken.has(h.id) && h.id !== current) continue;
      const n = h.name.toLowerCase().replace(/'/g, "");
      let score = -1;
      if (h.id === exact || n === q) score = 0;
      else if (n.startsWith(q)) score = 1;
      else if (prefixAliases.has(h.id)) score = 1.5;
      else if (n.split(/[\s-]+/).some((w) => w.startsWith(q))) score = 2;
      else if (n.includes(q)) score = 3;
      if (score >= 0) scored.push([score, h]);
    }
    return scored.sort((a, b) => a[0] - b[0] || a[1].name.localeCompare(b[1].name)).map((x) => x[1]);
  }
  function renderSearch() {
    const found = search($("search").value).slice(0, 6);
    if (selected >= found.length) selected = 0;
    $("suggest").innerHTML = found.map((h, i) =>
      `<li class="${i === selected ? "sel" : ""}" data-id="${h.id}">${icon(h.id)} ${esc(h.name)}${POOL[h.id]
        ? ` <span class="muted small">наш: ${esc(POOL[h.id].join(", "))}</span>` : ""}</li>`).join("");
    const q = $("search").value.trim();
    document.querySelectorAll("#grid .hero-tile").forEach((b) => {
      b.classList.toggle("match", q !== "" && found.some((h) => h.id === Number(b.dataset.id)));
    });
  }
  // pendingWarn не сбрасываем: повторный ввод того же героя после предупреждения — тоже подтверждение
  $("search").addEventListener("input", () => { selected = 0; renderSearch(); });
  $("search").addEventListener("keydown", (e) => {
    const found = search($("search").value).slice(0, 6);
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (found.length) selected = (selected + (e.key === "ArrowDown" ? 1 : found.length - 1)) % found.length;
      renderSearch();
    } else if (e.key === "Enter") {
      if (found.length) apply(found[Math.min(selected, found.length - 1)].id);
      else if (pendingWarn) apply(pendingWarn);
    } else if (e.key === "Escape") {
      $("search").value = "";
      editIndex = null;
      pendingWarn = null;
      showWarn("");
      renderSearch();
      renderBoard();
    }
  });
  $("suggest").addEventListener("click", (e) => {
    const li = e.target.closest("li[data-id]");
    if (li) apply(Number(li.dataset.id));
  });

  // ------------------------------------------------------------ лента ходов
  function renderRibbon() {
    const step = state.heroes.length;
    $("ribbon").innerHTML = ORDER.map((s, i) => {
      const h = state.heroes[i];
      const cls = ["slot", isOurs(i) ? "ours" : "theirs", s.pick ? "pick" : "ban"];
      if (i === step && editIndex === null) cls.push("current");
      if (i === editIndex) cls.push("editing");
      if (i > step) cls.push("future");
      const label = h > 0 ? `${icon(h)}<span class="who">${esc(name(h))}</span>` : h === 0 ? '<span class="who muted">пропуск</span>' : "";
      return `<button class="${cls.join(" ")}" data-i="${i}" ${i >= step ? "disabled" : ""}
        title="Ход ${i + 1}: ${s.pick ? "пик" : "бан"} ${isOurs(i) ? "наш" : "их"}">
        <span class="kind">${i + 1} ${s.pick ? "ПИК" : "бан"} · ${isOurs(i) ? "мы" : "они"}</span>${label}</button>`;
    }).join("");
    $("ribbon-hint").textContent = editIndex !== null
      ? `Исправляете ход ${editIndex + 1} (${slot(editIndex).pick ? "пик" : "бан"} ${isOurs(editIndex) ? "наш" : "их"}): выберите героя. «Пропустить ход» — сделать пропуском, Esc — отмена.`
      : "Клик по сделанному ходу — исправить его.";
  }
  $("ribbon").addEventListener("click", (e) => {
    const b = e.target.closest(".slot");
    if (!b || b.disabled) return;
    const i = Number(b.dataset.i);
    editIndex = editIndex === i ? null : i;
    pendingWarn = null;
    showWarn("");
    renderBoard();
    $("search").focus();
  });

  function renderTurn() {
    const step = state.heroes.length;
    const turn = $("turn");
    if (step >= ORDER.length) {
      turn.textContent = "Драфт завершён";
      turn.className = "turn";
      return;
    }
    const s = slot(step), ours = isOurs(step);
    turn.textContent = `Ход ${step + 1}/${ORDER.length}: ${s.pick ? "ПИК" : "БАН"} ${ours ? "НАШ" : "ИХ"}`;
    turn.className = "turn " + (ours ? "ours-turn" : "their-turn");
  }

  // ------------------------------------------------------------ слоты игроков
  /** Чем играет последние 14 дней: иконки с числом игр и пометками 🔥 (новый) и 👥 (с командой). */
  const recentLine = (p) => p.recent.length
    ? p.recent.map((h) => `<span class="mini" title="${esc(h.name)}: ${h.g14} игр за 14 дней, ${h.g60} за 2 месяца${h.team ? `, ${h.team} с командой` : ""}">${icon(h.id)}${h.g14 || h.g60}${h.fresh ? "🔥" : ""}${h.team ? "👥" : ""}</span>`).join("")
    : '<span class="muted">нет игр</span>';

  function playerSlot(p, side, picks) {
    const a = side.assignment.find((x) => x.player === p.nick);
    const free = side.remaining.find((r) => r.player === p.nick);
    const outlook = side.outlook.find((o) => o.player === p.nick);
    const hero = a ? a.heroId : null;
    const options = picks.map((h) =>
      `<option value="${h}" ${hero === h && a.fixed ? "selected" : ""}>${esc(name(h))}</option>`).join("");
    const heroLine = hero
      ? `<div class="slot-hero">${heroPortrait(hero, name(hero))}<div><b>${esc(name(hero))}</b>
           <div class="small muted">${a.fixed ? "вручную" : pct(a.probability) + (a.alternative ? ` · или ${esc(a.alternative)} ${pct(a.alternativeProbability)}` : "")}</div></div></div>`
      : `<div class="slot-hero empty"><span class="hero-portrait empty"></span><span class="muted small">${free && free.probability < 0.95 ? `свободен ${pct(free.probability)}` : "без героя"}</span></div>`;
    const odds = !hero && outlook && outlook.heroes.length
      ? `<div class="odds small">вероятно: ${outlook.heroes.slice(0, 3).map((h) =>
          `<button class="hero-pick link" data-id="${h.heroId}">${icon(h.heroId)}${esc(h.name)}</button> ${pct(h.probability)}`).join(" ")}</div>`
      : "";
    return `<div class="pslot ${hero ? "picked" : ""}">
      ${heroLine}
      <div class="slot-player"><span class="pos">${p.pos}</span>
        <button class="nick link" data-account="${p.accountId}" title="Карточка игрока">${esc(p.nick)}</button>
        ${rankIcon(p.rankTier, p.rank)}
        ${profiles(p.accountId)}
        ${picks.length ? `<select class="slot-assign" data-player="${esc(p.nick)}" title="Кто на каком герое: выбрать вручную"><option value="">авто</option>${options}</select>` : ""}
      </div>
      <div class="recent small" title="Последние 14 дней (без Турбо)">14д: ${recentLine(p)}</div>
      ${odds}
    </div>`;
  }
  const rankIcon = (tier, title) => {
    const medal = Math.floor(tier / 10), star = tier % 10;
    if (medal < 1 || medal > 8) return "";
    return `<span class="rank" title="${esc(title)}"><img class="rank-ico" src="/img/ranks/rank_icon_${medal}.png" alt="">${
      medal < 8 && star >= 1 && star <= 5 ? `<img class="rank-star" src="/img/ranks/rank_star_${star}.png" alt="">` : ""}</span>`;
  };
  const profiles = (id) => `<span class="profiles"><a href="https://ru.dotabuff.com/players/${id}" target="_blank" title="Dotabuff">DB</a><a href="https://www.opendota.com/players/${id}" target="_blank" title="OpenDota">OD</a><a href="https://stratz.com/players/${id}" target="_blank" title="STRATZ">ST</a></span>`;

  root.addEventListener("change", (e) => {
    const sel = e.target.closest(".slot-assign");
    if (!sel) return;
    const player = sel.dataset.player;
    // один игрок — один герой: снимаем ручные назначения этого игрока и героя
    Object.entries(state.assign).forEach(([h, p]) => { if (p === player) delete state.assign[h]; });
    if (sel.value) {
      delete state.assign[sel.value];
      state.assign[sel.value] = player;
    }
    afterChange();
  });

  function renderBoard() {
    renderTurn();
    renderRibbon();
    const parts = { ourBans: [], ourPicks: [], theirBans: [], theirPicks: [] };
    state.heroes.forEach((h, i) => {
      if (h <= 0) return;
      parts[(isOurs(i) ? "our" : "their") + (slot(i).pick ? "Picks" : "Bans")].push(h);
    });
    const banRow = (list) => list.map((h) => `<span class="ban-chip" title="${esc(name(h))}">${icon(h)}</span>`).join("") || '<span class="muted">—</span>';
    $("our-bans").innerHTML = banRow(parts.ourBans);
    $("their-bans").innerHTML = banRow(parts.theirBans);
    const ours = { assignment: advice?.ourAssignment ?? [], remaining: advice?.ourRemaining ?? [], outlook: [] };
    const theirs = { assignment: advice?.theirAssignment ?? [], remaining: advice?.theirRemaining ?? [], outlook: advice?.theirPlayers ?? [] };
    $("our-slots").innerHTML = (ROSTERS.ours || []).map((p) => playerSlot(p, ours, parts.ourPicks)).join("");
    $("their-slots").innerHTML = (ROSTERS[state.opponent] || []).map((p) => playerSlot(p, theirs, parts.theirPicks)).join("");
    $("their-title").textContent = $("opponent").selectedOptions[0]?.textContent || "Они";
    const taken = used();
    document.querySelectorAll("#grid .hero-tile").forEach((b) => {
      const id = Number(b.dataset.id);
      b.disabled = (taken.has(id) && !(editIndex !== null && state.heroes[editIndex] === id))
        || (state.heroes.length >= ORDER.length && editIndex === null);
    });
    layoutPanels();
  }

  /** Нужная на этом ходу панель — первой и с рамкой; панели, ходы которых закончились, — прячем. */
  function layoutPanels() {
    const step = state.heroes.length;
    const finished = step >= ORDER.length;
    const a = advice;
    ["panel-their", "panel-bans", "panel-picks"].forEach((p) => { $(p).hidden = finished; });
    $("summary").hidden = !finished || !a || !a.finished;
    if (finished || !a) return;
    const s = slot(step), ours = isOurs(step);
    $("their-next-box").hidden = a.theirPicksLeft === 0;
    $("their-bans-box").hidden = a.theirBansLeft === 0;
    $("panel-their").hidden = a.theirPicksLeft === 0 && a.theirBansLeft === 0;
    $("panel-bans").hidden = a.ourBansLeft === 0;
    $("panel-picks").hidden = a.ourPicksLeft === 0;
    const focus = ours ? (s.pick ? "panel-picks" : "panel-bans") : "panel-their";
    ["panel-their", "panel-bans", "panel-picks"].forEach((p) => {
      $(p).classList.toggle("focus", p === focus);
      $(p).style.order = p === focus ? 0 : 1;
    });
    // на их ходу наверху то, что они сделают сейчас: пик или бан
    if (!ours) {
      $("panel-their").insertBefore(s.pick ? $("their-next-box") : $("their-bans-box"), $("panel-their").firstChild);
    }
  }

  // ------------------------------------------------------------ подсказки
  const reasons = (r, n) => (r.reasons || []).slice(0, n).map((x) => `<span class="reason">${esc(x)}</span>`).join(" ");
  const more = (r, n) => (r.reasons || []).length > n ? `<span class="reason more" title="${esc(r.reasons.slice(n).join(" · "))}">+${r.reasons.length - n}</span>` : "";
  const pickable = (r, extra) => `<li><button class="hero-pick" data-id="${r.heroId}">${icon(r.heroId)}${esc(r.name)}</button>
    ${extra} ${reasons(r, 2)} ${more(r, 2)}</li>`;

  function renderAdvice(a) {
    advice = a;
    $("their-next").innerHTML = a.theirNextPicks.map((r) =>
      pickable(r, `<b>${pct(r.score)}</b> <span class="muted">${esc(r.player)}</span>`)).join("");
    $("bans").innerHTML = a.banSuggestions.map((r) => pickable(r, "")).join("");
    $("picks").innerHTML = a.pickSuggestions.map((r) =>
      pickable(r, `<span class="muted">${esc(r.player)}</span>`)).join("");
    const lo = a.laneOptions || [];
    $("lane-box").hidden = lo.length === 0;
    $("lane-options").innerHTML = lo.map((o) => {
      const ids = o.enemies.map((n) => (HEROES.find((x) => x.name === n) || {}).id).filter(Boolean);
      const href = `/counters?h=${encodeURIComponent(o.enemies[0])}&h2=${encodeURIComponent(o.enemies[1] || "")}&pos=${o.position}`;
      const chips = o.heroes.map((r) => `<button class="hero-pick" data-id="${r.heroId}" title="${esc(r.reasons.join(" · "))}">${icon(r.heroId)}${esc(r.name)}</button>
        <span class="reason">${esc(r.reasons.find((x) => x.startsWith("линия")) || r.reasons[0] || "")}</span>`).join(" ");
      return `<li><span class="who"><b>${esc(o.player)}</b> (${o.position}) vs ${ids.map((id) => icon(id)).join("")}
        <a href="${href}" target="_blank" rel="noopener" title="Все контрпики к ${esc(o.enemies.join(" + "))}">↗</a></span> ${chips}</li>`;
    }).join("");
    $("their-likely-bans").innerHTML = a.theirLikelyBans.map((r) =>
      pickable(r, `<b>${pct(r.score)}</b>`)).join("") || '<li class="muted">—</li>';
    $("their-bans-left").textContent = a.theirBansLeft ? `осталось банов: ${a.theirBansLeft}` : "";
    renderSummary(a);
    renderBoard();
  }

  function renderSummary(a) {
    if (!a.finished) return;
    const heroes = (names) => names.map((n) => { const h = HEROES.find((x) => x.name === n); return h ? icon(h.id) + esc(n) : esc(n); }).join(", ");
    $("lanes").innerHTML = "<tr><th>Линия</th><th>Мы</th><th>Они</th><th>Преим.</th><th></th></tr>" + a.lanes.map((l) =>
      `<tr><td>${esc(l.name)}</td><td>${heroes(l.ours)}</td><td>${heroes(l.theirs)}</td>
       <td class="num ${l.advantage > 0.005 ? "strong" : l.advantage < -0.005 ? "weak" : ""}">${(l.advantage >= 0 ? "+" : "") + (100 * l.advantage).toFixed(1)}%</td>
       <td class="small muted">${esc(l.note || "")}</td></tr>`).join("");
    $("threats").innerHTML = a.theirThreats.slice(0, 3).map((t) =>
      `<li>${icon(t.heroId)}<b>${esc(t.name)}</b> <span class="muted">${esc(t.player)}</span> угроза ${t.score.toFixed(2)}</li>`).join("");
  }
  $("copy").onclick = async () => {
    const a = advice;
    if (!a || !a.finished) return;
    const team = (list) => list.map((x) => `${x.name} (${x.player})`).join(", ");
    const text = [
      `Драфт против ${$("opponent").selectedOptions[0].textContent}`,
      `Мы: ${team(a.ourAssignment)}`,
      `Они: ${team(a.theirAssignment)}`,
      ...a.lanes.map((l) => `${l.name}: ${l.ours.join(" + ")} vs ${l.theirs.join(" + ")} (${(l.advantage >= 0 ? "+" : "") + (100 * l.advantage).toFixed(1)}%)${l.note ? ", " + l.note : ""}`),
      `Опасные у них: ${a.theirThreats.slice(0, 3).map((t) => `${t.name} (${t.player})`).join(", ")}`,
    ].join("\n");
    try {
      await navigator.clipboard.writeText(text);
      $("copied").textContent = "скопировано";
    } catch (e) {
      $("copied").textContent = "не удалось скопировать";
    }
  };

  // ------------------------------------------------------------ карточка игрока (боковая панель)
  async function openDrawer(accountId) {
    $("drawer").hidden = false;
    $("drawer-body").innerHTML = '<p class="muted">Загрузка…</p>';
    try {
      const res = await fetch(`/api/player/${accountId}`);
      if (!res.ok) throw new Error(res.status);
      const p = await res.json();
      const f = p.form;
      const ratio = (w, g) => g ? Math.round(100 * w / g) + "%" : "—";
      $("drawer-body").innerHTML = `
        <h3>${esc(p.nick)} <span class="muted small">поз. ${p.pos} · ${esc(p.team)}</span></h3>
        <p>${rankIcon(p.rankTier, "")} ${profiles(p.accountId)} <a href="/player/${p.accountId}" target="_blank">страница игрока ↗</a></p>
        ${p.note ? `<p class="small">${esc(p.note)}</p>` : ""}
        <h4>Чем играет сейчас <span class="hint">без Турбо; 🔥 новый · ★ сигнатурка · 👥 с командой</span></h4>
        <p class="small muted">14 дней: ${f.games14} игр · 2 месяца: ${f.games60} (с командой ${f.team60}) · Турбо: ${f.turbo60}</p>
        <table class="compact"><tr><th>Герой</th><th>14д</th><th>2 мес</th><th>WR</th><th>Турбо</th><th>С командой</th></tr>
        ${f.heroes.slice(0, 10).map((h) => `<tr><td>${icon(h.heroId)}${esc(h.name)} ${h.fresh ? "🔥" : ""}${h.signature ? "★" : ""}${h.team60 ? "👥" : ""}</td>
          <td class="num">${h.games14 || ""}</td><td class="num">${h.games60 || ""}</td><td class="num">${ratio(h.wins60, h.games60)}</td>
          <td class="num">${h.turbo60 || ""}</td><td class="small">${h.team60 ? h.team60 + " · " + esc(h.mates.join(", ")) : ""}</td></tr>`).join("")}
        </table>
        <h4>Всегда может достать <span class="hint">умение по всей истории</span></h4>
        <table class="compact"><tr><th>Герой</th><th>Умение</th><th>Игр</th><th>За год</th><th>Ранг на герое</th></tr>
        ${p.best.map((h) => `<tr><td>${icon(h.id)}${esc(h.name)}</td><td class="num">${h.skill}</td><td class="num">${h.games}</td>
          <td class="num">${h.yearGames}</td><td class="small">${esc(h.top)}</td></tr>`).join("")}
        </table>
        <h4>Последние матчи</h4>
        <table class="compact"><tr><th>Дата</th><th>Герой</th><th></th><th>K/D/A</th><th>Пати</th><th>Свои</th></tr>
        ${f.matches.slice(0, 12).map((m) => `<tr><td class="small">${m.date}</td><td>${icon(m.heroId)}${esc(m.hero)}${m.turbo ? ' <span class="muted small">turbo</span>' : ""}</td>
          <td class="${m.win ? "strong" : "weak"}">${m.win ? "W" : "L"}</td><td class="small">${m.kda}</td>
          <td class="num">${m.partySize > 1 ? m.partySize : ""}</td><td class="small">${esc(m.mates.join(", "))}</td></tr>`).join("")}
        </table>`;
    } catch (e) {
      $("drawer-body").innerHTML = `<p class="weak">Не удалось загрузить игрока: ${esc(e.message)}</p>`;
    }
  }
  function closeDrawer() { $("drawer").hidden = true; }
  $("drawer-close").onclick = closeDrawer;

  root.addEventListener("click", (e) => {
    const nick = e.target.closest(".nick[data-account]");
    if (nick) { openDrawer(nick.dataset.account); return; }
    const b = e.target.closest(".hero-pick, #grid .hero-tile");
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
        body: JSON.stringify({ opponent: state.opponent, weFirst: state.weFirst, heroes: state.heroes, assign: state.assign }),
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
    `<div class="attr"><div class="small muted">${title}</div><div class="tiles">${HEROES.filter((h) => h.attr === attr)
      .map((h) => `<button class="hero-tile" data-id="${h.id}" title="${esc(h.name)}">${heroPortrait(h.id, h.name)}<span>${esc(h.name)}</span></button>`).join("")}</div></div>`).join("");

  $("opponent").innerHTML = OPPONENTS.map((o) => `<option value="${esc(o.href)}">${esc(o.label)}</option>`).join("");
  $("opponent").value = state.opponent;
  $("opponent").onchange = (e) => {
    state.opponent = e.target.value;
    state.heroes = [];
    state.assign = {};
    state.weFirst = true;
    editIndex = null;
    advice = null;
    load();
    $("first").value = state.weFirst ? "us" : "them";
    afterChange();
  };
  $("first").onchange = (e) => { state.weFirst = e.target.value === "us"; afterChange(); };

  load();
  // Драфт из адреса: /draft?opponent=X#first=us&heroes=23,22,0,42&assign=42:kren (0 — пропущенный ход)
  const hash = new URLSearchParams(location.hash.slice(1));
  if (hash.has("heroes")) {
    state.heroes = hash.get("heroes").split(",").filter((x) => x !== "").map(Number).slice(0, ORDER.length);
    state.weFirst = hash.get("first") !== "them";
    state.assign = {};
    (hash.get("assign") || "").split(",").filter(Boolean).forEach((pair) => {
      const [h, p] = pair.split(":");
      if (h && p) state.assign[h] = decodeURIComponent(p);
    });
    save();
  }
  $("first").value = state.weFirst ? "us" : "them";
  renderSearch();
  renderBoard();
  requestAdvice();
})();
