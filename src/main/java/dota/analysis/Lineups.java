package dota.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * Состав на игру, выбранный на сайте: для команды (по имени из конфига) — позиция 1..5 → account_id. Хранится в
 * {@code state/lineups.json} (не в git: на сервере и локально свой). Нет записи для команды — состав по конфигу:
 * игроки без {@code reserve: true} на своих позициях.
 */
public final class Lineups {

    public static final Path DEFAULT_FILE = Path.of("state/lineups.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final Map<String, Map<Integer, Long>> byTeam;

    private Lineups(Path file, Map<String, Map<Integer, Long>> byTeam) {
        this.file = file;
        this.byTeam = byTeam;
    }

    /** Без изменений: везде состав по конфигу (бэктест, отчёты analysis/, тесты). */
    public static Lineups none() {
        return new Lineups(null, Map.of());
    }

    public static Lineups load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Lineups(file, new TreeMap<>());
        }
        Map<String, Map<Integer, Long>> data = JSON.readValue(file.toFile(), new TypeReference<>() {
        });
        return new Lineups(file, new TreeMap<>(data));
    }

    /** Состав команды или null, если не менялся. */
    public Map<Integer, Long> of(String team) {
        return byTeam.get(team);
    }

    /** Новый Lineups с изменённым (lineup != null) или сброшенным (null) составом команды, уже записанный в файл. */
    public Lineups with(String team, Map<Integer, Long> lineup) throws IOException {
        if (file == null) {
            throw new IllegalStateException("состав без файла не сохраняется");
        }
        Map<String, Map<Integer, Long>> next = new TreeMap<>(byTeam);
        if (lineup == null) {
            next.remove(team);
        } else {
            next.put(team, new TreeMap<>(lineup));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), next);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Lineups(file, next);
    }
}
