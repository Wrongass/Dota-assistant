# Dota 2 assistant

Учебный ИИ-помощник для нашей команды в любительском турнире по Dota 2 (Captains Mode).

Шаг 1: выгрузить из [OpenDota API](https://docs.opendota.com/) все доступные данные по игрокам и общую
картину текущего патча, сохранить ответы как есть, а анализировать потом.

## Запуск

Нужен JDK 21. Gradle-wrapper в репозитории, Gradle ставить не нужно.

```bash
./gradlew run                                   # докачать то, чего нет
./gradlew run --args="--refresh"                # перекачать всё
./gradlew run --args="--only 138244651"         # один игрок
./gradlew run --args="--no-meta"                # без данных по патчу
./gradlew run --args="--no-league"              # без матчей лиги
./gradlew analyze                               # отчёты в analysis/ из data/
./gradlew test                                  # тесты
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
