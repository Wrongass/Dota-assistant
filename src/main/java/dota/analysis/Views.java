package dota.analysis;

import java.util.List;

/**
 * Данные отчётов без форматирования. Их строит {@link Squad}, а показывают {@link MarkdownRenderer} и сайт.
 * Доли — числа 0..1, NaN — «нет данных».
 */
public final class Views {

    /**
     * Игрок в редакторе состава. position — позиция в составе на игру (0 — запасной), configPosition — позиция в
     * конфиге, maps — карт за эту команду в лиге сезона.
     */
    public record LineupPlayer(long accountId, String nick, int rankTier, int position, int configPosition,
                               boolean reserve, int maps, String note) {
    }

    /** Состав команды: team — имя из конфига (ключ в state/lineups.json), changed — выбран на сайте. */
    public record Lineup(String team, String teamName, boolean changed, List<LineupPlayer> players) {
    }

    private Views() {
    }

    // ---------------------------------------------------------------- общие

    /** Герой и число (винрейт, оценка, преимущество — по контексту). */
    public record HeroValue(int heroId, String name, double value) {
    }

    /** Счётчик по герою: сколько раз и сколько из них побед (0, если не считается). */
    public record Count(int heroId, String name, int count, int wins) {
    }

    /** Герой в пуле игрока: пригодность и признак «сильный в патче». */
    public record PoolEntry(int heroId, String name, double fit, boolean strong) {
    }

    public record PlayerRef(int pos, String nick, String file, long accountId) {
    }

    // ---------------------------------------------------------------- состав

    public record RosterRow(int pos, boolean inferred, String nick, String file, long accountId, String personaName,
                            int rankTier, double mmr, int totalGames, int yearGames, int patchGames, int patchWins,
                            int cmGames, int cmWins, String note) {
    }

    /** share[0..4] — позиции 1..5, lanes[0..3] — safe/mid/off/лес. */
    public record PosShareRow(String nick, int pos, double[] share, int[] lanes) {
    }

    /** together[i][j] / togetherWins[i][j] — матчи игроков i и j в одной команде; на диагонали -1. */
    public record Roster(String teamName, String today, String patch, List<RosterRow> rows, boolean anyInferred,
                         List<PosShareRow> shares, List<String> nicks, int[][] together, int[][] togetherWins) {
    }

    // ---------------------------------------------------------------- игрок

    public record BestHero(int heroId, String name, double skill, int games, int yearGames, int patchGames,
                           double winrate, double yearWinrate, Double percentRank, double kda, String positions,
                           double metaWinrate) {
    }

    public record FitRow(int heroId, String name, double fit, double meta, double winrate, double posWinrate,
                         double proContest, double skill, int games, String mark) {
    }

    public record PlayerCard(int pos, String nick, String personaName, long accountId, int rankTier, double mmr,
                             int totalGames, int yearGames, String patch, int patchGames, int patchWins, int cmGames,
                             String note, String rangeName, List<BestHero> best, List<FitRow> fits,
                             List<HeroValue> strong, List<HeroValue> weak, List<HeroValue> weakInPool,
                             Form form) {
    }

    /**
     * Чем игрок играет в последнее время. Окна — от даты отчёта: 14 и 60 дней. Игры и победы — без Турбо,
     * Турбо отдельной цифрой. «С командой» — игры, где на его стороне был хотя бы один игрок его команды из конфига.
     */
    public record Form(String asOf, int games14, int games60, int turbo60, int team60, List<HeroForm> heroes,
                       List<RecentMatch> matches) {
    }

    /** signature — герой из его топа «играет лучше всего»; fresh — много игр за 14 дней, но в топе его нет. */
    public record HeroForm(int heroId, String name, int games14, int wins14, int games60, int wins60, int turbo60,
                           int team60, List<String> mates, double kda60, String lastPlayed, boolean signature,
                           boolean fresh) {
    }

