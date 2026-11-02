package dota.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeroTraitsTest {

    @Test
    void everyHeroOfThePatchIsDescribed() throws Exception {
        HeroTraits traits = HeroTraits.load(Path.of("config/hero_traits.yaml"));
        List<String> missing = new ArrayList<>();
        new ObjectMapper().readTree(Path.of("data/raw/constants/heroes.json").toFile()).forEach(h -> {
            if (h.path("cm_enabled").asBoolean(true) && !traits.has(h.get("id").asInt())) {
                missing.add(h.get("id").asInt() + " " + h.get("localized_name").asText());
            }
        });
        assertEquals(List.of(), missing, "в config/hero_traits.yaml описаны все герои");
    }

    @Test
    void illusionHeroNeedsAnAnswer() throws Exception {
        HeroTraits traits = HeroTraits.load(Path.of("config/hero_traits.yaml"));
        assertEquals(1, traits.of(12).get("illusions"), "Phantom Lancer — иллюзии");
        assertEquals(List.of("anti_illusion"), traits.missingAnswers(12, List.of(26, 14)), "у Lion и Pudge нет AoE");
        assertEquals(List.of(), traits.missingAnswers(12, List.of(26, 7)), "Earthshaker отвечает на иллюзии");
        assertEquals(0, HeroTraits.empty().of(12).get("illusions"));
    }

    @Test
    void lateDeltaFromDurationBuckets() throws Exception {
        Meta meta = Meta.load(Path.of("data"));
        double spectre = meta.lateDelta(67), huskar = meta.lateDelta(59);
        assertTrue(Math.abs(spectre) < 0.2 && Math.abs(huskar) < 0.2, "сглаженная разница в разумных пределах");
    }
}
