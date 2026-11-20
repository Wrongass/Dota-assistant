package dota.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Страница героя: кто его контрит на линии и по игре, кого контрит он, с кем его брать, с какими предметами он чаще
 * выигрывает на каждой позиции и какие предметы хороши или плохи против него. Плюс сводка «новые предметы патча — на
 * ком они хороши».
 * <ul>
 *   <li>Линия, игра, союзники и предметы — STRATZ ({@link LaneStats}, только недели текущего патча, Legend–Immortal);
 *       без STRATZ по игре и союзникам — выборка OpenDota из {@link Meta}.</li>
 *   <li>Предметы против героя — ручной справочник {@code config/item_counters.yaml}.</li>
 *   <li>Имена и иконки предметов — {@code data/patch/{версия}/itemlist*.json}, новые предметы —
 *       {@code analysis/patch/{версия}/patch.json} (их готовит tools/patch/build.py).</li>
 * </ul>
 */
public final class HeroGuide {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ICONS = "https://cdn.cloudflare.steamstatic.com/apps/dota2/images/dota_react/items/";
    /** Сколько героев показывать в каждом списке контрпиков и союзников. */
    static final int TOP = 10;
    /** Позиция героя попадает на страницу, если на ней столько его игр от всех. */
    static final double MIN_POS_SHARE = 0.10;

    public record Item(int id, String name, String nameRu, String icon) {
    }

    /** Совет из справочника: предмет, почему, и против какой особенности героя (группа). */
    public record ItemAdvice(Item item, String why, String theme) {
    }

    /**
     * Матчап героя heroId против другого. laneMatches = 0 — по линии данных нет (laneAdv NaN); pos — позиция героя
     * heroId на этой линии; gameAdv — преимущество по игре (доля), NaN — нет данных.
     */
    public record Matchup(int heroId, String name, int pos, int laneMatches, double laneAdv, double laneWin,
                          double laneLoss, double gameAdv, int games) {
    }

    public record Ally(int heroId, String name, double advantage, int games) {
    }

    public record PosShare(int pos, long games, double share, double winrate) {
    }

    /**
     * Предмет героя на позиции: покупок, винрейт с ним, ожидаемый по покупкам той же минуты, прибавка (сглаженная),
     * z — надёжность, minute — средняя минута покупки, fresh — предмет появился в этом патче.
     */
    public record ItemRow(Item item, long purchases, double winrate, double expected, double lift, double z,
                          double minute, boolean fresh) {
    }

    public record PositionItems(int pos, long games, double winrate, List<ItemRow> items) {
    }

    /** Отметка патча для героя: винрейт в первые дни нового патча против прошлого, ручной вердикт. */
    public record PatchNote(String version, double winrate, double winratePrev, int games, double delta, String verdict,
                            String comment) {
    }

    public record View(int heroId, String name, String attr, List<String> roles, String positions,
                       List<PosShare> positionShares, PatchNote patch,
                       List<Matchup> laneCounters, List<Matchup> gameCounters,
                       List<Matchup> laneVictims, List<Matchup> gameVictims,
                       List<ItemAdvice> goodItems, List<ItemAdvice> badItems,
                       List<PositionItems> items, List<Ally> allies, boolean hasStratz, String sample,
                       List<ItemAdvice> patchItems, List<InvItem> inventoryItems, String inventorySample) {
    }

    /**
     * Предмет по выборке финальных инвентарей (tools/items: матчи Legend+ текущего патча из STRATZ). lift — прибавка к
     * винрейту против того же героя в играх той же длины, собравшего другую готовую вещь той же цены; share — доля игр
     * героя с предметом (для общего списка 0); intermediate — входит в рецепт другого предмета.
     */
    public record InvItem(Item item, int games, double share, double winrate, double expected, double lift, double z,
                          int cost, boolean fresh, boolean intermediate) {
    }

    /** Новый предмет патча на герое и позиции. */
    public record NewItemHero(int heroId, String name, int pos, long purchases, double winrate, double lift, double z,
                              double minute) {
    }

