package dota.analysis;

import dota.fetch.PlayerConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Всё, что нужно отчётам и сайту, загруженное один раз из data/ и config/players.yaml.
 * Расчёты привязаны к дате: опорный момент — полночь UTC, поэтому вывод воспроизводим.
 */
public final class AnalysisContext {

    public final PlayerConfig config;
    final Meta meta;
    final League league;
    /** Линия и матчапы героев из STRATZ; пустые, если их не качали. */
    final LaneStats lanes;
    /** Свойства героев из config/hero_traits.yaml (для симуляции драфта и оценки). */
    public final HeroTraits traits;
    public final Squad ours;
    public final List<Squad> opponents;
    public final LocalDate date;
    public final Instant loadedAt = Instant.now();
    public final Path dataDir;
    /** Игроки из конфига, чьих данных ещё нет в data/ (новые игроки до первой выгрузки): их пропускаем. */
    private final List<String> missingData;

    /**
     * Состав соперника для драфта: key — account_id по позициям через «-», label — «1 ник · 2 ник …», maps — карт этим
     * составом в турнире (0 — выбран на сайте), last — дата последней карты, squad — команда с этим составом.
     */
    public record TeamLineup(String key, String label, int maps, String last, Squad squad) {
    }

    /** Составы соперников строятся при первом запросе драфта: на запуск сайта не влияют. */
    private final java.util.concurrent.ConcurrentHashMap<String, List<TeamLineup>> lineupCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private AnalysisContext(PlayerConfig config, Meta meta, League league, LaneStats lanes, HeroTraits traits, Squad ours,
                            List<Squad> opponents, LocalDate date, Path dataDir, List<String> missingData) {
        this.config = config;
        this.traits = traits;
        this.meta = meta;
        this.league = league;
        this.lanes = lanes;
        this.ours = ours;
        this.opponents = opponents;
        this.date = date;
        this.dataDir = dataDir;
        this.missingData = List.copyOf(missingData);
    }

    /** «ник (команда)» игроков без скачанных данных — для предупреждения на сайте. */
    public List<String> missingData() {
        return missingData;
    }

    public static AnalysisContext load(Path configPath, Path dataDir, LocalDate date) throws IOException {
        return load(configPath, dataDir, date, Lineups.none());
    }

    /** lineups — состав на игру, выбранный на сайте; {@link Lineups#none()} — по конфигу. */
    public static AnalysisContext load(Path configPath, Path dataDir, LocalDate date, Lineups lineups) throws IOException {
        PlayerConfig config = PlayerConfig.load(configPath);
        Meta meta = Meta.load(dataDir);
        League league = config.league() == null || config.league().id() == null ? null
                : League.load(dataDir, config.league());
        // опорный момент — полночь UTC даты отчёта: вывод воспроизводим (веса по давности не «плывут»)
        Instant now = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        String today = date.toString();

        List<String> missing = new ArrayList<>();
        Squad ours = squad(dataDir, config, null, config.ourTeam(), true, meta, league, now, today, lineups, missing);
        List<Squad> opponents = new ArrayList<>();
        if (config.league() != null) {
            for (PlayerConfig.TeamInfo t : config.league().opponentsOrEmpty()) {
                Squad s = squad(dataDir, config, t.name(), t, false, meta, league, now, today, lineups, missing);
                if (!s.players.isEmpty()) {
                    opponents.add(s);
                }
            }
        }
        // имена для матчей лиги — из конфига: команды «SD Team (GGsel)» вместо «GGsel» из OpenDota, ники вместо Steam-ников
        List<Squad> all = new ArrayList<>(opponents);
        all.add(ours);
        for (Squad s : all) {
            for (Squad other : all) {
                if (other.team != null && other.team.teamId() != null) {
                    s.teamNames.put(other.team.teamId(), other.teamName());
                }
            }
            config.players().forEach(p -> s.nicks.put(p.accountId(), p.nick()));
            if (config.league() != null && s.team != null) {
                config.league().manualMapsOrEmpty().stream()
                        .filter(mm -> s.team.name().equals(mm.winner()) || s.team.name().equals(mm.loser()))
                        .forEach(s.manualMaps::add);
            }
        }
        Path traitsFile = configPath.toAbsolutePath().getParent().resolve("hero_traits.yaml");
        return new AnalysisContext(config, meta, league, LaneStats.load(dataDir), HeroTraits.load(traitsFile), ours,
                opponents, date, dataDir, missing);
    }

