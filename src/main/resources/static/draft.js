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
    const taken = used();
    if (taken.has(heroId) && state.heroes[target] !== heroId) return;
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
      `<li class="${i === selected ? "sel" : ""}" data-id="${h.id}">${esc(h.name)}${POOL[h.id] ? ` <span class="muted small">наш: ${esc(POOL[h.id].join(", "))}</span>` : ""}</li>`).join("");
    const q = $("search").value.trim();
    document.querySelectorAll("#grid .hero").forEach((b) => {
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

  // ------------------------------------------------------------ отрисовка
  const pct = (x) => (100 * x).toFixed(x < 0.1 ? 1 : 0) + "%";
  const heroChip = (id, cls = "") => `<span class="chip ${cls}">${esc(name(id))}</span>`;

  function renderRibbon() {
    const step = state.heroes.length;
    $("ribbon").innerHTML = ORDER.map((s, i) => {
      const h = state.heroes[i];
      const cls = ["slot", isOurs(i) ? "ours" : "theirs", s.pick ? "pick" : "ban"];
      if (i === step && editIndex === null) cls.push("current");
      if (i === editIndex) cls.push("editing");
      if (i > step) cls.push("future");
      const label = h > 0 ? esc(name(h)) : h === 0 ? "пропуск" : "";
      return `<button class="${cls.join(" ")}" data-i="${i}" ${i >= step ? "disabled" : ""}
        title="Ход ${i + 1}: ${s.pick ? "пик" : "бан"} ${isOurs(i) ? "наш" : "их"}">
        <span class="kind">${i + 1} ${s.pick ? "P" : "b"} ${isOurs(i) ? "мы" : "они"}</span><span class="who">${label}</span></button>`;
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

  /** Строка «герой → игрок»: клик открывает выбор игрока (капитан знает лучше статистики). */
  function assignLine(heroId, a, roster) {
    const fixed = a && a.fixed;
    const prob = a && !fixed ? ` <span class="muted">${pct(a.probability)}${a.alternative ? ` (${esc(a.alternative)} ${pct(a.alternativeProbability)})` : ""}</span>` : "";
    const options = roster.map((r) =>
      `<option value="${esc(r.player)}" ${fixed && a.player === r.player ? "selected" : ""}>${esc(r.player)} (поз. ${r.position})</option>`).join("");
    return `<div class="assign"><b>${esc(name(heroId))}</b> → ${a ? `<span class="${fixed ? "fixed" : ""}">${esc(a.player)}</span>` : "…"}${
      fixed ? ' <span class="muted small">вручную</span>' : prob}
      <select class="assign-select" data-hero="${heroId}" title="Кто на этом герое">
        <option value="">авто</option>${options}</select></div>`;
  }
  root.addEventListener("change", (e) => {
    const sel = e.target.closest(".assign-select");
    if (!sel) return;
    const h = Number(sel.dataset.hero);
    // один игрок — один герой: снимаем его с другого героя
    Object.entries(state.assign).forEach(([k, v]) => { if (v === sel.value && Number(k) !== h) delete state.assign[k]; });
    if (sel.value) state.assign[h] = sel.value; else delete state.assign[h];
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
    $("our-bans").innerHTML = parts.ourBans.map((h) => heroChip(h, "ban")).join("") || "—";
    $("their-bans").innerHTML = parts.theirBans.map((h) => heroChip(h, "ban")).join("") || "—";
    const ourA = advice?.ourAssignment ?? [], theirA = advice?.theirAssignment ?? [];
    $("our-picks").innerHTML = parts.ourPicks.map((h) =>
      assignLine(h, ourA.find((x) => x.heroId === h), advice?.ourRoster ?? [])).join("") || "—";
    $("their-picks").innerHTML = parts.theirPicks.map((h) =>
      assignLine(h, theirA.find((x) => x.heroId === h), advice?.theirRoster ?? [])).join("") || "—";
    // кто ещё без героя — от этого зависят прогноз их пиков и баны
    const ourRemaining = advice?.ourRemaining ?? [];
    $("our-remaining").innerHTML = ourRemaining.length && !advice.finished
      ? `<div class="remaining small">Ещё без героя: ${ourRemaining.map((r) =>
          `<span class="chip">${esc(r.player)} <span class="muted">поз. ${r.position} · ${pct(r.probability)}</span></span>`).join("")}</div>`
      : "";
    const outlook = advice?.theirPlayers ?? [];
    $("their-outlook").innerHTML = outlook.length && !advice.finished
      ? `<div class="small muted outlook-title">Ещё без героя — их вероятные герои:</div>` + outlook.map((p) =>
          `<div class="outlook"><b>${esc(p.player)}</b> <span class="muted">поз. ${p.position}${p.free < 0.95 ? ` · свободен ${pct(p.free)}` : ""}</span>: ${
            p.heroes.map((h) => `<button class="hero-pick link" data-id="${h.heroId}">${esc(h.name)}</button> ${pct(h.probability)}`).join(", ")}</div>`).join("")
      : "";
    const taken = used();
    document.querySelectorAll("#grid .hero").forEach((b) => {
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
    $("advice").hidden = finished;
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
      $(p).style.order = p === focus ? -1 : 0;
    });
    // на их ходу наверху то, что они сделают сейчас: пик или бан
    if (!ours) {
      $("panel-their").insertBefore(s.pick ? $("their-next-box") : $("their-bans-box"), $("panel-their").firstChild);
    }
  }

  const reasons = (r) => (r.reasons || []).map((x) => `<span class="reason">${esc(x)}</span>`).join(" ");
  const pickable = (r, extra) => `<li><button class="hero-pick" data-id="${r.heroId}">${esc(r.name)}</button> ${extra} ${reasons(r)}</li>`;

  function renderAdvice(a) {
    advice = a;
    $("their-next").innerHTML = a.theirNextPicks.map((r) =>
      pickable(r, `<b>${pct(r.score)}</b> <span class="muted">${esc(r.player)}</span>`)).join("");
    $("bans").innerHTML = a.banSuggestions.map((r) => pickable(r, "")).join("");
    $("picks").innerHTML = a.pickSuggestions.map((r) =>
      pickable(r, `<span class="muted">${esc(r.player)}</span>`)).join("");
    $("their-likely-bans").innerHTML = a.theirLikelyBans.map((r) =>
      pickable(r, `<b>${pct(r.score)}</b>`)).join("") || '<li class="muted">—</li>';
    $("their-bans-left").textContent = a.theirBansLeft ? `осталось банов: ${a.theirBansLeft}` : "";
    renderSummary(a);
    renderBoard();
  }

  function renderSummary(a) {
    if (!a.finished) return;
    $("lanes").innerHTML = "<tr><th>Линия</th><th>Мы</th><th>Они</th><th>Преим.</th><th></th></tr>" + a.lanes.map((l) =>
      `<tr><td>${esc(l.name)}</td><td>${esc(l.ours.join(", "))}</td><td>${esc(l.theirs.join(", "))}</td>
       <td class="num ${l.advantage > 0.005 ? "strong" : l.advantage < -0.005 ? "weak" : ""}">${(l.advantage >= 0 ? "+" : "") + (100 * l.advantage).toFixed(1)}%</td>
       <td class="small muted">${esc(l.note || "")}</td></tr>`).join("");
    $("threats").innerHTML = a.theirThreats.slice(0, 3).map((t) =>
      `<li><b>${esc(t.name)}</b> <span class="muted">${esc(t.player)}</span> угроза ${t.score.toFixed(2)}</li>`).join("");
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
    `<div class="attr"><div class="small muted">${title}</div>${HEROES.filter((h) => h.attr === attr)
      .map((h) => `<button class="hero" data-id="${h.id}">${esc(h.name)}</button>`).join("")}</div>`).join("");

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
  renderBoard();
  requestAdvice();
})();
