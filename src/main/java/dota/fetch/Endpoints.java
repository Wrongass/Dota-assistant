package dota.fetch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Какие эндпоинты качаем и куда кладём. */
final class Endpoints {

    /** Один запрос → один файл. */
    record Target(String url, Path file) {
    }

    /**
     * Поля для /players/{id}/matches?project=...
     * В документации список не приведён ("Fields to project (array)"). Получен перебором ~180 кандидатов
     * (поля match/player из OpenAPI + колонки player_matches из /schema): неизвестные поля API молча
     * отбрасывает, эти вернулись. match_id, player_slot, radiant_win приходят всегда.
     * Не поддерживаются: patch, region, net_worth, backpack_*, item_neutral2, aghanims_*, teamfight_participation.
     */
    static final List<String> MATCH_FIELDS = List.of(
            // матч
            "start_time", "duration", "game_mode", "lobby_type", "cluster", "version",
            "skill", "average_rank", "party_size", "leaver_status",
            // герой и роль
            "hero_id", "hero_variant", "lane", "lane_role", "is_roaming", "heroes",
            // результат
            "kills", "deaths", "assists", "kda", "last_hits", "denies", "level",
            "gold_per_min", "xp_per_min", "hero_damage", "tower_damage", "hero_healing", "stuns",
            "neutral_kills", "tower_kills", "courier_kills", "lane_efficiency_pct", "actions_per_min",
            "kill_streaks", "multi_kills", "pings",
            // предметы
            "item_0", "item_1", "item_2", "item_3", "item_4", "item_5", "item_neutral",
            "purchase", "purchase_time", "purchase_tpscroll", "item_uses", "item_win", "item_usage",
            // варды
            "obs", "sen",
            "account_id");

    /** Справочники: data/raw/constants/{name}.json */
    static final List<String> CONSTANTS = List.of("heroes", "game_mode", "lobby_type", "patch");

    private Endpoints() {
    }

    static String matchesQuery() {
        return "significant=0&" + MATCH_FIELDS.stream()
                .map(f -> "project=" + f)
                .collect(Collectors.joining("&"));
    }

    /**
     * Агрегаты (heroes, peers, counts, totals) — с параметрами по умолчанию (significant=1: только
     * стандартные режимы, включая Captains Mode). Сырые matches и wl — significant=0, чтобы wl
     * совпадал с числом матчей в matches.json.
     */
    static List<Target> player(long accountId, Path dir) {
        String base = "/players/" + accountId;
        List<Target> targets = new ArrayList<>();
        targets.add(new Target(base, dir.resolve("profile.json")));
        targets.add(new Target(base + "/wl?significant=0", dir.resolve("wl.json")));
        targets.add(new Target(base + "/matches?" + matchesQuery(), dir.resolve("matches.json")));
        targets.add(new Target(base + "/heroes", dir.resolve("heroes.json")));
        targets.add(new Target(base + "/peers", dir.resolve("peers.json")));
        targets.add(new Target(base + "/counts", dir.resolve("counts.json")));
        targets.add(new Target(base + "/totals", dir.resolve("totals.json")));
        targets.add(new Target(base + "/ratings", dir.resolve("ratings.json")));
        targets.add(new Target(base + "/rankings", dir.resolve("rankings.json")));
        return targets;
    }

    static List<Target> constants(Path dir) {
        return CONSTANTS.stream()
                .map(name -> new Target("/constants/" + name, dir.resolve(name + ".json")))
                .toList();
    }
}
