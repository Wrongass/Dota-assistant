// Общее для всех страниц: переключатель темы и иконки героев рядом с их именами.
// Иконки — официальные картинки Valve, скачанные при выгрузке в data/img (см. ImageFetcher); сайт отдаёт их сам.
(() => {
  // ------------------------------------------------------------ тема
  const toggle = document.getElementById("theme-toggle");
  if (toggle) {
    toggle.onclick = () => {
      const light = document.documentElement.dataset.theme !== "light";
      if (light) document.documentElement.dataset.theme = "light"; else delete document.documentElement.dataset.theme;
      try { localStorage.setItem("theme", light ? "light" : "dark"); } catch (e) { /* ок */ }
    };
  }

  // ------------------------------------------------------------ иконки героев
  const initials = (name) => name.replace(/'/g, "").split(/[\s-]+/).map((w) => w[0]).join("").slice(0, 2).toUpperCase();

  /** HTML иконки героя; если картинки нет — инициалы, а не случайная картинка. */
  window.heroIcon = (id, name, cls = "hero-ico") =>
    `<img class="${cls}" src="/img/icons/${id}.png" alt="" loading="lazy" data-initials="${initials(name)}" onerror="heroIconFallback(this)">`;
  window.heroPortrait = (id, name) =>
    `<img class="hero-portrait" src="/img/heroes/${id}.png" alt="${name}" loading="lazy" data-initials="${initials(name)}" onerror="heroIconFallback(this)">`;
  window.heroIconFallback = (img) => {
    const span = document.createElement("span");
    span.className = img.className + " fallback";
    span.textContent = img.dataset.initials || "?";
    img.replaceWith(span);
  };

  // Имена героев в тексте страницы (таблицы, планы) получают иконку. Не трогаем ссылки, кнопки, поля ввода и драфт.
  const SKIP = "a, button, input, select, textarea, script, style, option, .hero-tag, .no-hero-icons, #draft";

  function enhance(heroes) {
    const byName = new Map(heroes.map((h) => [h.name, h.id]));
    const names = heroes.map((h) => h.name).sort((a, b) => b.length - a.length)
      .map((n) => n.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
    // имя целиком: до и после — не буква (Lion не внутри Dandelion)
    const pattern = `(^|[^\\p{L}])(${names.join("|")})(?![\\p{L}])`;
    const re = new RegExp(pattern, "gu");
    const has = new RegExp(pattern, "u"); // без флага g: test() не помнит позицию между узлами
    const main = document.querySelector("main");
    if (!main) return;
    const walker = document.createTreeWalker(main, NodeFilter.SHOW_TEXT, {
      acceptNode: (node) => node.parentElement.closest(SKIP) || !has.test(node.nodeValue)
        ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT,
    });
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    for (const node of nodes) {
      const html = node.nodeValue.replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" })[c]);
      const span = document.createElement("span");
      span.innerHTML = html.replace(re, (_, pre, name) =>
        `${pre}<span class="hero-tag">${heroIcon(byName.get(name), name)}${name}</span>`);
      node.replaceWith(...span.childNodes);
    }
  }

  const KEY = "heroes:v1";
  let cached = null;
  try { cached = JSON.parse(sessionStorage.getItem(KEY) || "null"); } catch (e) { /* ок */ }
  if (cached) {
    enhance(cached);
  } else {
    fetch("/api/heroes").then((r) => r.ok ? r.json() : Promise.reject(r.status)).then((heroes) => {
      try { sessionStorage.setItem(KEY, JSON.stringify(heroes)); } catch (e) { /* ок */ }
      enhance(heroes);
    }).catch(() => { /* без иконок страница остаётся как есть */ });
  }
})();
