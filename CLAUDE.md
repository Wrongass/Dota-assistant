# Dota 2 assistant

Учебный ИИ-помощник для любительской команды (Captains Mode, 5 игроков). Шаг 1: выгрузка сырых данных
из OpenDota API (`src/main/java/dota/fetch`). Решения — `docs/decisions/`.

## Команды

- Сборка и тесты: `./gradlew build`
- Выгрузка: `./gradlew run` (`--args="--refresh"` — перекачать всё, `--only <id>`, `--no-meta`, `--no-stratz`;
  линия героев из STRATZ — токен в `config/tokens.env`, лежит в git по решению владельца, ADR 0005)
- Отчёты: `./gradlew analyze` → `analysis/` (код в `src/main/java/dota/analysis`, веса в `Scoring`)
- Сайт: `./gradlew web` → http://127.0.0.1:8070 (`dota.web`, шаблоны `src/main/jte`, статика `src/main/resources/static`)
- Бэктест драфта: `./gradlew draftBacktest`

Архитектура (ADR 0003): `AnalysisContext` грузит всё один раз → `Squad` считает view-модели (`Views`) →
их показывают `MarkdownRenderer` и сайт. Live-драфт — `DraftEngine`. `GoldenReportTest` сверяет генератор с
закоммиченным `analysis/`: после изменения данных или расчётов перегенерируйте `analysis/` (`./gradlew analyze`).

Нужен JDK 21: Gradle находит его через toolchain (в т.ч. в `~/.jdks`). Java в PATH может быть старше,
тогда задайте `JAVA_HOME`.

## Журнал работы

После каждого запроса, который меняет логику, данные или поведение приложения, добавляй запись в
`docs/history/claude-code-sessions.md`: дата; что попросил пользователь (пересказ, если относится к делу); что
сделано; какие решения приняты и кем (пользователь или агент); где агент ошибся и как исправили; чем проверено.
Если изменение затрагивает план, обнови `docs/plans.md`. Не упоминай личные данные, ключи и третьих лиц, не
связанных с проектом. Стиль простой и разговорный, без тире-вставок, без конструкций «не X, а Y», без
афористичных концовок.

## Данные для анализа

При вопросах про игроков, драфт, героев и мету — сначала `analysis/` (готовые выводы; план драфта —
`analysis/team/draft_plan.md`, написан вручную и после `analyze` не обновляется), детали — в `data/`
(раскладка в README.md). Состав, роли, лига и группа — `config/players.yaml`.
Соперники — `analysis/opponents/{team}/` (план на игру — `game_plan.md`, вручную). Матчи лиги —
`data/raw/league/{id}/matches/`; на OpenDota лига `excluded`: команд и составов API не отдаёт, только match_id.
Мета патча: `data/meta/{patch}/`, параметры выборки — в `_sample.json` рядом.

Особенности формата (проверено на реальных ответах):
- `matches.json`: детальные поля (lane_role, purchase, item_*, stuns, obs…) есть только у разобранных
  матчей (~18%), у остальных `null`. `heroes` — объект с ключами-player_slot строкой
  ("0".."4" Radiant, "128".."132" Dire). Патча в матче нет — вычислять по `start_time` и
  `constants/patch.json`. Captains Mode — `game_mode == 2`.
- `lane_role` — это линия (1 safe, 2 mid, 3 off, 4 jungle), а не позиция 1–5. 0 — не определена.
- `counts.json`, `constants/*.json` — объекты с числовыми ключами-строками.
- `lane_roles.json`: `games` и `wins` — строки. В explorer-файлах (`hero_*.json`) данные в `rows`.
- `hero_stats.json`: `{N}_pick` / `{N}_win` — ранговые корзины 1..8 (Herald..Immortal).
- Синергия пары: сравнивать винрейт пары с средним базовым винрейтом героев из `hero_winrate.json`,
  а не с 50%.
