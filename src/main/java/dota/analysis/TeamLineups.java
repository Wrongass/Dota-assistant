package dota.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Составы команды в картах турнира: кто на какой позиции. Нужны, когда состав соперника до игры неизвестен (есть
 * замена, игроки меняются позициями): драфт готовит ко всем составам сразу (по запросу пользователя 2.11).
 */
final class TeamLineups {

    private TeamLineups() {
    }

    /** Состав «позиция → account_id», сколько карт им сыграно и когда последняя (unix-время). */
    record Seen(Map<Integer, Long> lineup, int maps, long lastTime) {
    }

    /**
     * Разные составы команды teamId в матчах лиги, сначала самый свежий. Карты, где позиции не определить (нет
     * разбора линий) или играл кто-то не из конфига команды (known), пропускаются.
     */
    static List<Seen> seen(List<League.Match> matches, long teamId, Set<Long> known) {
        Map<Map<Integer, Long>, Seen> result = new LinkedHashMap<>();
        for (League.Match m : matches) {
            if (!m.involves(teamId)) {
                continue;
            }
            Map<Integer, Long> lineup = positions(m.side(m.isRadiant(teamId)));
            if (lineup == null || !known.containsAll(lineup.values())) {
                continue;
            }
            Seen old = result.get(lineup);
            result.put(lineup, new Seen(lineup, old == null ? 1 : old.maps() + 1,
                    Math.max(old == null ? 0 : old.lastTime(), m.startTime())));
        }
        List<Seen> list = new ArrayList<>(result.values());
        list.sort(Comparator.comparingLong(Seen::lastTime).reversed());
        return list;
    }

    /**
     * Позиции по линиям матча: мид — линия 2; на лёгкой (1) больше золота у керри (1), меньше у пятёрки (5); на сложной
     * (3) больше у оффлейнера (3), меньше у четвёрки (4). Другая раскладка (лес, линия не определена) — null.
     */
    static Map<Integer, Long> positions(List<League.Player> side) {
        if (side.size() != 5) {
            return null;
        }
        List<League.Player> safe = new ArrayList<>(), mid = new ArrayList<>(), off = new ArrayList<>();
        for (League.Player p : side) {
            if (p.accountId() <= 0) {
                return null;
            }
            switch (p.laneRole()) {
                case 1 -> safe.add(p);
                case 2 -> mid.add(p);
                case 3 -> off.add(p);
                default -> {
                    return null;
                }
            }
        }
        if (mid.size() != 1 || safe.size() != 2 || off.size() != 2) {
            return null;
        }
        Comparator<League.Player> byGold = Comparator.comparingInt(League.Player::gpm).reversed();
        safe.sort(byGold);
        off.sort(byGold);
        Map<Integer, Long> lineup = new TreeMap<>();
        lineup.put(1, safe.get(0).accountId());
        lineup.put(2, mid.get(0).accountId());
        lineup.put(3, off.get(0).accountId());
        lineup.put(4, off.get(1).accountId());
        lineup.put(5, safe.get(1).accountId());
        return lineup;
    }
}