    public record RecentMatch(long matchId, String date, int heroId, String hero, boolean win, String kda,
                              int partySize, List<String> mates, boolean turbo, String mode) {
    }

    // ---------------------------------------------------------------- мета

    public record MetaRow(int heroId, String name, double strength, double winrate, double pickRate,
                          double posWinrate, double proContest) {
    }

    public record PositionMeta(int pos, List<MetaRow> top, List<HeroValue> weak) {
    }

    public record ProRow(int heroId, String name, double contest, long picks, long bans, double proWinrate,
                         String positions) {
    }

    /** Пара героев: value — синергия или преимущество a над b. */
    public record PairRow(int a, String aName, int b, String bName, double value, double winrate, long games) {
    }

    public record MetaView(String patch, String today, String rangeName, long proMatches,
                           List<PositionMeta> positions, List<ProRow> pro, List<PairRow> synergies,
                           List<PairRow> counters) {
    }

    // ---------------------------------------------------------------- данные для драфта

    public record Pool(int pos, String nick, List<PoolEntry> heroes) {
    }

    public record ComboRow(PairRow pair, String players) {
    }

    public record VsRow(int heroId, String name, double vsWinrate, int games, double pubWinrate) {
    }

    /** vsWinrate = NaN, если против героя не играли. */
    public record BanRow(int heroId, String name, double score, double vsWinrate, int vsGames, double pubWinrate,
                         double proContest, String positions) {
    }

    public record CounterThreat(String nick, int heroId, String name, List<HeroValue> threats) {
    }

    public record DraftData(String teamName, String today, String patch, String rangeName, List<Pool> pools,
                            List<ComboRow> combos, double avgVs, List<VsRow> nemesis, List<HeroValue> bestAgainst,
                            List<BanRow> bans, List<CounterThreat> threats) {
    }

    // ---------------------------------------------------------------- турнир

    public record MatchPlayer(String nick, int heroId, String heroName, int laneRole, int gpm,
                              int kills, int deaths, int assists) {
    }

    public record Step(boolean pick, boolean ours, int heroId, String heroName) {
    }

    public record MatchView(long matchId, String date, String opponent, boolean won, boolean radiant, int minutes,
                            List<MatchPlayer> players, List<MatchPlayer> enemies, List<Step> draft) {
    }

    public record Tournament(String teamName, long leagueId, long wins, boolean hadRemakes, List<MatchView> matches,
                             List<Count> picks, List<Count> bans, List<Count> bansAgainst, List<Count> enemyPicks) {
    }

    // ---------------------------------------------------------------- соперник

    public record OppPool(int pos, String nick, String file, int rankTier, List<PoolEntry> heroes,
                          List<Count> tourney) {
    }

    public record ThreatRow(int heroId, String name, String nick, double score, double skill, double meta,
                            int games, int yearGames, int yearWins, int tourneyPicks, String positions) {
    }

    public record Answer(int heroId, String name, double advantage, List<String> players) {
    }

    public record CounterRow(int heroId, String name, String nick, List<Answer> answers) {
    }

    public record LossRow(int heroId, String name, double winrate, int games, boolean inOurPool) {
    }

    public record Threats(String teamName, String today, String patch, List<OppPool> pools, List<ThreatRow> threats,
                          List<Count> bans, List<String> bannedFromOurPool, List<CounterRow> counters, double avg,
                          List<LossRow> losses) {
    }

    // ---------------------------------------------------------------- группа

    public record Standing(String team, long games, long wins) {
    }

    /** matchId 0 — карта из manual_maps: её нет в OpenDota, известен только результат (note — откуда). */
    public record GroupMatch(long matchId, String date, String radiant, String dire, String winner, int minutes,
                             List<String> radiantHeroes, List<String> direHeroes, String note) {
    }

    public record Group(String name, long leagueId, int remakes, List<Standing> standings, List<GroupMatch> matches) {
    }
}
