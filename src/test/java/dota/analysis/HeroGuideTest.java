package dota.analysis;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HeroGuideTest {

    private static final Path DATA = Path.of("data");

    @Test
    void itemCountersNameRealHeroesAndItemsOfThePatch() throws Exception {
        assumeTrue(Files.isDirectory(DATA.resolve("patch")), "нужен data/patch (tools/patch/build.py --fetch)");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), DATA);
        HeroGuide guide = HeroGuide.load(ctx, Path.of("config/item_counters.yaml"), Path.of("analysis/patch"));
        assertEquals(List.of(), guide.problems(), "все герои и предметы config/item_counters.yaml есть в патче");

        HeroGuide.View pl = guide.hero(12);
        assertEquals("Phantom Lancer", pl.name());
        assertTrue(pl.goodItems().stream().anyMatch(a -> a.item().name().equals("Crimson Guard")), "Crimson против PL");
        assertNotNull(pl.goodItems().get(0).item().icon());
        HeroGuide.View pa = guide.hero(44);
        assertTrue(pa.badItems().stream().anyMatch(a -> a.item().name().equals("Bloodthorn")), "Bloodthorn без Pierce в 7.42");
        assertNull(guide.hero(100_000));
    }

    @Test
    void inventorySampleFillsNewItemsAndStrongItems() throws Exception {
        assumeTrue(Files.exists(Path.of("analysis/items/7.42/items.json")) && Files.isDirectory(DATA.resolve("patch")),
                "нужна выборка tools/items");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), DATA);
        HeroGuide guide = HeroGuide.load(ctx, Path.of("config/item_counters.yaml"), Path.of("analysis/patch"));
        HeroGuide.Index x = guide.index();
        assertFalse(x.strongItems().isEmpty());
        assertTrue(x.strongItems().stream().noneMatch(HeroGuide.InvItem::intermediate), "компоненты не в рейтинге");
        assertTrue(x.strongItems().stream().allMatch(i -> i.games() >= 150 && i.lift() > 0));
        assertTrue(x.weakItems().stream().allMatch(i -> i.lift() < 0));
        assertNotNull(x.inventorySample());
        // новые предметы: у каждого есть итог по выборке, герои в таблице — от 40 игр
        assertTrue(x.newItems().stream().anyMatch(n -> n.inventory() != null && n.inventory().games() > 100));
        x.newItems().forEach(n -> n.best().forEach(h -> assertTrue(h.purchases() >= 40, n.item().name() + " " + h.name())));
        HeroGuide.View wk = guide.hero(42);
        assertTrue(wk.inventoryItems().stream().anyMatch(i -> i.item().name().equals("Blink Dagger")));
    }

    @Test
    void heroPageListsOtherHeroesOnly() throws Exception {
        assumeTrue(Files.isDirectory(DATA.resolve("raw/stratz/lanes")), "нужны данные STRATZ");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), DATA);
        HeroGuide guide = HeroGuide.load(ctx, Path.of("config/item_counters.yaml"), Path.of("analysis/patch"));
        HeroGuide.View v = guide.hero(12);
        for (List<HeroGuide.Matchup> list : List.of(v.laneCounters(), v.gameCounters(), v.laneVictims(), v.gameVictims())) {
            assertTrue(list.stream().noneMatch(m -> m.heroId() == 12), "сам с собой не сравнивается");
            assertTrue(list.stream().allMatch(m -> m.name() != null && !m.name().startsWith("#")));
        }
        assertTrue(v.allies().stream().noneMatch(a -> a.heroId() == 12));
        // контрпики по игре отсортированы по убыванию
        for (int i = 1; i < v.gameCounters().size(); i++) {
            assertTrue(v.gameCounters().get(i - 1).gameAdv() >= v.gameCounters().get(i).gameAdv());
        }
    }
}
