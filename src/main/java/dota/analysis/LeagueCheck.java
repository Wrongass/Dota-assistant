package dota.analysis;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Проверка карт лиги перед моделью (тест на противоречивых данных, docs/research/contradictory-data.md).
 * <ul>
 *   <li>{@link #problem} — карта не может быть настоящей: дубль, не 5 на 5, неизвестный или повторный герой, игрок за
 *   обе стороны, команда против себя, дата в будущем, нулевая длительность, герой и в бане, и в пике;</li>
 *   <li>{@link #clean} — карта правдоподобна по форме, но противоречит всему, что мы знаем об игроках:
 *   3+ игрока команды на героях, на которых у них меньше 3 игр в пабах. В настоящих картах лиги 96% пиков — герои с 3+
 *   играми, такой карты нет ни одной. Плюс предупреждение, если у команды больше 6 карт за день.</li>
 * </ul>
 */
final class LeagueCheck {

    /** Сколько игр в пабах на герое нужно, чтобы пик считался «из пула» игрока. */
    static final int POOL_GAMES = 3;
    /** Со скольких пиков не из пула карта считается неправдоподобной. */
    static final int OFF_POOL_LIMIT = 3;
    /** Больше карт за день у одной команды не бывает (BO3 в день, иногда две серии). */
    static final int MAPS_PER_DAY = 6;

    record Report(List<League.Match> removed, List<String> warnings) {
    }

    private LeagueCheck() {
    }

    /** Причина, по которой карта не может быть настоящей, или null. knownIds — уже принятые match_id. */
    static String problem(League.Match m, Set<Integer> heroIds, Set<Long> knownIds) {
        if (knownIds.contains(m.matchId())) {
            return "дубль match_id";
        }
        if (m.players().size() != 10 || m.side(true).size() != 5) {
            return "не 5 на 5";
        }
        if (m.radiantTeamId() != 0 && m.radiantTeamId() == m.direTeamId()) {
            return "команда против самой себя";
        }
        if (m.startTime() > Instant.now().getEpochSecond() + 86_400) {
            return "дата в будущем";
        }
        if (m.durationSec() <= 0) {
            return "длительность не больше нуля";
        }
        Set<Integer> heroes = new HashSet<>();
        Set<Long> accounts = new HashSet<>();
        for (League.Player p : m.players()) {
            if (!heroIds.contains(p.heroId())) {
                return "неизвестный герой " + p.heroId();
            }
            if (!heroes.add(p.heroId())) {
                return "герой дважды";
            }
            if (p.accountId() > 0 && !accounts.add(p.accountId())) {
                return "игрок за обе стороны";
            }
        }
        Set<Integer> drafted = new HashSet<>();
        for (League.DraftStep s : m.draft()) {
            if (!drafted.add(s.heroId())) {
                return "герой в драфте дважды (бан и пик)";
            }
            if (!s.pick() && heroes.contains(s.heroId())) {
                return "забаненный герой в игре";
            }
        }
        return null;
    }

    /**
     * Чистит карты лиги перед моделью: убирает невозможные ({@link #problem}) и неправдоподобные — где 3+ известных
     * игрока команды из конфига играют не своих героев. Возвращает убранные карты и предупреждения для сайта (в том
     * числе о слишком большом числе карт за день: такие карты не убираются, по одному числу не понять, какие настоящие).
     */
    static Report clean(League league, Set<Integer> heroIds, List<Squad> squads) {
        List<League.Match> removed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> reasons = new java.util.TreeMap<>();
        Set<Long> ids = new HashSet<>();
        for (League.Match m : league.matches) {
            String p = problem(m, heroIds, ids);
            if (p != null) {
                removed.add(m);
                reasons.merge(p.startsWith("неизвестный герой") ? "неизвестный герой" : p, 1, Integer::sum);
                continue;
            }
            ids.add(m.matchId());
            if (offPool(m, squads)) {
                removed.add(m);
                reasons.merge("3+ игрока на чужих героях", 1, Integer::sum);
            }
        }
        league.matches.removeAll(removed);
        if (!removed.isEmpty()) {
            warnings.add("Карт лиги отброшено проверкой данных: " + removed.size() + " (" + String.join(", ",
                    reasons.entrySet().stream().map(e -> e.getKey() + " — " + e.getValue()).toList()) + ")");
        }
        Map<String, Integer> perDay = new HashMap<>();
        for (League.Match m : league.matches) {
            String day = Instant.ofEpochSecond(m.startTime()).atZone(ZoneOffset.UTC).toLocalDate().toString();
            for (Squad s : squads) {
                if (s.team != null && s.team.teamId() != null && m.involves(s.team.teamId())) {
                    perDay.merge(s.teamName() + " " + day, 1, Integer::sum);
                }
            }
        }
        List<String> busy = perDay.entrySet().stream().filter(e -> e.getValue() > MAPS_PER_DAY)
                .map(e -> e.getKey() + " — " + e.getValue()).sorted().toList();
        if (!busy.isEmpty()) {
            warnings.add("Слишком много карт лиги за день (больше " + MAPS_PER_DAY + "), данные под подозрением: "
                    + busy.size() + " дн., например " + busy.get(0));
        }
        return new Report(removed, warnings);
    }

    /** 3+ известных игрока одной из команд конфига на героях, где у них меньше POOL_GAMES игр в пабах. */
    static boolean offPool(League.Match m, List<Squad> squads) {
        for (Squad s : squads) {
            if (s.team == null || s.team.teamId() == null || !m.involves(s.team.teamId())) {
                continue;
            }
            int known = 0, off = 0;
            for (League.Player p : m.side(m.isRadiant(s.team.teamId()))) {
                PlayerStats ps = s.player(p.accountId());
                if (ps == null) {
                    continue;
                }
                known++;
                PlayerStats.HeroLine h = ps.heroes.get(p.heroId());
                off += h == null || h.games < POOL_GAMES ? 1 : 0;
            }
            if (known >= OFF_POOL_LIMIT && off >= OFF_POOL_LIMIT) {
                return true;
            }
        }
        return false;
    }
}
