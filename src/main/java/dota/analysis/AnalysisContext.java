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

    private AnalysisContext(PlayerConfig config, Meta meta, League league, LaneStats lanes, Squad ours,
                            List<Squad> opponents, LocalDate date, Path dataDir) {
        this.config = config;
        this.meta = meta;
        this.league = league;
        this.lanes = lanes;
        this.ours = ours;
        this.opponents = opponents;
        this.date = date;
        this.dataDir = dataDir;
    }

    public static AnalysisContext load(Path configPath, Path dataDir, LocalDate date) throws IOException {
        PlayerConfig config = PlayerConfig.load(configPath);
        Meta meta = Meta.load(dataDir);
        League league = config.league() == null || config.league().id() == null ? null
                : League.load(dataDir, config.league());
        // опорный момент — полночь UTC даты отчёта: вывод воспроизводим (веса по давности не «плывут»)
        Instant now = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        String today = date.toString();

        Squad ours = new Squad(meta, loadPlayers(dataDir, config, null, meta, now), config.ourTeam(), true, league, today);
        List<Squad> opponents = new ArrayList<>();
        if (config.league() != null) {
            for (PlayerConfig.TeamInfo t : config.league().opponentsOrEmpty()) {
                List<PlayerStats> squad = loadPlayers(dataDir, config, t.name(), meta, now);
                if (!squad.isEmpty()) {
                    opponents.add(new Squad(meta, squad, t, false, league, today));
                }
            }
        }
        return new AnalysisContext(config, meta, league, LaneStats.load(dataDir), ours, opponents, date, dataDir);
    }

    public static AnalysisContext load(Path configPath, Path dataDir) throws IOException {
        return load(configPath, dataDir, LocalDate.now(ZoneOffset.UTC));
    }

    /** teamName == null — наши игроки, иначе соперники с этим team_name. */
    private static List<PlayerStats> loadPlayers(Path dataDir, PlayerConfig config, String teamName, Meta meta,
                                                 Instant now) throws IOException {
        List<PlayerConfig.Player> squad = config.players().stream()
                .filter(p -> teamName == null ? p.team() == PlayerConfig.Team.OUR
                        : p.team() == PlayerConfig.Team.OPP && teamName.equals(p.teamName()))
                .toList();
        Set<Long> ids = squad.stream().map(PlayerConfig.Player::accountId).collect(Collectors.toSet());
        List<PlayerStats> players = new ArrayList<>();
        for (PlayerConfig.Player p : squad) {
            players.add(PlayerStats.load(dataDir, p, ids, meta.patchStart, now));
        }
        return players;
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

    /** Команда, в которой играет account_id, или null. */
    public Squad squadOf(long accountId) {
        if (ours.player(accountId) != null) {
            return ours;
        }
        return opponents.stream().filter(s -> s.player(accountId) != null).findFirst().orElse(null);
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
