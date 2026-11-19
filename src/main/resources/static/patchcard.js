// Разбор патча: загрузка /api/patch и карточка героя. Общее для страницы /patch и живого драфта.
// Данные собирает tools/patch/build.py (analysis/patch/{версия}/patch.json): строки патча с важностью и направлением,
// статистика до и после патча, ручной разбор из review.json.
window.Patch = (() => {
  let data = null, loading = null;
  const byId = new Map();
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const pct = (x, d = 1) => (x * 100).toFixed(d) + "%";
  const pp = (x) => (x > 0 ? "+" : x < 0 ? "−" : "") + Math.abs(x * 100).toFixed(1) + " п. п.";

  /** Загрузить разбор один раз; null, если патча нет. */
  function load() {
    if (!loading) {
      loading = fetch("/api/patch").then((r) => (r.ok ? r.json() : null)).catch(() => null).then((d) => {
        data = d;
        (d?.heroes || []).forEach((h) => byId.set(h.id, h));
        return d;
      });
    }
    return loading;
  }

  const TIER = { key: "критично", notable: "заметно", minor: "мелочь", trivial: "косметика" };
  const VERDICT = {
    up: { cls: "up", mark: "▲", label: "сильнее" },
    down: { cls: "down", mark: "▼", label: "слабее" },
    same: { cls: "same", mark: "•", label: "без больших изменений" },
    rework: { cls: "rework", mark: "◆", label: "переделан" },
    mixed: { cls: "same", mark: "±", label: "по-разному" },
  };

  /** Вердикт героя: ручной из review.json, иначе по сумме строк патча и статистике (помечается «авто»). */
  function verdict(h) {
    if (h.review?.verdict) return { ...VERDICT[h.review.verdict], manual: true, strength: h.review.strength || 1 };
    const z = h.stats?.z ?? 0, s = h.score;
    let v = h.rework ? "rework" : s >= 4 ? "up" : s <= -4 ? "down" : "same";
    if (v === "same" && Math.abs(z) >= 4) v = z > 0 ? "up" : "down";
    // строки патча и уверенная статистика тянут в разные стороны: честнее «по-разному», чем знак по одной из них
    else if ((v === "up" && z <= -4) || (v === "down" && z >= 4)) v = "mixed";
    return { ...VERDICT[v], manual: false, strength: v !== "mixed" && (Math.abs(s) >= 8 || Math.abs(z) >= 6) ? 2 : 1 };
  }

  function statsLine(h) {
    const s = h.stats;
    if (!s) return '<span class="muted">статистики пока нет</span>';
    if (s.wr_prev == null) return `винрейт ${pct(s.wr)} <span class="muted">(${s.games} игр)</span>`;
    const cls = s.delta >= 0.02 ? "up" : s.delta <= -0.02 ? "down" : "";
    const sure = Math.abs(s.z) >= 4 ? "уверенно" : Math.abs(s.z) >= 2 ? "похоже" : "шум";
    const prev = data?.previous || "прошлый патч", cur = data?.version || "патч";
    return `винрейт ${pct(s.wr_prev)} → <b>${pct(s.wr)}</b> <span class="delta ${cls}">${pp(s.delta)}</span>
      <span class="muted" title="${esc(`${prev}: ${s.games_prev} игр, ${cur}: ${s.games} игр. z = ${s.z}: изменение винрейта, делённое на его стандартную ошибку. «Уверенно» — |z| ≥ 4, «похоже» — |z| ≥ 2, иначе шум. Это про случайный разброс, а не про причину: часть сдвига может давать то, что игроки ещё пробуют героя.`)}">(${s.games} игр против ${s.games_prev}, ${sure})</span>`;
  }

  const ARROW = { 1: '<span class="sgn up" title="сильнее">▲</span>', "-1": '<span class="sgn down" title="слабее">▼</span>', 0: '<span class="sgn" title="нейтрально или неясно">◆</span>' };

  function noteHtml(n) {
    // пояснение Valve показываем текстом: в подсказке под ⓘ его не видно (так пропустили «золото вместо Shard» у Терзателя)
    const info = n.info ? ` <span class="pn-info">(${esc(n.info)})</span>` : "";
    const agh = n.aghanims ? `<span class="agh" title="Aghanim's ${n.aghanims === "shard" ? "Shard" : "Scepter"}">${n.aghanims === "shard" ? "◇" : "⬢"}</span> ` : "";
    return `<li class="pn tier-${n.tier} ind-${Math.min(n.indent || 1, 3)}">${ARROW[n.sign] || ARROW[0]} ${agh}${esc(n.ru)}${info}
      ${n.tier === "key" ? '<span class="tier-tag key">критично</span>' : ""}</li>`;
  }

  /** Строки патча блока: важные сразу, мелочи — под «ещё N», косметика не показывается вовсе. */
  function notesHtml(notes, open = false) {
    const shown = notes.filter((n) => n.tier === "key" || n.tier === "notable");
    const minor = notes.filter((n) => n.tier === "minor");
    const trivial = notes.length - shown.length - minor.length;
    let html = shown.length ? `<ul class="pnotes">${shown.map(noteHtml).join("")}</ul>` : "";
    if (minor.length) {
      html += shown.length || !open
        ? `<details class="minor"${open && !shown.length ? " open" : ""}><summary class="muted small">ещё ${minor.length} ${plural(minor.length, "мелкое изменение", "мелких изменения", "мелких изменений")}</summary><ul class="pnotes">${minor.map(noteHtml).join("")}</ul></details>`
        : `<ul class="pnotes">${minor.map(noteHtml).join("")}</ul>`;
    }
    if (trivial > 0) html += `<div class="muted small">и ${trivial} косметических, не показаны</div>`;
    return html;
  }
  const plural = (n, one, few, many) => (n % 10 === 1 && n % 100 !== 11 ? one : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 10 || n % 100 >= 20) ? few : many);

  function abilityHead(a) {
    if (!a) return '<div class="ab-head"><span class="ab-ico gen">★</span><b>Герой</b></div>';
    const img = a.icon ? `<img class="ab-ico" src="${esc(a.icon)}" alt="" loading="lazy" onerror="this.replaceWith(Object.assign(document.createElement('span'),{className:'ab-ico gen',textContent:'✦'}))">`
      : `<span class="ab-ico gen">${a.talents ? "✪" : "✦"}</span>`;
    return `<div class="ab-head">${img}<b>${esc(a.name)}</b>${a.en && a.en !== a.name ? ` <span class="muted small">${esc(a.en)}</span>` : ""}</div>`;
  }

  /** Кто из наших и из соперника играет героя: «Enaria (6 в турнире, 153 в пабах)». */
  function owners(list, label, cls) {
    if (!list?.length) return "";
    return `<span class="owners ${cls}">${label}: ${list.map((p) => `${esc(p.nick)}${p.tournament ? ` <span class="muted">(${p.tournament} в турнире)</span>` : p.games ? ` <span class="muted">(${p.games} в пабах)</span>` : ""}`).join(", ")}</span>`;
  }

  /** Полная карточка героя. opts.compact — без заголовка с иконкой (страница рисует его сама). */
  function heroCard(h, opts = {}) {
    const v = verdict(h);
    const head = opts.compact ? "" : `<div class="pc-head">${window.heroPortrait ? heroPortrait(h.id, h.en) : ""}
      <div><div class="pc-name">${esc(h.name)} ${chip(h)}</div><div class="small">${statsLine(h)}</div>
      <div class="small">${owners(h.ours, "у нас", "ours")} ${owners(h.opponent, data?.opponent || "соперник", "theirs")}</div></div></div>`;
    const comment = h.review?.comment ? `<div class="pc-comment"><b>Оценка:</b> ${esc(h.review.comment)}</div>`
      : `<div class="pc-comment muted small">Автоматическая оценка по строкам патча (сумма ${h.score > 0 ? "+" : ""}${h.score}: критично ±3, заметно ±2, мелочь ±1) и статистике. Ручного разбора для этого героя нет.${v.label === "по-разному" ? " Строки патча и статистика тянут в разные стороны, смотрите изменения сами." : ""}</div>`;
    // ответ на вопрос о герое (например, «стоит ли пикать OD») — в карточке тоже, в драфте его больше негде увидеть
    const q = opts.noQuestion ? null : (data?.review?.questions || []).find((x) => x.hero === h.id);
    const question = q ? `<details class="pc-question"${opts.compact ? "" : " open"}><summary><b>${esc(q.title)}</b></summary><p>${esc(q.answer)}</p></details>` : "";
    const blocks = h.blocks.map((b) => `<div class="pc-block">${abilityHead(b.ability)}${notesHtml(b.notes, opts.openMinor)}</div>`).join("");
    return `<div class="patch-card v-${v.cls}">${head}${comment}${question}${blocks}</div>`;
  }

  function chip(h) {
    const v = verdict(h);
    return `<span class="vchip ${v.cls}${v.strength >= 2 ? " strong" : ""}" title="${v.manual ? "ручная оценка" : "автоматическая оценка"}">${v.mark} ${v.label}${v.manual ? "" : " · авто"}</span>`;
  }

  // ------------------------------------------------------------ всплывающая карточка (драфт)
  let pop = null;
  function openPopup(heroId) {
    load().then((d) => {
      const h = byId.get(heroId);
      if (!pop) {
        pop = document.createElement("aside");
        pop.id = "patch-pop";
        pop.className = "patch-pop";
        document.body.appendChild(pop);
        pop.addEventListener("click", (e) => { if (e.target.closest("[data-act=close]")) pop.hidden = true; });
        document.addEventListener("keydown", (e) => { if (e.key === "Escape" && pop) pop.hidden = true; });
      }
      pop.innerHTML = `<div class="pop-bar"><b>Патч ${esc(d?.version || "")}</b><a href="/patch#hero-${heroId}" target="_blank">на странице патча ↗</a>
        <button type="button" data-act="close" title="Закрыть (Esc)">✕</button></div>
        ${h ? heroCard(h) : '<p class="muted">В этом патче герой не менялся.</p>'}`;
      pop.hidden = false;
      pop.scrollTop = 0;
    });
  }

  /** Значок изменений в углу плитки героя: ▲ сильнее, ▼ слабее, ◆ переделан, • без больших изменений. */
  function markHtml(heroId) {
    const h = byId.get(heroId);
    if (!h) return "";
    const v = verdict(h);
    return `<span class="patch-mark ${v.cls}" data-patch="${heroId}" title="Патч ${esc(data.version)}: ${v.label}. Нажмите, чтобы посмотреть изменения (ход не вносится)" role="button" tabindex="0" aria-label="Изменения героя в патче ${esc(data.version)}: ${v.label}">${v.mark}</span>`;
  }

  // клик по значку не должен вносить героя в драфт: перехватываем раньше обработчиков страницы
  document.addEventListener("click", (e) => {
    const m = e.target.closest(".patch-mark[data-patch]");
    if (!m) return;
    e.preventDefault();
    e.stopPropagation();
    openPopup(Number(m.dataset.patch));
  }, true);
  // с клавиатуры: Enter или пробел на значке
  document.addEventListener("keydown", (e) => {
    const m = e.target.closest?.(".patch-mark[data-patch]");
    if (!m || (e.key !== "Enter" && e.key !== " ")) return;
    e.preventDefault();
    e.stopPropagation();
    openPopup(Number(m.dataset.patch));
  }, true);

  return { load, get data() { return data; }, byId, verdict, heroCard, chip, statsLine, notesHtml, noteHtml, abilityHead, owners,
    openPopup, markHtml, esc, pct, pp, plural };
})();
