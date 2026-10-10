package dota.fetch;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Выгрузка сырых данных OpenDota.
 * <pre>
 *   --refresh            перекачать уже скачанные файлы
 *   --config &lt;path&gt;      по умолчанию config/players.yaml
 *   --data &lt;dir&gt;         по умолчанию data
 *   --only &lt;account_id&gt;  только один игрок (справочники качаются всегда)
 * </pre>
 * Необязательный ключ API берётся из переменной окружения OPENDOTA_API_KEY.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        boolean refresh = false;
        Path configPath = Path.of("config/players.yaml");
        Path dataDir = Path.of("data");
        Long only = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--refresh" -> refresh = true;
                case "--config" -> configPath = Path.of(value(args, ++i));
                case "--data" -> dataDir = Path.of(value(args, ++i));
                case "--only" -> only = Long.parseLong(value(args, ++i));
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

        for (Endpoints.Target t : Endpoints.constants(raw.resolve("constants"))) {
            fetcher.fetch(t);
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

        Log.info("Done: downloaded %d, skipped %d, failed %d",
                fetcher.downloaded(), fetcher.skipped(), fetcher.failed());
        if (fetcher.failed() > 0) {
            System.exit(1);
        }
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Missing value for " + args[i - 1]);
        }
        return args[i];
    }
}
