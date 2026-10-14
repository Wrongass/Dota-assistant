# Dota 2 assistant

Учебный ИИ-помощник для нашей команды в любительском турнире по Dota 2 (Captains Mode).

Выгружает из [OpenDota API](https://docs.opendota.com/) данные по нашим игрокам, соперникам по группе и
текущему патчу, строит отчёты и показывает всё на **локальном сайте** с помощником для живого драфта.

## Быстрый старт

```bash
./gradlew run        # скачать/докачать данные (≈140 запросов к OpenDota, пара минут)
./gradlew web        # сайт: http://127.0.0.1:8070
```

Перед игрой: `./gradlew run`, затем на сайте кнопка «Перечитать данные» (или перезапуск `web`).

## Запуск

Нужен JDK 21. Gradle-wrapper в репозитории, Gradle ставить не нужно.

```bash
./gradlew run                                   # докачать то, чего нет
./gradlew run --args="--refresh"                # перекачать всё
./gradlew run --args="--only 138244651"         # один игрок
./gradlew run --args="--no-meta"                # без данных по патчу
./gradlew run --args="--no-league"              # без матчей лиги
./gradlew analyze                               # отчёты в analysis/ из data/
./gradlew analyze --args="--date 2026-10-14"    # отчёты на дату (давность матчей считается от неё)
./gradlew web                                   # сайт на http://127.0.0.1:8070 (--args="--port 8080")
./gradlew draftBacktest                         # проверка прогнозов драфта на матчах группы
./gradlew build                                 # сборка и тесты
```

На Windows вместо `./gradlew` используйте `gradlew.bat`.

Опции меты: `--meta-window <n>` — сколько последних match_id брать в выборку (по умолчанию 1 000 000,
это около 250 тыс. матчей Legend+ за ~15 часов); `--meta-min-rank <n>` — минимальный средний `rank_tier`
матча (по умолчанию 50 = Legend).

Если есть ключ OpenDota, задайте переменную окружения `OPENDOTA_API_KEY`. Без ключа лимит
60 запросов в минуту и 3000 в день. Полный прогон для 10 игроков занимает около 100 запросов.

## Игроки

`config/players.yaml` (полный пример — в самом файле):

```yaml
our_team: {name: Team Visual, organization: Долг, team_id: 10255027}
league:
  id: 18845                 # лига OpenDota
  since: 2026-10-01         # начало текущего сезона
  group:                    # соперники по группе
    - {name: UPPO TEAM, organization: УППО, team_id: 9564376}
players:
  - account_id: 138244651
    nick: Wrongass
    team: our        # our | opp
    position:        # 1..5 или пусто
  - account_id: 361977946
    nick: Togo
    team: opp
    team_name: UPPO TEAM   # обязательно для соперников
    position: 1
```

## Что скачивается

```
data/raw/constants/{heroes,game_mode,lobby_type,patch}.json
data/raw/{our|opp}/{account_id}/
    profile.json   /players/{id}
    wl.json        /players/{id}/wl?significant=0         победы/поражения, сверка с matches.json
    matches.json   /players/{id}/matches?significant=0&project=…   все матчи, 56 полей
    heroes.json    /players/{id}/heroes                   по героям: свои игры, с ним, против него
    peers.json     /players/{id}/peers                    с кем играл
    counts.json    /players/{id}/counts                   разбивки по lane_role, game_mode, patch…
    totals.json    /players/{id}/totals                   суммы по метрикам
    ratings.json   /players/{id}/ratings                  история rank_tier
    rankings.json  /players/{id}/rankings                 перцентиль игрока на героях
data/raw/league/{league_id}/
    match_ids.json      /leagues/{id}/matchIds — перекачивается каждый запуск (растёт по ходу турнира)
    matches/{id}.json   /matches/{id} — матчи текущего сезона (с league.since), с драфтом
data/meta/{patch}/
    hero_stats.json     /heroStats            пики/победы по рангам, pro pick/ban, тренд за неделю
    lane_roles.json     /scenarios/laneRoles  винрейт героя по линии и длительности
    hero_winrate.json   /explorer  винрейт героев в выборке патча
    hero_synergy.json   /explorer  пары героев в одной команде
    hero_counters.json  /explorer  герой против героя
    hero_positions.json /explorer  позиция 1–5 героя в разобранных матчах патча
    _sample.json        параметры выборки
```

Уже скачанные файлы не перекачиваются без `--refresh`. Подробности и причины решений:
[docs/decisions/0001-data-fetch.md](docs/decisions/0001-data-fetch.md).

## Сайт

`./gradlew web` → http://127.0.0.1:8070 (только этот компьютер). К OpenDota сайт не обращается: читает `data/`,
`config/players.yaml` и ручные планы из `analysis/`. Кнопка «Перечитать данные» подхватывает изменения без перезапуска.

| Страница | Что на ней |
|---|---|
| Главная | следующий соперник (`league.next_opponent`), группа, наш состав, предупреждения о данных |
| Команда / Игрок | состав, где реально играют, сыгранность; карточка игрока — наша или соперника |
| Наш драфт | план драфта (`draft_plan.md`) + пулы, связки, неудобные герои, кандидаты в баны |
| Турнир / Группа | наши матчи с драфтами по ходам; все матчи группы |
| Соперник | план на игру (`game_plan.md`), угрозы, их баны, ответы из наших пулов, их турнир, состав |
| Мета / Методика | мета патча по позициям, про-сцена, связки, контрпики; как считаются оценки |
| **Драфт** | живой драфт Captains Mode (ниже) |

### Живой драфт

Выберите соперника и кто ходит первым, затем вносите ходы по порядку: поиск героя (имя, начало имени, инициалы
`WK`, сокращения `вк`, `леш`) и Enter, или клик по герою — в сетке или в подсказках. Порядок ходов CM подставляется
сам (как в 39 матчах нашей лиги). После каждого хода (≈ 10–50 мс) сайт показывает:

- кто у соперника на каком герое (с вероятностью и альтернативой);
- их вероятные следующие пики — с причинами (турнирные пики, умение, ▲/▼ в мете, связки, контрпики наших);
- кого нам банить и кого брать (с «пик-отказом» — герой из их топа, который есть и у нас);
- что они, скорее всего, забанят у нас — наших героев (турнир, последние игры) у тех наших игроков, кто ещё без
  героя; их история банов — лишь небольшая поправка.

Прогноз их пиков исходит из того, кто у них ещё без героя: у каждого свободного игрока своё распределение по
героям, поэтому если остался только мидер — и пики, и баны будут про героев мидера.

Драфт хранится в браузере и в адресе страницы (`#first=us&heroes=…`) — ссылкой можно поделиться.
Бэктест на 3 матчах группы (30 пиков): реальный пик в топ-5 прогноза — 37%, в топ-10 — 50%, «кто на каком
герое» — 30/30. Выборка маленькая: это ориентир, а не гарантия.

### Вход по паролю

Если задана переменная окружения `SITE_PASSWORD`, сайт спрашивает общий пароль команды (вход запоминается на
устройстве на 30 дней). Без неё — открыт, это для локального запуска.

### Docker и сервер

```bash
cp .env.example .env              # SITE_PASSWORD, для сервера ещё DOMAIN
docker compose build
docker compose run --rm fetch     # обновить data/ из OpenDota
docker compose up -d              # сайт на http://localhost:8070
docker compose --profile https up -d   # на сервере: HTTPS через Caddy на https://$DOMAIN
```

Пошагово для сервера — [docs/deploy.md](docs/deploy.md), решения — [ADR 0004](docs/decisions/0004-server-deploy.md).

## Анализ

`./gradlew analyze` строит Markdown-отчёты в `analysis/` (начните с [analysis/README.md](analysis/README.md)):

```
analysis/
  README.md                 оглавление и как считаются оценки
  team/draft_plan.md        план драфта (пишется вручную, генератор не трогает)
  team/roster.md            состав, позиции, ранги, сыгранность
  team/draft_data.md        пулы, связки внутри команды, неудобные герои, баны
  team/tournament.md        наши турнирные матчи: драфты, пики, что банят против нас
  league/group.md           результаты и драфты матчей нашей группы
  opponents/{team}/         соперник: README, game_plan.md (вручную), team/{roster,threats,tournament}.md, players/
  meta/patch_{patch}.md     мета патча по позициям, про-сцена, связки, контрпики
  players/pos{N}_{nick}.md  топ героев игрока и топ под патч на его позиции
```