    /**
     * Новый предмет: всего покупок в выборке и герои, отсортированные по прибавке; review — герои и комментарий из
     * ручного разбора патча (analysis/patch/{версия}/review.json), не статистика.
     */
    public record NewItem(Item item, long purchases, List<NewItemHero> best, List<NewItemHero> worst,
                          List<AnalysisContext.HeroInfo> reviewHeroes, String reviewComment, InvItem inventory) {
    }

    /**
     * noItemStats — в выгрузке STRATZ нет ни одной покупки новых предметов (STRATZ их ещё не знает); тогда герои у
     * новых предметов — из выборки инвентарей (pos = 0). strongItems / weakItems — готовые предметы по инвентарям.
     */
    public record Index(List<AnalysisContext.HeroInfo> heroes, List<NewItem> newItems, String sample, String patch,
                        boolean hasStratz, boolean noItemStats, List<InvItem> strongItems, List<InvItem> weakItems,
                        String inventorySample) {
    }

    private final AnalysisContext ctx;
    private final Map<Integer, Item> items;
    private final Set<Integer> newItems;
    /** Новый предмет → ручной разбор патча (review: verdict, heroes, comment). */
    private final Map<Integer, JsonNode> newItemReview = new HashMap<>();
    private final Map<Integer, JsonNode> patchHeroes;
    private final String patchVersion;
    /** hero → советы из справочника. */
    private final Map<Integer, List<ItemAdvice>> good = new HashMap<>(), bad = new HashMap<>();
    /** Выборка инвентарей analysis/items/{патч}/items.json или null. */
    private JsonNode inventory;
    private String inventorySample;
    /** Ошибки справочника: неизвестные герои и предметы. */
    private final List<String> problems = new ArrayList<>();

    private HeroGuide(AnalysisContext ctx, Map<Integer, Item> items, Set<Integer> newItems, Map<Integer, JsonNode> patchHeroes,
                      String patchVersion) {
        this.ctx = ctx;
        this.items = items;
        this.newItems = newItems;
        this.patchHeroes = patchHeroes;
        this.patchVersion = patchVersion;
    }

    /**
     * @param itemCounters config/item_counters.yaml (может не быть)
     * @param patchDir     analysis/patch — разбор патча с новыми предметами (может не быть)
     */
    public static HeroGuide load(AnalysisContext ctx, Path itemCounters, Path patchDir) throws IOException {
        return load(ctx, itemCounters, patchDir, patchDir == null ? null : patchDir.resolveSibling("items"));
    }

    /** @param itemsDir analysis/items — выборка инвентарей tools/items (может не быть) */
    public static HeroGuide load(AnalysisContext ctx, Path itemCounters, Path patchDir, Path itemsDir) throws IOException {
        Map<Integer, Item> items = catalog(ctx.dataDir.resolve("patch"));
        Set<Integer> fresh = new HashSet<>();
        Map<Integer, JsonNode> heroes = new HashMap<>();
        Map<Integer, JsonNode> reviews = new HashMap<>();
        Path patchFile = latest(patchDir, "patch.json");
        String version = null;
        if (patchFile != null) {
            JsonNode p = JSON.readTree(patchFile.toFile());
            version = p.path("version").asText(patchFile.getParent().getFileName().toString());
            for (JsonNode it : p.path("items")) {
                if ("new".equals(it.path("status").asText())) {
                    fresh.add(it.path("item").path("id").asInt());
                    reviews.put(it.path("item").path("id").asInt(), it.path("review"));
                }
            }
            p.path("heroes").forEach(h -> heroes.put(h.path("id").asInt(), h));
        }
        HeroGuide g = new HeroGuide(ctx, items, fresh, heroes, version);
        g.newItemReview.putAll(reviews);
        Path inv = latest(itemsDir, "items.json");
        // выборка инвентарей только своего патча: прошлый патч с другими предметами и ценами вводил бы в заблуждение
        if (inv != null && (version == null || inv.getParent().getFileName().toString().equals(version))) {
            g.inventory = JSON.readTree(inv.toFile());
            JsonNode smp = g.inventory.path("meta").path("sample");
            g.inventorySample = g.inventory.path("meta").path("matches").asInt() + " матчей Legend+ "
                    + ddmm(smp.path("patch_start").asText("")) + " — " + ddmm(smp.path("until").asText(""))
                    + ", финальные инвентари (STRATZ)";
        }
        if (itemCounters != null && Files.isRegularFile(itemCounters)) {
            g.readCounters(new ObjectMapper(new YAMLFactory()).readTree(itemCounters.toFile()));
        }
        return g;
    }

