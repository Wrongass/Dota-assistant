package dota.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Свойства героев из {@code config/hero_traits.yaml}: пуш, Рошан, файт, сплит, кайт, ловля, AoE против иллюзий,
 * быстрый урон, иллюзии, поздняя игра и «неудобство» (каких ответов требует герой). Ручной справочник для симуляции
 * драфта и оценки (docs/specs/draft-simulation.md). Нет файла — все свойства нулевые.
 */
public final class HeroTraits {

    public static final List<String> NAMES = List.of("push", "roshan", "teamfight", "split", "kite", "catch",
            "anti_illusion", "burst", "illusions");
    /** Ответы, которые может требовать неудобный герой. */
    public static final Set<String> ANSWERS = Set.of("anti_illusion", "catch", "burst");

    public record Traits(String name, Map<String, Integer> values, int late, List<String> awkward) {
        public int get(String trait) {
            return values.getOrDefault(trait, 0);
        }
    }

    private static final Traits NONE = new Traits("", Map.of(), 0, List.of());
    private final Map<Integer, Traits> byHero;

    private HeroTraits(Map<Integer, Traits> byHero) {
        this.byHero = byHero;
    }

    public static HeroTraits empty() {
        return new HeroTraits(Map.of());
    }

    public static HeroTraits load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return empty();
        }
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(file.toFile());
        Map<Integer, Traits> result = new HashMap<>();
        root.fields().forEachRemaining(e -> {
            JsonNode n = e.getValue();
            Map<String, Integer> values = new HashMap<>();
            for (String t : NAMES) {
                int v = n.path(t).asInt(0);
                if (v < 0 || v > 2) {
                    throw new IllegalArgumentException(file + ": " + e.getKey() + "." + t + " должно быть 0–2");
                }
                if (v > 0) {
                    values.put(t, v);
                }
            }
            n.fieldNames().forEachRemaining(k -> {
                if (!NAMES.contains(k) && !Set.of("name", "late", "awkward").contains(k)) {
                    throw new IllegalArgumentException(file + ": неизвестное свойство " + k + " у героя " + e.getKey());
                }
            });
            List<String> awkward = new ArrayList<>();
            n.path("awkward").forEach(a -> {
                if (!ANSWERS.contains(a.asText())) {
                    throw new IllegalArgumentException(file + ": awkward " + a.asText() + " у героя " + e.getKey());
                }
                awkward.add(a.asText());
            });
            int late = n.path("late").asInt(0);
            if (late < -2 || late > 2) {
                throw new IllegalArgumentException(file + ": " + e.getKey() + ".late должно быть −2…2");
            }
            result.put(Integer.parseInt(e.getKey()), new Traits(n.path("name").asText(""), Map.copyOf(values), late,
                    List.copyOf(awkward)));
        });
        return new HeroTraits(result);
    }

    public Traits of(int heroId) {
        return byHero.getOrDefault(heroId, NONE);
    }

    public boolean has(int heroId) {
        return byHero.containsKey(heroId);
    }

    /** Сумма свойства по героям команды. */
    public int sum(Collection<Integer> heroes, String trait) {
        return heroes.stream().mapToInt(h -> of(h).get(trait)).sum();
    }

    /** Ответы, которых нет у команды: у героя awkward, а у соперника ни у кого нет этого свойства. */
    public List<String> missingAnswers(int heroId, Collection<Integer> enemies) {
        return of(heroId).awkward().stream().filter(a -> sum(enemies, a) == 0).toList();
    }
}
