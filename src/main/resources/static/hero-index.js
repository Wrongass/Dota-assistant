// Список героев: фильтр как в драфте (сленг, начало имени, инициалы, раскладка), Enter — открыть первого найденного.
(() => {
  const input = document.getElementById("hero-filter");
  const grid = document.getElementById("hero-grid");
  if (!input || !grid) return;
  const none = document.getElementById("hero-none");
  const links = [...grid.querySelectorAll("a[data-name]")];

  loadHeroes().then((heroes) => {
    const search = heroSearch(heroes);
    let first = null;
    const apply = () => {
      const q = input.value.trim();
      const found = q ? search.find(q) : null;
      const names = found ? new Set(found.map((h) => h.name)) : null;
      first = found && found.length ? found[0].name : null;
      let shown = 0;
      for (const a of links) {
        a.hidden = names !== null && !names.has(a.dataset.name);
        if (!a.hidden) shown++;
      }
      none.hidden = shown > 0;
    };
    input.addEventListener("input", apply);
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && first) {
        const a = links.find((l) => l.dataset.name === first);
        if (a) location.href = a.href;
      } else if (e.key === "Escape") {
        input.value = "";
        apply();
      }
    });
    input.focus();
  });
})();
