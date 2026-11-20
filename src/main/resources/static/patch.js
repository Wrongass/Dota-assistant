// Страница патча (/patch): разделы-спойлеры, карточки героев и предметов. Данные — /api/patch (patchcard.js).
(() => {
  const root = document.getElementById("patch-root");
  if (!root) return;
  const { esc, pct, pp } = Patch;
  const ICON = { magic: "✨", health: "❤", tormentor: "🛡", gold: "💰", neutral: "🎒", items: "🗡", potency: "⚙", map: "🗺", spell: "🔮" };
  const TIER_LABEL = { key: "критично", notable: "заметно", minor: "мелочь" };

  Patch.load().then((d) => {
    if (!d) { root.innerHTML = '<div class="panel">Не удалось загрузить разбор патча.</div>'; return; }
    render(d);
    openFromHash();
    window.addEventListener("hashchange", openFromHash);
  });

  function section(id, title, html, open = false, sub = "") {
    return `<details class="panel patch-sec" id="${id}"${open ? " open" : ""}><summary><b>${title}</b>${sub ? ` <span class="muted small">${sub}</span>` : ""}</summary>${html}</details>`;
  }

  // ------------------------------------------------------------ герои
  const relevance = (list) => list.reduce((s, p) => s + p.tournament * 50 + p.games, 0);

  function heroRow(h, who) {
    const owners = who === "ours" ? Patch.owners(h.ours, "у нас", "ours") : who === "theirs" ? Patch.owners(h.opponent, Patch.data.opponent, "theirs")
      : `${Patch.owners(h.ours, "у нас", "ours")} ${Patch.owners(h.opponent, Patch.data.opponent, "theirs")}`;
    return `<details class="hero-row" id="${who === "all" ? "hero-" + h.id : who + "-" + h.id}" data-hero="${h.id}" data-players="${esc([...h.ours, ...h.opponent].map((p) => p.nick).join("|"))}" data-name="${esc((h.name + " " + h.en).toLowerCase())}">
      <summary>${heroPortrait(h.id, h.en)}<span class="hr-main"><span class="hr-name">${esc(h.name)}</span> ${Patch.chip(h)}
        <span class="hr-stats small">${Patch.statsLine(h)}</span><span class="small">${owners}</span>
        ${h.review?.comment ? `<span class="hr-comment small">${esc(firstSentence(h.review.comment))}</span>` : ""}</span></summary>
      <div class="hr-body"></div></details>`;
  }
  const firstSentence = (s) => { const m = s.match(/^.+?[.!?](\s|$)/); return m ? m[0] : s; };

  // тело карточки рисуется при раскрытии: героев много
  root.addEventListener("toggle", (e) => {
    const row = e.target.closest?.(".hero-row");
    if (!row || !row.open) return;
    const body = row.querySelector(".hr-body");
    if (!body.innerHTML) body.innerHTML = Patch.heroCard(Patch.byId.get(Number(row.dataset.hero)), { compact: true });
  }, true);

  function playerFilter(id, heroes, key) {
    const nicks = [...new Set(heroes.flatMap((h) => h[key].map((p) => p.nick)))];
    return `<div class="chips" data-filter="${id}"><button type="button" class="chip on" data-nick="">все</button>${nicks.map((n) => `<button type="button" class="chip" data-nick="${esc(n)}">${esc(n)}</button>`).join("")}</div>`;
  }
  root.addEventListener("click", (e) => {
    const b = e.target.closest(".chips[data-filter] .chip");
    if (!b) return;
    const box = b.closest(".chips");
    box.querySelectorAll(".chip").forEach((x) => x.classList.toggle("on", x === b));
    document.querySelectorAll(`#${box.dataset.filter} .hero-row`).forEach((r) => {
      r.hidden = !!b.dataset.nick && !r.dataset.players.split("|").includes(b.dataset.nick);
    });
  });

  function heroesSection(id, title, plan, heroes, key, who, sub) {
    const list = heroes.filter((h) => h[key].length).sort((a, b) => relevance(b[key]) - relevance(a[key]));
    return section(id, title, `${plan ? `<p class="plan">${esc(plan)}</p>` : ""}${playerFilter(id, list, key)}
      <div class="hero-list">${list.map((h) => heroRow(h, who)).join("")}</div>`, true, sub);
  }

  // ------------------------------------------------------------ сильнее / слабее
  function movers(d) {
    const rows = d.heroes.filter((h) => h.stats?.wr_prev != null);
    const v = (h) => Patch.verdict(h).cls;
    const up = rows.filter((h) => v(h) === "up" || (!h.review?.verdict && h.stats.delta >= 0.025 && h.stats.z >= 3)).sort((a, b) => b.stats.delta - a.stats.delta);
    const down = rows.filter((h) => v(h) === "down" || (!h.review?.verdict && h.stats.delta <= -0.025 && h.stats.z <= -3)).sort((a, b) => a.stats.delta - b.stats.delta);
    const rework = d.heroes.filter((h) => v(h) === "rework");
    const table = (list) => `<table class="movers"><thead><tr><th>Герой</th><th>Оценка</th><th>7.41 → 7.42</th><th>Изм.</th><th>Игр</th><th>Наши / их</th></tr></thead><tbody>
      ${list.map((h) => `<tr><td><a href="#hero-${h.id}" class="hero-link" data-hero="${h.id}">${heroIcon(h.id, h.en)} ${esc(h.name)}</a></td><td>${Patch.chip(h)}</td>
        <td>${pct(h.stats.wr_prev)} → ${pct(h.stats.wr)}</td><td class="delta ${h.stats.delta > 0 ? "up" : "down"}">${pp(h.stats.delta)}</td>
        <td class="muted">${h.stats.games}</td><td class="small">${[...h.ours.map((p) => `<span class="owners ours">${esc(p.nick)}</span>`), ...h.opponent.map((p) => `<span class="owners theirs">${esc(p.nick)}</span>`)].join(" ")}</td></tr>`).join("")}</tbody></table>`;
    return `<p class="muted small">Оценка — ручная (по совокупности изменений героя, предметов и общих механик) или автоматическая, если героя не разбирали. В списки попадают герои с ручной оценкой «сильнее/слабее» и без неё — с изменением винрейта больше 2,5 п. п. и уверенностью z ≥ 3. Это первые часы патча: игроки ещё учатся, и шум большой.</p>
      <div class="two-col"><div><h3>▲ Сильнее</h3>${table(up)}</div><div><h3>▼ Слабее</h3>${table(down)}</div></div>
      <h3>◆ Переделаны</h3><p>${rework.map((h) => `<a href="#hero-${h.id}" class="hero-link" data-hero="${h.id}">${heroIcon(h.id, h.en)} ${esc(h.name)}</a>`).join(", ")}</p>`;
  }
  root.addEventListener("click", (e) => {
    const a = e.target.closest("a.hero-link[data-hero]");
    if (!a) return;
    e.preventDefault();
    openHero(Number(a.dataset.hero));
  });

  // ------------------------------------------------------------ предметы
  function itemCard(it, d) {
    const r = it.review || {};
    const status = { new: '<span class="badge new">новый</span>', removed: '<span class="badge removed">удалён</span>', changed: "" }[it.status];
    const heroes = (r.heroes || []).map((id) => Patch.byId.get(id)).filter(Boolean)
      .map((h) => `<a href="#hero-${h.id}" class="hero-link" data-hero="${h.id}" title="${esc(h.name)}">${heroIcon(h.id, h.en)}</a>`).join("");
    const verdict = r.verdict ? `<span class="vchip ${r.verdict}">${{ up: "▲ сильный", down: "▼ слабее", same: "• по ситуации" }[r.verdict]}</span>` : "";
    return `<div class="item-card"><div class="ic-head"><img class="it-ico" src="${esc(it.item.icon || "")}" alt="" loading="lazy" onerror="this.style.visibility='hidden'">
      <b>${esc(it.item.name)}</b> ${status} ${verdict}</div>
      ${r.comment ? `<div class="pc-comment">${esc(r.comment)}</div>` : ""}
      ${heroes ? `<div class="small">Кому: ${heroes}</div>` : ""}
      ${Patch.notesHtml(it.notes, it.status !== "changed")}</div>`;
  }
  function itemsSection(d) {
    const list = d.items.filter((x) => !x.general);
    const fresh = list.filter((x) => x.status === "new"), gone = list.filter((x) => x.status === "removed");
    const changed = list.filter((x) => x.status === "changed");
    const important = changed.filter((x) => x.review?.comment || x.tier === "key").sort((a, b) => (b.review?.comment ? 1 : 0) - (a.review?.comment ? 1 : 0));
    const rest = changed.filter((x) => !important.includes(x));
    return `<h3>Новые предметы</h3><div class="item-grid">${fresh.map((x) => itemCard(x, d)).join("")}</div>
      <h3>Удалены</h3><div class="item-grid">${gone.map((x) => itemCard(x, d)).join("")}</div>
      <h3>Главные изменения</h3><div class="item-grid">${important.map((x) => itemCard(x, d)).join("")}</div>
      <details><summary class="muted">Остальные изменения предметов (${rest.length})</summary><div class="item-grid">${rest.map((x) => itemCard(x, d)).join("")}</div></details>`;
  }
  function neutralSection(d) {
    const general = d.neutral.filter((x) => x.general);
    const items = d.neutral.filter((x) => !x.general);
    const bySection = {};
    items.forEach((x) => (bySection[x.section] ||= []).push(x));
    return `${general.map((g) => `<h3>${esc(g.section)}</h3>${Patch.notesHtml(g.notes, true)}`).join("")}
      ${Object.entries(bySection).map(([s, list]) => `<details><summary><b>${esc(s)}</b> <span class="muted small">${list.length}</span></summary>
        <div class="item-grid">${list.map((x) => itemCard(x, d)).join("")}</div></details>`).join("")}`;
  }

  // ------------------------------------------------------------ механики
  function generalSection(d) {
    return d.general.map((sec) => {
      const terrain = /terrain/i.test(sec.title_en || "");
      const body = sec.groups.map((g) => `${g.title ? `<h4>${esc(g.title)}</h4>` : ""}${Patch.notesHtml(g.notes, true)}`).join("");
      return terrain
        ? `<details><summary><b>${esc(sec.title)}</b> <span class="muted small">изменения карты лучше смотреть глазами: в игре или на <a href="${esc(d.source)}" target="_blank" rel="noopener">официальной странице</a></span></summary>${body}</details>`
        : `<h3>${esc(sec.title)}</h3>${body}`;
    }).join("");
  }

  // ------------------------------------------------------------ сборка
  function render(d) {
    const r = d.review || {};
    const s = d.stats || {};
    const cur = s.sample || {}, prev = s.sample_prev || {};
    const ago = cur.patch_start && cur.fetched_at ? (Date.parse(cur.fetched_at) - Date.parse(cur.patch_start)) / 3600e3 : null;
    // первые три дня патча: винрейты ещё плавают, вердикты по статистике — гипотезы
    const early = ago != null && ago < 72 ? `статистика ${esc(d.version)} за первые ${ago < 24 ? Math.max(1, Math.round(ago)) + " ч" : Math.round(ago / 24) + " дн."} патча. Стрелки по ней — гипотезы, а не пик-лист.` : "";
    const when = (iso) => (iso ? new Date(iso).toLocaleString("ru", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit", timeZone: "UTC" }) + " UTC" : "—");
    const header = `<div class="panel patch-head">
      <p>${r.headline ? esc(r.headline) : ""}</p>
      <p class="small muted">Опубликован ${esc(d.released)}. Источник — <a href="${esc(d.source)}" target="_blank" rel="noopener">официальный список изменений</a> (фид dota2.com).
        Разбор от ${esc(r.checked || d.built)}. Статистика: ${s.matches ? `${s.matches.toLocaleString("ru")} матчей ${esc(d.version)}` : "нет"} против ${s.matches_prev ? s.matches_prev.toLocaleString("ru") : "—"} в ${esc(d.previous || "")}, ранг Legend и выше (OpenDota).</p>
      ${r.stats_note ? `<p class="small">${esc(r.stats_note)}</p>` : ""}
      ${early ? `<p class="small recheck"><b>Предварительная выборка:</b> ${esc(early)}</p>` : ""}
      ${r.recheck ? `<p class="small recheck"><b>Обновление:</b> ${esc(r.recheck)}</p>` : ""}
      <p class="small legend">${Patch.chip({ review: { verdict: "up" } })} ${Patch.chip({ review: { verdict: "down" } })} ${Patch.chip({ review: { verdict: "rework" } })} ${Patch.chip({ review: { verdict: "same" } })} ${Patch.chip({ review: { verdict: "mixed" } })}
        · строки: <span class="sgn up">▲</span> усиление, <span class="sgn down">▼</span> ослабление, <span class="sgn">◆</span> нейтрально, <span class="tier-tag key">критично</span> — сильно влияет на игру. Мелочи свёрнуты, косметика скрыта.</p>
      <nav class="patch-nav small">${[["main", "Главное"], ["questions", "Вопросы"], ["ours", "Наши герои"], ["theirs", "Соперник"], ["movers", "Сильнее / слабее"], ["items", "Предметы"], ["neutral", "Нейтралки"], ["mechanics", "Механики и карта"], ["all", "Все герои"], ["method", "Как считалось"]]
        .map(([id, t]) => `<a href="#${id}" data-sec="${id}">${t}</a>`).join("")}</nav></div>`;

    const main = `<div class="sum-grid">${(r.summary || []).map((x) => `<div class="sum-card tier-${x.tier}"><div class="sum-ico">${ICON[x.icon] || "•"}</div>
      <div><b>${esc(x.title)}</b> <span class="tier-tag ${x.tier}">${TIER_LABEL[x.tier] || ""}</span><p>${esc(x.text)}</p>${x.for_us ? `<p class="for-us"><b>Для нас:</b> ${esc(x.for_us)}</p>` : ""}</div></div>`).join("")}</div>`;

    const questions = (r.questions || []).map((q) => {
      const h = Patch.byId.get(q.hero);
      return `<div class="question">${h ? heroPortrait(h.id, h.en) : ""}<div><h3>${esc(q.title)}</h3>${h ? `<div class="small">${Patch.chip(h)} ${Patch.statsLine(h)}</div>` : ""}
        <p>${esc(q.answer)}</p>${h ? `<details><summary class="muted small">Все изменения ${esc(h.name)}</summary>${Patch.heroCard(h, { compact: true, openMinor: true, noQuestion: true })}</details>` : ""}</div></div>`;
    }).join("");

    const all = `<input type="search" id="hero-filter" placeholder="Найти героя: имя по-русски или по-английски" autocomplete="off">
      <div class="chips" data-verdict><button type="button" class="chip on" data-v="">все</button><button type="button" class="chip" data-v="up">▲ сильнее</button><button type="button" class="chip" data-v="down">▼ слабее</button><button type="button" class="chip" data-v="rework">◆ переделаны</button></div>
      <div class="small muted" id="all-count"></div>
      <div class="hero-list" id="all-list">${d.heroes.map((h) => heroRow(h, "all")).join("")}</div>
      <div class="empty-state" id="all-empty" hidden>Ничего не нашлось <span id="all-empty-why"></span>. <button type="button" class="chip" id="all-reset">Сбросить поиск и фильтр</button></div>`;

    const sample = (x, n) => x.patch ? `<li><b>${esc(x.patch)}</b>: ${n ? n.toLocaleString("ru") : "—"} публичных матчей OpenDota со средним рангом Legend и выше
        (avg_rank_tier ≥ ${esc(x.min_avg_rank_tier ?? 50)}), из последнего окна в ${(x.match_id_window || 0).toLocaleString("ru")} match_id перед выгрузкой
        и только после начала патча (${when(x.patch_start)}). Выгружено ${when(x.fetched_at)}.</li>` : "";
    const method = `<h3>Что входит</h3><ul>
      <li>Изменения — из официального фида dota2.com (структура «герой → способность → строка», русский текст Valve; часть строк Valve не перевела).
        Здесь только игровой баланс: герои, предметы, нейтралки, механики, карта. Горячие клавиши, интерфейс и исправления ошибок не разбираются, их смотрите <a href="${esc(d.source)}" target="_blank" rel="noopener">в официальном списке</a>.</li>
      <li>Важность строки считается по тексту: переработки и новые способности — критично; изменения на 40% и больше — тоже критично; от 12% — заметно; от 3% — мелочь (свёрнута); меньше 3%, базовый урон ±1–5 и визуальные правки — косметика (скрыта, только счётчик). Строки с пометкой «снижение силы заклинаний» не бывают критичными: это общая правка для всех. Для части строк важность поправлена вручную.</li>
      <li>Направление (усиление или ослабление) — по словам «увеличен/уменьшен» и по тому, что для этого параметра лучше: для перезарядки и стоимости меньше — лучше.</li></ul>
      <h3>Вердикт героя</h3><ul>
      <li>Ручной — для наших героев, героев соперника и самых заметных по статистике: изменения героя, общие механики и предметы вместе.</li>
      <li>Автоматический (помечен «авто») — сумма строк: критично ±3, заметно ±2, мелочь ±1. Сумма ≥ 4 — сильнее, ≤ −4 — слабее, иначе без больших изменений.
        Если по строкам изменений мало, а статистика уверенно сдвинулась (|z| ≥ 4), берётся знак статистики. Если строки и уверенная статистика спорят — «по-разному».</li></ul>
      <h3>Статистика</h3><ul>
      ${sample(cur, s.matches)}${sample(prev, s.matches_prev)}
      <li>Винрейт героя — его победы / его игры в этой выборке. Сдвиг — разница винрейтов ${esc(d.version)} и ${esc(d.previous || "")}.
        z — сдвиг, делённый на стандартную ошибку разницы: √(p₁(1−p₁)/n₁ + p₀(1−p₀)/n₀). «Уверенно» — |z| ≥ 4, «похоже» — |z| ≥ 2, иначе шум.
        Число игр обеих выборок видно в подсказке у винрейта.</li>
      <li>«Уверенно» значит только, что сдвиг не случайный разброс. Причиной может быть не патч, а то, что игроки ещё пробуют героя и новые предметы. Пабы Legend+ — не турнир: для драфта это повод проверить героя, а не готовый пик.</li></ul>
      ${r.recheck_list?.length ? `<h3>${r.recheck_done ? "Что проверили " + esc(r.recheck_done) : "Что проверим при обновлении"}</h3><ul>${r.recheck_list.map((x) => `<li>${esc(x)}</li>`).join("")}</ul>` : ""}`;

    root.innerHTML = header
      + section("main", "Главное в патче", main, true)
      + (questions ? section("questions", "Вопросы", questions, true) : "")
      + heroesSection("ours", "Наши герои", r.our_plan, d.heroes, "ours", "ours", "по игрокам: турнир и частые герои в пабах")
      + (d.opponent ? heroesSection("theirs", `Герои соперника: ${esc(d.opponent)}`, r.opponent_plan, d.heroes, "opponent", "theirs", "следующий соперник из конфига") : "")
      + section("movers", "Кто стал сильнее и слабее", movers(d))
      + section("items", "Предметы", itemsSection(d))
      + section("neutral", "Нейтральные предметы", neutralSection(d))
      + section("mechanics", "Механики, объекты и карта", generalSection(d)
          + `<details><summary><b>Нейтральные крипы</b></summary>${d.creeps.map((c) => `<h4>${esc(c.name)}</h4>${Patch.notesHtml(c.notes, true)}`).join("")}</details>`)
      + section("all", "Все герои", all, false, `${d.heroes.length} героев`)
      + section("method", "Как считалось", method);

    const filter = document.getElementById("hero-filter");
    // тот же поиск, что в драфте: имя, начало, инициалы (od, wk), сленг («лина», «вк»), раскладка
    const finder = window.heroSearch ? heroSearch(d.heroes.map((h) => ({ id: h.id, name: h.en }))) : null;
    const applyFilter = () => {
      const q = filter.value.trim().toLowerCase();
      const hits = q && finder ? new Set(finder.find(q).map((h) => h.id)) : null;
      const v = document.querySelector("[data-verdict] .chip.on")?.dataset.v || "";
      document.querySelectorAll("#all-list .hero-row").forEach((row) => {
        const id = Number(row.dataset.hero);
        const match = !q || row.dataset.name.includes(q) || (hits && hits.has(id));
        row.hidden = !match || (v && Patch.verdict(Patch.byId.get(id)).cls !== v);
      });
      const shown = root.querySelectorAll("#all-list .hero-row:not([hidden])").length;
      document.getElementById("all-count").textContent = q || v ? `Показано ${shown} из ${d.heroes.length}` : "";
      document.getElementById("all-empty").hidden = shown > 0;
      const vLabel = document.querySelector("[data-verdict] .chip.on")?.textContent.trim();
      document.getElementById("all-empty-why").textContent = [q && `по запросу «${filter.value.trim()}»`, v && `с фильтром «${vLabel}»`].filter(Boolean).join(" ");
    };
    document.getElementById("all-reset").addEventListener("click", () => {
      filter.value = "";
      root.querySelectorAll("[data-verdict] .chip").forEach((x) => x.classList.toggle("on", !x.dataset.v));
      applyFilter();
      filter.focus();
    });
    filter.addEventListener("input", applyFilter);
    root.querySelector("[data-verdict]").addEventListener("click", (e) => {
      const b = e.target.closest(".chip");
      if (!b) return;
      root.querySelectorAll("[data-verdict] .chip").forEach((x) => x.classList.toggle("on", x === b));
      applyFilter();
    });
    root.querySelector(".patch-nav").addEventListener("click", (e) => {
      const a = e.target.closest("a[data-sec]");
      if (!a) return;
      const sec = document.getElementById(a.dataset.sec);
      if (sec) sec.open = true;
    });
  }

  /** Открыть карточку героя в «Все герои» и прокрутить к ней. */
  function openHero(id) {
    const all = document.getElementById("all");
    const row = document.getElementById("hero-" + id);
    if (!all || !row) return;
    all.open = true;
    row.hidden = false;
    row.open = true;
    row.scrollIntoView({ behavior: "smooth", block: "start" });
    history.replaceState(null, "", "#hero-" + id);
  }
  function openFromHash() {
    const m = location.hash.match(/^#hero-(\d+)$/);
    if (m) { openHero(Number(m[1])); return; }
    const sec = location.hash && document.getElementById(location.hash.slice(1));
    if (sec && sec.tagName === "DETAILS") { sec.open = true; sec.scrollIntoView(); }
  }
})();