    public static AnalysisContext load(Path configPath, Path dataDir) throws IOException {
        return load(configPath, dataDir, LocalDate.now(ZoneOffset.UTC));
    }

    /**
     * Команда с составом на игру. teamName == null — наши игроки, иначе соперники с этим team_name. Состав: выбранный на
     * сайте (если все его игроки есть в конфиге этой команды), иначе игроки без reserve; остальные — запасные (bench).
     */
    private static Squad squad(Path dataDir, PlayerConfig config, String teamName, PlayerConfig.TeamInfo team,
                               boolean ours, Meta meta, League league, Instant now, String today, Lineups lineups,
                               List<String> missing) throws IOException {
        // игрок есть в конфиге, а данных ещё нет (на сервере новый конфиг приходит с деплоем, данные — со следующей
        // выгрузкой): пропускаем его, иначе сайт не запустится
        List<PlayerConfig.Player> all = config.players().stream()
                .filter(p -> teamName == null ? p.team() == PlayerConfig.Team.OUR
                        : p.team() == PlayerConfig.Team.OPP && teamName.equals(p.teamName()))
                .filter(p -> hasData(dataDir, p, missing))
                .toList();
        Set<Long> ids = all.stream().map(PlayerConfig.Player::accountId).collect(Collectors.toSet());
        java.util.Map<Integer, Long> chosen = team == null ? null : lineups.of(team.name());
        boolean changed = chosen != null && chosen.size() == 5 && ids.containsAll(chosen.values())
                && chosen.values().stream().distinct().count() == 5;
        List<PlayerConfig.Player> active = new ArrayList<>();
        List<PlayerConfig.Player> bench = new ArrayList<>();
        if (changed) {
            java.util.Map<Long, Integer> posOf = new java.util.HashMap<>();
            chosen.forEach((pos, id) -> posOf.put(id, pos));
            for (PlayerConfig.Player p : all) {
                if (posOf.containsKey(p.accountId())) {
                    active.add(p.at(posOf.get(p.accountId())));
                } else {
                    bench.add(p);
                }
            }
        } else {
            all.forEach(p -> (p.isReserve() ? bench : active).add(p));
        }
        List<PlayerStats> players = new ArrayList<>();
        for (PlayerConfig.Player p : active) {
            players.add(PlayerStats.load(dataDir, p, ids, meta.patchStart, now));
        }
        Squad squad = new Squad(meta, players, team, ours, league, today);
        for (PlayerConfig.Player p : bench) {
            squad.bench.add(PlayerStats.load(dataDir, p, ids, meta.patchStart, now));
        }
        squad.lineupChanged = changed;
        return squad;
    }

    private static boolean hasData(Path dataDir, PlayerConfig.Player p, List<String> missing) {
        Path dir = dataDir.resolve("raw").resolve(p.team().dir()).resolve(String.valueOf(p.accountId()));
        boolean ok = java.nio.file.Files.isRegularFile(dir.resolve("profile.json"))
                && java.nio.file.Files.isRegularFile(dir.resolve("matches.json"));
        if (!ok) {
            missing.add(p.nick() + " (" + (p.teamName() == null ? "наша команда" : p.teamName()) + ")");
        }
        return ok;
    }

    public String patch() {
        return meta.patch;
    }

    public Instant patchStart() {
        return meta.patchStart;
    }

    /** Есть ли статистика линии (STRATZ). */
    public boolean hasLanes() {
        return !lanes.isEmpty();
    }

    public boolean hasLeague() {
        return league != null;
    }

    public Views.Group group() {
        return league == null ? null : Squad.group(meta, league, config);
    }

    /** Карты команды в лиге этого сезона: «побед–поражений», или пусто, если матчей нет. */
    public String mapRecord(PlayerConfig.TeamInfo team) {
        if (league == null || team == null || team.teamId() == null) {
            return "";
        }
        List<League.Match> maps = league.of(team.teamId());
        if (maps.isEmpty()) {
            return "";
        }
        long wins = maps.stream().filter(m -> m.won(team.teamId())).count();
        return wins + "–" + (maps.size() - wins) + " по картам";
    }

