// «Мета»: поиск героя тем же поиском, что в драфте (сленг, русские имена, раскладка). Строки и чипы с data-heroes
// показываются, если в них есть найденный герой; пустые таблицы подписываются; «Все герои» открывается сам.
(() => {
  const input = document.getElementById("hero-filter");
  const note = document.getElementById("filter-note");
  if (!input) return;
  loadHeroes().then((heroes) => {
    const search = heroSearch(heroes);
    const apply = () => {
      const q = input.value.trim();
      const ids = q ? new Set(search.find(q).map((h) => String(h.id))) : null;
      document.querySelectorAll("[data-heroes]").forEach((el) => {
        const show = !ids || el.dataset.heroes.split(" ").some((id) => ids.has(id));
        el.style.display = show ? "" : "none";
      });
      document.querySelectorAll("table.filterable").forEach((t) => {
        let empty = t.tBodies[0].querySelector("tr.empty-note");
        const visible = [...t.tBodies[0].rows].some((r) => !r.classList.contains("empty-note") && r.style.display !== "none");
        if (!visible && ids) {
          if (!empty) {
            empty = t.tBodies[0].insertRow();
            empty.className = "empty-note";
            const td = empty.insertCell();
            td.colSpan = t.tHead.rows[0].cells.length;
            td.className = "muted small";
          }
          empty.cells[0].textContent = "здесь нет — смотрите «Все герои патча» ниже";
          empty.style.display = "";
        } else if (empty) {
          empty.style.display = "none";
        }
      });
      const all = document.getElementById("all-heroes");
      if (ids) all.open = true;
      note.hidden = !q;
      note.textContent = ids && ids.size === 0 ? `Не нашёл героя «${q}»` : ids ? `Найдено героев: ${ids.size}` : "";
    };
    input.addEventListener("input", apply);
  });
})();
