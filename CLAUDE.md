# Dota 2 assistant

Учебный ИИ-помощник для любительской команды (Captains Mode, 5 игроков). Шаг 1: выгрузка сырых данных
из OpenDota API (`src/main/java/dota/fetch`). Решения — `docs/decisions/`.

## Команды

- Сборка и тесты: `./gradlew build`
- Выгрузка: `./gradlew run` (`--args="--refresh"` — перекачать всё, `--only <id>`, `--no-meta`)
- Отчёты: `./gradlew analyze` → `analysis/` (код в `src/main/java/dota/analysis`, веса в `Scoring`)

Нужен JDK 21: Gradle находит его через toolchain (в т.ч. в `~/.jdks`). Java в PATH может быть старше,
тогда задайте `JAVA_HOME`.

## Данные для анализа

При вопросах про игроков, драфт, героев и мету — сначала `analysis/` (готовые выводы; план драфта —
`analysis/team/draft_plan.md`, написан вручную и после `analyze` не обновляется), детали — в `data/`
(раскладка в README.md). Состав и роли — `config/players.yaml`.
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
