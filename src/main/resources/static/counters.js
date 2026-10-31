// Контрпики: поиск героя как в драфте (сленг, начало имени, инициалы, раскладка), ↑↓ и Enter — выбрать.
(() => {
  const form = document.querySelector(".counters-form");
  if (!form) return;
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

  loadHeroes().then((heroes) => {
    const search = heroSearch(heroes);
    const exact = new Set(heroes.map((h) => h.name.toLowerCase()));

    form.querySelectorAll(".hero-input").forEach((box) => {
      const input = box.querySelector("input");
      const list = box.querySelector(".suggest");
      let selected = 0, found = [];
      const render = () => {
        const q = input.value.trim();
        found = q && !exact.has(q.toLowerCase()) ? search.find(q).slice(0, 6) : [];
        if (selected >= found.length) selected = 0;
        list.innerHTML = found.map((h, i) =>
          `<li class="${i === selected ? "sel" : ""}" data-name="${esc(h.name)}">${heroIcon(h.id, h.name)} ${esc(h.name)}</li>`).join("")
          + (q && !found.length && !exact.has(q.toLowerCase()) ? `<li class="none">Не нашёл героя «${esc(q)}»</li>` : "");
      };
      const pick = (name) => { input.value = name; found = []; list.innerHTML = ""; };
      input.addEventListener("input", () => { selected = 0; render(); });
      input.addEventListener("keydown", (e) => {
        if ((e.key === "ArrowDown" || e.key === "ArrowUp") && found.length) {
          e.preventDefault();
          selected = (selected + (e.key === "ArrowDown" ? 1 : found.length - 1)) % found.length;
          render();
        } else if (e.key === "Enter" && found.length) {
          e.preventDefault(); // Enter выбирает героя из подсказки, второй Enter отправляет форму
          pick(found[selected].name);
        } else if (e.key === "Escape") {
          found = []; list.innerHTML = "";
        }
      });
      input.addEventListener("blur", () => setTimeout(() => { list.innerHTML = ""; }, 150));
      list.addEventListener("mousedown", (e) => {
        const li = e.target.closest("li[data-name]");
        if (li) { e.preventDefault(); pick(li.dataset.name); }
      });
    });

    // перед отправкой: «вайпер» → Viper (первый вариант поиска); пустой первый герой — подсказка вместо пустого запроса
    form.addEventListener("submit", (e) => {
      const first = form.querySelector("input[name=h]");
      for (const input of form.querySelectorAll(".hero-input input")) {
        const q = input.value.trim();
        if (q && !exact.has(q.toLowerCase())) {
          const best = search.find(q)[0];
          if (best) input.value = best.name;
        }
      }
      if (!first.value.trim()) {
        e.preventDefault();
        first.placeholder = "Укажите героя, например Viper";
        first.focus();
      }
    });
  });
})();
