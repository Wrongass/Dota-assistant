package dota.fetch;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;

/**
 * Выгрузка сырых данных OpenDota.
 * <pre>
 *   --refresh              перекачать уже скачанные файлы
 *   --config &lt;path&gt;        по умолчанию config/players.yaml
 *   --data &lt;dir&gt;           по умолчанию data
 *   --only &lt;account_id&gt;    только один игрок (справочники и мета качаются всегда)
 *   --no-meta              не качать данные по текущему патчу (data/meta)
 *   --no-league            не качать матчи лиги из config (data/raw/league)
 *   --no-images            не качать картинки героев и рангов для сайта (data/img)
 *   --no-stratz            не качать линию и матчапы героев из STRATZ (data/raw/stratz)
 *   --meta-window &lt;n&gt;     сколько последних match_id брать для синергий/контрпиков, по умолчанию 1000000
 *   --meta-min-rank &lt;n&gt;   минимальный средний rank_tier матча, по умолчанию 50 (Legend 0)
 *   --league-players       ещё и игроки всех команд лиги не из конфига, облегчённо — для бэктеста драфта
 *                          (data/backtest/players); по умолчанию выключено
 * </pre>
 * Необязательный ключ API берётся из переменной окружения OPENDOTA_API_KEY. Токен STRATZ — из STRATZ_TOKEN
 * (переменная окружения или строка в .env); без него STRATZ пропускается.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        boolean refresh = false;
        boolean meta = true;
        boolean league = true;
        boolean images = true;
        boolean stratz = true;
        long metaWindow = 1_000_000;
        int metaMinRank = 50;
        Path configPath = Path.of("config/players.yaml");
        Path dataDir = Path.of("data");
        Long only = null;
        boolean leaguePlayers = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--refresh" -> refresh = true;
                case "--config" -> configPath = Path.of(value(args, ++i));
                case "--data" -> dataDir = Path.of(value(args, ++i));
                case "--only" -> only = Long.parseLong(value(args, ++i));
                case "--no-meta" -> meta = false;
                case "--no-league" -> league = false;
                case "--no-images" -> images = false;
                case "--no-stratz" -> stratz = false;
                case "--meta-window" -> metaWindow = Long.parseLong(value(args, ++i));
                case "--meta-min-rank" -> metaMinRank = Integer.parseInt(value(args, ++i));
                case "--league-players" -> leaguePlayers = true;
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }

        PlayerConfig config = PlayerConfig.load(configPath);
        OpenDotaClient client = new OpenDotaClient(
                OpenDotaClient.BASE_URL, System.getenv("OPENDOTA_API_KEY"), Duration.ofMillis(1100));
        RawFetcher fetcher = new RawFetcher(client, refresh);
        Path raw = dataDir.resolve("raw");
        Path constants = raw.resolve("constants");

        for (Endpoints.Target t : Endpoints.constants(constants)) {
            fetcher.fetch(t);
        }
        if (images) {
            new ImageFetcher().fetch(constants.resolve("heroes.json"), dataDir.resolve("img"));
        }

        for (PlayerConfig.Player p : config.players()) {
            if (only != null && p.accountId() != only) {
                continue;
            }
            Log.info("== %s (%d, %s)", p.nick(), p.accountId(), p.team().dir());
            Path dir = raw.resolve(p.team().dir()).resolve(String.valueOf(p.accountId()));
            for (Endpoints.Target t : Endpoints.player(p.accountId(), dir)) {
                fetcher.fetch(t);
            }
        }

        PlayerConfig.League l = config.league();
        if (league && l != null && l.id() != null) {
            LocalDate since = l.since() == null ? LocalDate.of(1970, 1, 1) : LocalDate.parse(l.since());
            new LeagueFetcher(client, fetcher, refresh).fetch(l.id(), since, raw.resolve("league").resolve(String.valueOf(l.id())));
        }

        if (leaguePlayers && l != null && l.id() != null) {
            fetchLeaguePlayers(config, fetcher, raw.resolve("league").resolve(String.valueOf(l.id())).resolve("matches"),
                    dataDir.resolve("backtest").resolve("players"));
        }

        if (meta) {
            fetchMeta(client, fetcher, constants.resolve("patch.json"), dataDir.resolve("meta"),
                    refresh, metaWindow, metaMinRank);
        }

        if (stratz) {
            String token = Env.get("STRATZ_TOKEN");
            if (token == null) {
                Log.info("STRATZ_TOKEN not set, skip STRATZ lanes");
            } else {
                new StratzFetcher(new StratzClient(StratzClient.URL, token, Duration.ofMillis(1100)), fetcher, refresh)
                        .fetch(constants.resolve("heroes.json"), raw.resolve("stratz"), java.time.Instant.now());
            }
        }

        Log.info("Done: downloaded %d, skipped %d, failed %d",
                fetcher.downloaded(), fetcher.skipped(), fetcher.failed());
        if (fetcher.failed() > 0) {
            System.exit(1);
        }
    }

    /** Игроки из скачанных матчей лиги, которых нет в конфиге: облегчённая выгрузка для бэктеста драфта. */
    private static void fetchLeaguePlayers(PlayerConfig config, RawFetcher fetcher, Path matchesDir, Path outDir)
            throws IOException, InterruptedException {
        java.util.Set<Long> known = new java.util.HashSet<>();
        config.players().forEach(p -> known.add(p.accountId()));
        java.util.Set<Long> ids = new java.util.TreeSet<>();
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var files = java.nio.file.Files.list(matchesDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                for (var p : json.readTree(f.toFile()).path("players")) {
                    long id = p.path("account_id").asLong(0);
                    if (id > 0 && !known.contains(id)) {
                        ids.add(id);
                    }
                }
            }
        }
        Log.info("== league players for backtest: %d -> %s", ids.size(), outDir);
        for (long id : ids) {
            for (Endpoints.Target t : Endpoints.backtestPlayer(id, outDir.resolve(String.valueOf(id)))) {
                fetcher.fetch(t);
            }
        }
    }

    private static void fetchMeta(OpenDotaClient client, RawFetcher fetcher, Path patchJson, Path metaDir,
                                  boolean refresh, long window, int minRank) throws InterruptedException {
        MetaEndpoints.Patch patch;
        try {
            patch = MetaEndpoints.currentPatch(patchJson);
        } catch (IOException e) {
            fetcher.recordFailure("meta: cannot read " + patchJson, e);
            return;
        }
        Path dir = metaDir.resolve(patch.name());
        Log.info("== meta for patch %s -> %s", patch.name(), dir);

        for (Endpoints.Target t : MetaEndpoints.staticTargets(dir)) {
            fetcher.fetch(t);
        }

        if (!refresh && MetaEndpoints.allExist(MetaEndpoints.explorerFiles(dir))) {
            Log.info("exists, skip: explorer sample in %s", dir);
            return;
        }
        try {
            long maxId = MetaEndpoints.parseMaxMatchId(client.get(MetaEndpoints.maxMatchIdUrl()));
            long minParsedId = MetaEndpoints.parseMinMatchId(
                    client.get(MetaEndpoints.minParsedMatchIdUrl(patch.start().getEpochSecond())));
            var sample = new MetaEndpoints.Sample(maxId, window, minRank, patch.start().getEpochSecond());
            for (Endpoints.Target t : MetaEndpoints.explorerTargets(dir, sample, minParsedId)) {
                fetcher.fetch(t);
            }
            MetaEndpoints.writeInfo(dir, patch, sample);
        } catch (IOException e) {
            fetcher.recordFailure("meta: explorer sample", e);
        }
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Missing value for " + args[i - 1]);
        }
        return args[i];
    }
}