    public Squad opponent(String slug) {
        // адрес без учёта регистра: /opponent/bim_tim и /opponent/BIM_TIM — одна страница
        return slug == null ? null
                : opponents.stream().filter(s -> s.slug().equalsIgnoreCase(slug)).findFirst().orElse(null);
    }

    /**
     * Составы, которыми соперник выходил в турнире (сначала свежий), и состав, выбранный на сайте, если его нет среди
     * них. Пусто, если состав был один: тогда драфт работает с командой как есть.
     */
    public List<TeamLineup> lineupsOf(Squad team) {
        if (team == null || team == ours || team.team == null || team.team.teamId() == null || league == null) {
            return List.of();
        }
        return lineupCache.computeIfAbsent(team.slug(), k -> {
            try {
                return buildLineups(team);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    private List<TeamLineup> buildLineups(Squad team) throws IOException {
        Set<Long> known = new java.util.HashSet<>();
        team.players.forEach(p -> known.add(p.config.accountId()));
        team.bench.forEach(p -> known.add(p.config.accountId()));
        List<TeamLineup> result = new ArrayList<>();
        java.time.format.DateTimeFormatter day = java.time.format.DateTimeFormatter.ofPattern("dd.MM").withZone(ZoneOffset.UTC);
        for (TeamLineups.Seen s : TeamLineups.seen(league.of(team.team.teamId()), team.team.teamId(), known)) {
            Squad sq = withLineup(team, s.lineup());
            result.add(new TeamLineup(lineupKey(sq), lineupLabel(sq), s.maps(), day.format(Instant.ofEpochSecond(s.lastTime())), sq));
        }
        String current = lineupKey(team);
        if (team.lineupChanged && result.stream().noneMatch(l -> l.key().equals(current))) {
            result.add(0, new TeamLineup(current, lineupLabel(team), 0, "выбран на сайте", team));
        }
        return result.size() < 2 ? List.of() : List.copyOf(result);
    }

    /** Команда team (наша или соперник) с другим составом «позиция → account_id»: составы из турнира, бэктест. */
    Squad withLineup(Squad team, java.util.Map<Integer, Long> lineup) throws IOException {
        boolean isOurs = team == ours;
        Squad sq = squad(dataDir, config, isOurs ? null : team.team.name(), team.team, isOurs, meta, league,
                date.atStartOfDay(ZoneOffset.UTC).toInstant(), date.toString(), Lineups.of(team.team.name(), lineup),
                new ArrayList<>());
        sq.teamNames.putAll(team.teamNames);
        sq.nicks.putAll(team.nicks);
        sq.manualMaps.addAll(team.manualMaps);
        return sq;
    }

    static String lineupKey(Squad s) {
        return s.byPosition().stream().map(p -> String.valueOf(p.config.accountId())).collect(Collectors.joining("-"));
    }

    private static String lineupLabel(Squad s) {
        return s.byPosition().stream().map(p -> s.position(p) + " " + p.nick()).collect(Collectors.joining(" · "));
    }

    /** Команда, в которой играет account_id (в составе или запасным), или null. */
    public Squad squadOf(long accountId) {
        if (ours.player(accountId) != null) {
            return ours;
        }
        return opponents.stream().filter(s -> s.player(accountId) != null).findFirst().orElse(null);
    }

    /** Команда по имени из конфига (наша или соперник), или null. */
    public Squad squadNamed(String name) {
        if (ours.team() != null && ours.team().name().equals(name)) {
            return ours;
        }
        return opponents.stream().filter(s -> s.team().name().equals(name)).findFirst().orElse(null);
    }

    public String heroName(int heroId) {
        return meta.name(heroId);
    }

    /** Герой для сетки драфта: attr — str / agi / int / all; roles — роли из справочника OpenDota (Carry, Disabler…). */
    public record HeroInfo(int id, String name, String attr, List<String> roles) {
    }

    public List<HeroInfo> heroList() {
        return meta.heroes.values().stream()
                .sorted(java.util.Comparator.comparing(Meta.Hero::name))
                .map(h -> new HeroInfo(h.id(), h.name(), h.attr(), h.roles()))
                .toList();
    }
}
