// Сортировка таблиц по клику на заголовок (таблицы с классом "sortable").
document.addEventListener("click", (e) => {
  const th = e.target.closest("table.sortable th");
  if (!th) return;
  const table = th.closest("table");
  const index = Array.from(th.parentNode.children).indexOf(th);
  const body = table.tBodies[0];
  const asc = th.dataset.sort !== "asc";
  table.querySelectorAll("th").forEach((h) => delete h.dataset.sort);
  th.dataset.sort = asc ? "asc" : "desc";
  const value = (row) => {
    const text = row.children[index]?.innerText.trim() ?? "";
    const n = parseFloat(text.replace(",", ".").replace("%", "").replace("+", ""));
    return Number.isNaN(n) || !/^[+\-]?\d/.test(text) ? text.toLowerCase() : n;
  };
  const rows = Array.from(body.rows);
  rows.sort((a, b) => {
    const x = value(a), y = value(b);
    if (x === y) return 0;
    if (x === "—") return 1;
    if (y === "—") return -1;
    return (x < y ? -1 : 1) * (asc ? 1 : -1);
  });
  rows.forEach((r) => body.appendChild(r));
});
