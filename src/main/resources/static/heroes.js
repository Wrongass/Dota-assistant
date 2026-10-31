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


  // ------------------------------------------------------------ поиск героя (драфт, контрпики)
  // Сокращения, которыми героев называют в игре; ищутся и по началу: «кунк» → Kunkka. Дополняются инициалами
  // составных имён (WK, OD, PA). Явное сокращение важнее инициалов: es → Earthshaker, а не Earth/Ember Spirit.
  const SLANG = {
    "вк": "Wraith King", "ам": "Anti-Mage", "антимаг": "Anti-Mage", "пудж": "Pudge", "инвокер": "Invoker", "инвок": "Invoker",
    "леш": "Leshrac", "лешрак": "Leshrac", "од": "Outworld Devourer", "лич": "Lich", "лион": "Lion",
    "лина": "Lina", "вайпер": "Viper", "тини": "Tiny", "свен": "Sven", "сларк": "Slark",
    "кунка": "Kunkka", "куннка": "Kunkka", "варлок": "Warlock", "ворлок": "Warlock", "варик": "Warlock", "рубик": "Rubick",
    "энигма": "Enigma", "дб": "Dawnbreaker", "даунбрейкер": "Dawnbreaker", "огр": "Ogre Magi",
    "кентавр": "Centaur Warrunner", "цм": "Crystal Maiden", "см": "Crystal Maiden", "км": "Crystal Maiden",
    "вс": "Vengeful Spirit", "венга": "Vengeful Spirit", "венге": "Vengeful Spirit", "грим": "Grimstroke",
    "гримстрок": "Grimstroke", "дизраптор": "Disruptor", "никс": "Nyx Assassin", "сб": "Spirit Breaker",
    "бара": "Spirit Breaker", "дк": "Dragon Knight", "некр": "Necrophos", "некрофос": "Necrophos",
    "войд": "Faceless Void", "фв": "Faceless Void", "войд спирит": "Void Spirit", "вс войд": "Void Spirit",
    "пл": "Phantom Lancer", "па": "Phantom Assassin", "сф": "Shadow Fiend", "шадоу финд": "Shadow Fiend",
    "шадоу шаман": "Shadow Shaman", "шадоу демон": "Shadow Demon", "шш": "Shadow Shaman", "шторм": "Storm Spirit",
    "эмбер": "Ember Spirit", "котл": "Keeper of the Light", "котел": "Keeper of the Light", "бх": "Bounty Hunter",
    "ww": "Winter Wyvern", "вивер": "Winter Wyvern", "виверна": "Winter Wyvern", "андерлорд": "Underlord",
    "андер": "Underlord", "дроу": "Drow Ranger", "джагер": "Juggernaut", "джаг": "Juggernaut",
    "джагернаут": "Juggernaut", "ведьмак": "Witch Doctor", "вд": "Witch Doctor", "шаман": "Shadow Shaman",
    "раста": "Shadow Shaman", "акс": "Axe", "мипо": "Meepo", "тид": "Tidehunter", "тайд": "Tidehunter",
    "тайдхантер": "Tidehunter", "хускар": "Huskar", "луна": "Luna", "мирана": "Mirana", "уилоу": "Dark Willow",
    "вилоу": "Dark Willow", "худ": "Hoodwink", "худвинк": "Hoodwink", "джакиро": "Jakiro", "абаддон": "Abaddon",
    "аба": "Abaddon", "омни": "Omniknight", "клинкз": "Clinkz", "санд": "Sand King", "ск": "Sand King",
    "легионка": "Legion Commander", "лц": "Legion Commander", "найт": "Night Stalker", "нс": "Night Stalker",
    "спектра": "Spectre", "спектр": "Spectre", "вр": "Windranger", "wr": "Windranger", "виндра": "Windranger",
    "виндрейнджер": "Windranger", "слардар": "Slardar", "разор": "Razor", "бист": "Beastmaster",
    "бистмастер": "Beastmaster", "снайпер": "Sniper", "снайп": "Sniper", "тимбер": "Timbersaw",
    "лайфстилер": "Lifestealer", "лс": "Lifestealer", "гуль": "Lifestealer", "наикс": "Lifestealer",
    "силенсер": "Silencer", "сайленсер": "Silencer", "текис": "Techies", "течис": "Techies", "квопа": "Queen of Pain",
    "квоп": "Queen of Pain", "королева": "Queen of Pain", "рингмастер": "Ring Master", "феникс": "Phoenix",
    "веном": "Venomancer", "веномансер": "Venomancer", "снапфаер": "Snapfire", "снеп": "Snapfire", "ларго": "Largo",
    "энча": "Enchantress", "чен": "Chen", "зевс": "Zeus", "пак": "Puck", "пук": "Puck", "морф": "Morphling",
    "монки": "Monkey King", "мк": "Monkey King", "урса": "Ursa", "медуза": "Medusa", "дарк сир": "Dark Seer",
    "дс": "Dark Seer", "доом": "Doom", "дум": "Doom", "марс": "Mars", "брю": "Brewmaster", "эс": "Earth Spirit",
    "es": "Earthshaker", "эшейкер": "Earthshaker", "шейкер": "Earthshaker", "ио": "Io", "висп": "Io",
    "оракл": "Oracle", "дазл": "Dazzle", "бейн": "Bane", "скай": "Skywrath Mage", "скайрат": "Skywrath Mage",
    "тб": "Terrorblade", "террор": "Terrorblade", "бб": "Bristleback", "бристл": "Bristleback",
    "бристлбек": "Bristleback", "рики": "Riki", "нага": "Naga Siren", "тусик": "Tusk", "туск": "Tusk",
    "клок": "Clockwerk", "клокверк": "Clockwerk", "батрайдер": "Batrider", "бат": "Batrider", "визаж": "Visage",
    "пугна": "Pugna", "лоне": "Lone Druid", "друид": "Lone Druid", "ld": "Lone Druid", "тинкер": "Tinker",
    "тинк": "Tinker", "арк": "Arc Warden", "арквар": "Arc Warden", "элдер": "Elder Titan", "мага": "Ogre Magi",
  };
  // слово, набранное не в той раскладке: «ыефк» → «star», «вфя» → «daz»
  const RU = "йцукенгшщзхъфывапролджэячсмитьбю", EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.";
  const relayout = (q, from, to) => [...q].map((c) => { const i = from.indexOf(c); return i < 0 ? c : to[i]; }).join("");

  /**
   * Поиск героя по имени, началу имени, инициалам и сленгу. heroes — [{id, name}]. find(q, {exclude, boost}) →
   * герои по порядку: точное совпадение, начало имени, начало сокращения, начало слова, вхождение; при равенстве —
   * сначала из boost (например, советы на этот ход), потом по алфавиту. exclude — id, которых нет в выдаче.
   */
  window.heroSearch = (heroes) => {
    const nameToId = new Map(heroes.map((h) => [h.name.toLowerCase(), h.id]));
    const aliases = new Map();
    for (const h of heroes) {
      const words = h.name.toLowerCase().replace(/'/g, "").split(/[\s-]+/);
      if (words.length > 1) aliases.set(words.map((w) => w[0]).join(""), h.id);
    }
    for (const [alias, name] of Object.entries(SLANG)) {
      const id = nameToId.get(name.toLowerCase());
      if (id) aliases.set(alias, id);
    }
    function scored(q, exclude) {
      const exact = aliases.get(q);
      const prefixAliases = new Set();
      if (q.length >= 2) for (const [alias, id] of aliases) if (alias.startsWith(q)) prefixAliases.add(id);
      const out = [];
      for (const h of heroes) {
        if (exclude && exclude.has(h.id)) continue;
        const n = h.name.toLowerCase().replace(/'/g, "");
        let score = -1;
        if (h.id === exact || n === q) score = 0;
        else if (n.startsWith(q)) score = 1;
        else if (prefixAliases.has(h.id)) score = 1.5;
        else if (n.split(/[\s-]+/).some((w) => w.startsWith(q))) score = 2;
        else if (n.includes(q)) score = 3;
        if (score >= 0) out.push([score, h]);
      }
      return out;
    }
    return {
      find(query, opts = {}) {
        const q = String(query || "").trim().toLowerCase().replace(/'/g, "").replace(/\s+/g, " ");
        if (!q) return [];
        let found = scored(q, opts.exclude);
        if (!found.length && /[а-яё]/.test(q)) found = scored(relayout(q, RU, EN), opts.exclude);
        if (!found.length && /[a-z]/.test(q)) found = scored(relayout(q, EN, RU), opts.exclude);
        const boost = opts.boost || new Set();
        return found.sort((a, b) => a[0] - b[0] || (boost.has(b[1].id) - boost.has(a[1].id)) || a[1].name.localeCompare(b[1].name))
          .map((x) => x[1]);
      },
    };
  };

  /** Список героев [{id, name, attr}] — из кэша вкладки или с сервера. */
  window.loadHeroes = () => {
    try {
      const cached = JSON.parse(sessionStorage.getItem("heroes:v1") || "null");
      if (cached) return Promise.resolve(cached);
    } catch (e) { /* ок */ }
    return fetch("/api/heroes").then((r) => r.ok ? r.json() : Promise.reject(r.status)).then((heroes) => {
      try { sessionStorage.setItem("heroes:v1", JSON.stringify(heroes)); } catch (e) { /* ок */ }
      return heroes;
    });
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