    /** Имена и иконки предметов последнего скачанного патча; пусто, если tools/patch/build.py --fetch не запускали. */
    static Map<Integer, Item> catalog(Path dir) throws IOException {
        Map<Integer, Item> result = new HashMap<>();
        Path en = latest(dir, "itemlist.json");
        if (en == null) {
            return result;
        }
        Map<Integer, String> ru = new HashMap<>();
        Path ruFile = en.resolveSibling("itemlist_ru.json");
        if (Files.exists(ruFile)) {
            JSON.readTree(ruFile.toFile()).path("result").path("data").path("itemabilities")
                    .forEach(i -> ru.put(i.path("id").asInt(), i.path("name_loc").asText()));
        }
        for (JsonNode i : JSON.readTree(en.toFile()).path("result").path("data").path("itemabilities")) {
            int id = i.path("id").asInt();
            String key = i.path("name").asText().replaceFirst("^item_", "");
            String name = i.path("name_english_loc").asText(key);
            result.put(id, new Item(id, name, ru.getOrDefault(id, name), ICONS + key + ".png"));
        }
        return result;
    }

    /** Файл name в папке самой новой версии внутри dir (папки вида 7.42, 7.42b). */
    private static Path latest(Path dir, String name) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> dirs = Files.list(dir)) {
            return dirs.filter(d -> Files.exists(d.resolve(name)))
                    .max(Comparator.comparing(d -> versionKey(d.getFileName().toString())))
                    .map(d -> d.resolve(name)).orElse(null);
        }
    }

    private static String versionKey(String v) {
        StringBuilder sb = new StringBuilder();
        for (String part : v.split("\\.")) {
            String digits = part.replaceAll("\\D.*", "");
            sb.append(String.format("%05d", digits.isEmpty() ? 0 : Integer.parseInt(digits)))
                    .append(part.replaceAll("^\\d*", "")).append('.');
        }
        return sb.toString();
    }

    private void readCounters(JsonNode root) {
        Map<String, Integer> heroByName = new HashMap<>();
        ctx.meta.heroes.values().forEach(h -> heroByName.put(h.name().toLowerCase(java.util.Locale.ROOT), h.id()));
        Map<String, Item> itemByName = new HashMap<>();
        items.values().forEach(i -> itemByName.put(i.name().toLowerCase(java.util.Locale.ROOT), i));
        for (JsonNode group : root) {
            String theme = group.path("why").asText("");
            List<Integer> heroes = new ArrayList<>();
            for (JsonNode h : group.path("heroes")) {
                Integer id = heroByName.get(h.asText().toLowerCase(java.util.Locale.ROOT));
                if (id == null) {
                    problems.add("герой «" + h.asText() + "»");
                } else {
                    heroes.add(id);
                }
            }
            for (String kind : List.of("good", "bad")) {
                for (JsonNode a : group.path(kind)) {
                    String name = a.path("item").asText();
                    Item item = itemByName.get(name.toLowerCase(java.util.Locale.ROOT));
                    if (item == null) {
                        if (!items.isEmpty()) {
                            problems.add("предмет «" + name + "»");
                        }
                        item = new Item(0, name, name, null);
                    }
                    ItemAdvice advice = new ItemAdvice(item, a.path("why").asText(""), theme);
                    for (int h : heroes) {
                        (kind.equals("good") ? good : bad).computeIfAbsent(h, k -> new ArrayList<>()).add(advice);
                    }
                }
            }
        }
    }

    /** Неизвестные герои и предметы в config/item_counters.yaml (пусто — всё в порядке). */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    Item item(int id) {
        return items.getOrDefault(id, new Item(id, "#" + id, "#" + id, null));
    }

    // ---------------------------------------------------------------- страница героя

    public View hero(int heroId) {
        Meta meta = ctx.meta;
        LaneStats lanes = ctx.lanes;
        Meta.Hero h = meta.heroes.get(heroId);
        if (h == null) {
            return null;
        }
        boolean stratz = !lanes.isEmpty();

        List<PosShare> shares = new ArrayList<>();
        long total = 0;
        for (int p = 1; p <= 5; p++) {
            total += lanes.positionGames(heroId, p)[0];
        }
        for (int p = 1; p <= 5; p++) {
            long[] g = lanes.positionGames(heroId, p);
            if (total > 0 && (double) g[0] / total >= MIN_POS_SHARE) {
                shares.add(new PosShare(p, g[0], (double) g[0] / total, (double) g[1] / g[0]));
            }
        }

        List<Matchup> against = new ArrayList<>(), victims = new ArrayList<>();
        for (int other : meta.heroes.keySet()) {
            if (other == heroId) {
                continue;
            }
            against.add(matchup(other, heroId, other));
            victims.add(matchup(heroId, other, other));
        }
        Comparator<Matchup> byLane = Comparator.comparingDouble(Matchup::laneAdv).reversed();
        Comparator<Matchup> byGame = Comparator.comparingDouble(Matchup::gameAdv).reversed();
        List<Matchup> laneCounters = against.stream().filter(m -> m.laneMatches() > 0).sorted(byLane).limit(TOP).toList();
        List<Matchup> gameCounters = against.stream().filter(m -> !Double.isNaN(m.gameAdv())).sorted(byGame).limit(TOP).toList();
        // кого он обыгрывает: его матчап против героя; имя — соперника
        List<Matchup> laneVictims = victims.stream().filter(m -> m.laneMatches() > 0).sorted(byLane).limit(TOP).toList();
        List<Matchup> gameVictims = victims.stream().filter(m -> !Double.isNaN(m.gameAdv())).sorted(byGame).limit(TOP).toList();

        List<Ally> allies = new ArrayList<>();
        for (int other : meta.heroes.keySet()) {
            if (other == heroId) {
                continue;
            }
            double adv;
            int games;
            if (stratz) {
                adv = lanes.allyAdvantage(heroId, other);
                games = lanes.allyGames(heroId, other);
            } else {
                adv = meta.synergyLift(heroId, other, Squad.MIN_PAIR_GAMES);
                Meta.PairStats s = meta.synergyStats(heroId, other);
                games = s == null ? 0 : (int) s.games();
            }
            if (!Double.isNaN(adv)) {
                allies.add(new Ally(other, meta.name(other), adv, games));
            }
        }
        allies.sort(Comparator.comparingDouble(Ally::advantage).reversed());

        List<PositionItems> byPos = new ArrayList<>();
        for (PosShare s : shares) {
            List<ItemRow> rows = lanes.items.lines(heroId, s.pos()).stream().map(this::row).toList();
            if (!rows.isEmpty()) {
                byPos.add(new PositionItems(s.pos(), s.games(), s.winrate(), rows));
            }
        }

        return new View(heroId, h.name(), h.attr(), h.roles(), meta.positions(heroId), shares, patchNote(heroId),
                laneCounters, gameCounters, laneVictims, gameVictims,
                good.getOrDefault(heroId, List.of()), bad.getOrDefault(heroId, List.of()),
                byPos, allies.stream().limit(TOP).toList(), stratz, lanes.sample, patchItems(heroId),
                inventoryOf(heroId), inventorySample);
    }

    /**
     * Как герой a играет против b: линия — a на своей позиции против b (позиция, где они чаще встречались), по игре —
     * преимущество a над b (STRATZ, без него — OpenDota). shown — герой, которого показываем в строке.
     */
    private Matchup matchup(int a, int b, int shown) {
        LaneStats lanes = ctx.lanes;
        LaneStats.Outcome best = null;
        int bestPos = 0;
        for (int p = 1; p <= 5; p++) {
            LaneStats.Outcome o = lanes.lane(a, p, List.of(b));
            if (o != null && (best == null || o.matches() > best.matches())) {
                best = o;
                bestPos = p;
            }
        }
        double game = lanes.gameAdvantage(a, List.of(b));
        int games = lanes.gameMatches(a, List.of(b));
        if (Double.isNaN(game) && lanes.isEmpty()) {
            game = ctx.meta.counterAdvantage(a, b, Squad.MIN_PAIR_GAMES);
            Meta.PairStats s = ctx.meta.counters.get(new Meta.Pair(a, b));
            games = s == null ? 0 : (int) s.games();
        }
        String name = ctx.meta.name(shown);
        return best == null
                ? new Matchup(shown, name, 0, 0, Double.NaN, Double.NaN, Double.NaN, game, games)
                : new Matchup(shown, name, bestPos, best.matches(), best.advantage(), best.win(), best.loss(), game, games);
    }

    /** Новые предметы патча, которые ручной разбор патча советует этому герою. */
    private List<ItemAdvice> patchItems(int heroId) {
        List<ItemAdvice> result = new ArrayList<>();
        newItemReview.forEach((id, review) -> {
            for (JsonNode h : review.path("heroes")) {
                if (h.asInt() == heroId) {
                    result.add(new ItemAdvice(item(id), review.path("comment").asText(""), "разбор патча"));
                }
            }
        });
        result.sort(Comparator.comparing(a -> a.item().name()));
        return result;
    }

    private ItemRow row(HeroItems.Line l) {
        return new ItemRow(item(l.itemId()), l.purchases(), l.winrate(), l.expected(), l.lift(), l.z(), l.minute(),
                newItems.contains(l.itemId()));
    }

    private PatchNote patchNote(int heroId) {
        JsonNode h = patchHeroes.get(heroId);
        if (h == null) {
            return null;
        }
        JsonNode s = h.path("stats");
        return new PatchNote(patchVersion, s.path("wr").asDouble(Double.NaN), s.path("wr_prev").asDouble(Double.NaN),
                s.path("games").asInt(0), s.path("delta").asDouble(Double.NaN),
                h.path("review").path("verdict").asText(null), h.path("review").path("comment").asText(null));
    }

    // ---------------------------------------------------------------- выборка инвентарей

    private static String ddmm(String iso) {
        return iso.length() >= 10 ? iso.substring(8, 10) + "." + iso.substring(5, 7) : iso;
    }

    private InvItem inv(JsonNode x, JsonNode row, double share) {
        int id = x.path("id").asInt();
        return new InvItem(item(id), row.path("games").asInt(), share, row.path("winrate").asDouble(),
                row.path("expected").asDouble(), row.path("lift").asDouble(), row.path("z").asDouble(),
                x.path("cost").asInt(), x.path("new").asBoolean(), x.path("intermediate").asBoolean());
    }

    /** Предметы героя по выборке инвентарей (от min_hero_item игр), лучшие первыми. */
    private List<InvItem> inventoryOf(int heroId) {
        List<InvItem> result = new ArrayList<>();
        if (inventory == null) {
            return result;
        }
        for (JsonNode x : inventory.path("items")) {
            for (JsonNode h : x.path("heroes")) {
                if (h.path("hero").asInt() == heroId) {
                    result.add(inv(x, h, h.path("share").asDouble()));
                }
            }
        }
        result.sort(Comparator.comparingDouble(InvItem::lift).reversed());
        return result;
    }

    private InvItem inventoryItem(int itemId) {
        if (inventory == null) {
            return null;
        }
        for (JsonNode x : inventory.path("items")) {
            if (x.path("id").asInt() == itemId) {
                return inv(x, x, 0);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- список героев и новые предметы

    public Index index() {
        LaneStats lanes = ctx.lanes;
        Map<Integer, List<NewItemHero>> uses = new LinkedHashMap<>();
        newItems.stream().sorted(Comparator.comparing(id -> item(id).name())).forEach(id -> uses.put(id, new ArrayList<>()));
        Map<Integer, Long> purchases = new HashMap<>();
        for (int hero : lanes.items.heroes()) {
            for (int pos = 1; pos <= 5; pos++) {
                for (HeroItems.Line l : lanes.items.lines(hero, pos)) {
                    if (uses.containsKey(l.itemId())) {
                        purchases.merge(l.itemId(), l.purchases(), Long::sum);
                        if (!Double.isNaN(l.lift())) {
                            uses.get(l.itemId()).add(new NewItemHero(hero, ctx.meta.name(hero), pos, l.purchases(),
                                    l.winrate(), l.lift(), l.z(), l.minute()));
                        }
                    }
                }
            }
        }
        List<NewItem> result = new ArrayList<>();
        uses.forEach((id, list) -> {
            list.sort(Comparator.comparingDouble(NewItemHero::lift).reversed());
            List<NewItemHero> best = list.stream().filter(u -> u.lift() > 0).limit(8).toList();
            List<NewItemHero> worst = list.stream().filter(u -> u.lift() < 0)
                    .sorted(Comparator.comparingDouble(NewItemHero::lift)).limit(4).toList();
            JsonNode review = newItemReview.getOrDefault(id, JSON.createObjectNode());
            List<AnalysisContext.HeroInfo> reviewHeroes = new ArrayList<>();
            review.path("heroes").forEach(h -> ctx.heroList().stream().filter(x -> x.id() == h.asInt()).findFirst()
                    .ifPresent(reviewHeroes::add));
            result.add(new NewItem(item(id), purchases.getOrDefault(id, 0L), best, worst, reviewHeroes,
                    review.path("comment").asText(null), inventoryItem(id)));
        });
        boolean noStats = !lanes.items.isEmpty() && purchases.values().stream().mapToLong(Long::longValue).sum() == 0;
        if (noStats && inventory != null) {
            // STRATZ не знает новые предметы — герои из выборки инвентарей (позиция и минута неизвестны)
            for (int i = 0; i < result.size(); i++) {
                NewItem n = result.get(i);
                List<NewItemHero> list = new ArrayList<>();
                for (JsonNode x : inventory.path("items")) {
                    if (x.path("id").asInt() == n.item().id()) {
                        for (JsonNode h : x.path("heroes")) {
                            list.add(new NewItemHero(h.path("hero").asInt(), h.path("name").asText(), 0,
                                    h.path("games").asLong(), h.path("winrate").asDouble(), h.path("lift").asDouble(),
                                    h.path("z").asDouble(), Double.NaN));
                        }
                    }
                }
                list.sort(Comparator.comparingDouble(NewItemHero::lift).reversed());
                result.set(i, new NewItem(n.item(), n.inventory() == null ? 0 : n.inventory().games(),
                        list.stream().filter(u -> u.lift() > 0).limit(8).toList(),
                        list.stream().filter(u -> u.lift() < 0).sorted(Comparator.comparingDouble(NewItemHero::lift))
                                .limit(4).toList(), n.reviewHeroes(), n.reviewComment(), n.inventory()));
            }
        }
        result.sort(Comparator.comparingLong(NewItem::purchases).reversed());
        List<InvItem> all = new ArrayList<>();
        if (inventory != null) {
            int min = inventory.path("meta").path("min_item").asInt(150);
            for (JsonNode x : inventory.path("items")) {
                InvItem it = inv(x, x, 0);
                if (it.games() >= min && !it.intermediate()) {
                    all.add(it);
                }
            }
        }
        all.sort(Comparator.comparingDouble(InvItem::lift).reversed());
        List<InvItem> strong = all.stream().filter(x -> x.lift() > 0).limit(15).toList();
        List<InvItem> weak = all.stream().filter(x -> x.lift() < 0).sorted(Comparator.comparingDouble(InvItem::lift))
                .limit(8).toList();
        return new Index(ctx.heroList(), result, lanes.sample, patchVersion, !lanes.isEmpty(), noStats, strong, weak,
                inventorySample);
    }
}
