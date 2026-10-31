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
    public final Squad ours;
    public final List<Squad> opponents;
    public final LocalDate date;
    public final Instant loadedAt = Instant.now();
    public final Path dataDir;
    /** Игроки из конфига, чьих данных ещё нет в data/ (новые игроки до первой выгрузки): их пропускаем. */
    private final List<String> missingData;

    private AnalysisContext(PlayerConfig config, Meta meta, League league, LaneStats lanes, Squad ours,
                            List<Squad> opponents, LocalDate date, Path dataDir, List<String> missingData) {
        this.config = config;
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
        return new AnalysisContext(config, meta, league, LaneStats.load(dataDir), ours, opponents, date, dataDir, missing);
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
        return opponents.stream().filter(s -> s.slug().equals(slug)).findFirst().orElse(null);
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

    /** Герой для сетки драфта: attr — str / agi / int / all. */
    public record HeroInfo(int id, String name, String attr) {
    }

    public List<HeroInfo> heroList() {
        return meta.heroes.values().stream()
                .sorted(java.util.Comparator.comparing(Meta.Hero::name))
                .map(h -> new HeroInfo(h.id(), h.name(), h.attr()))
                .toList();
    }
}
