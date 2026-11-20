package dota.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeroItemsTest {

    /**
     * Ранний предмет (минута 10) выигрывает 55%, поздний (минута 35) — 75%, потому что поздние покупки бывают в уже
     * выигранных играх. На каждой минуте с ними сравнивается другой предмет той же минуты.
     */
    @Test
    void itemIsComparedWithPurchasesOfTheSameMinuteNotWithFiftyPercent() {
        HeroItems items = new HeroItems();
        items.add(1, 1, 100, 0, 10, 2000, 1100); // 55% на 10-й минуте
        items.add(1, 1, 101, 0, 10, 2000, 1000); // 50% на 10-й минуте
        items.add(1, 1, 200, 0, 35, 2000, 1500); // 75% на 35-й минуте
        items.add(1, 1, 201, 0, 35, 2000, 1560); // 78% на 35-й минуте

        List<HeroItems.Line> lines = items.lines(1, 1);
        HeroItems.Line early = line(lines, 100), late = line(lines, 200);
        assertEquals(0.75, late.winrate(), 1e-9);
        assertEquals(0.05, early.rawLift(), 1e-9, "55% против 50% той же минуты");
        assertEquals(-0.03, late.rawLift(), 1e-9, "75% против 78% той же минуты: поздний предмет не лучший");
        assertEquals(0.05 * 2000 / (2000 + HeroItems.PRIOR), early.lift(), 1e-9, "сглажено по числу покупок");
        assertEquals(100, lines.get(0).itemId(), "сначала лучший по прибавке");
        assertTrue(early.z() > 2, "2000 покупок и +5% — надёжно");
        assertEquals(10, early.minute(), 1e-9);
    }

    @Test
    void rareItemsAndSecondCopiesAreLeftOut() {
        HeroItems items = new HeroItems();
        items.add(1, 1, 100, 0, 10, 3000, 1500);
        items.add(1, 1, 300, 0, 10, 200, 200); // 100% на 200 покупках — меньше порога
        items.add(1, 1, 100, 1, 25, 900, 800); // вторая копия
        List<HeroItems.Line> lines = items.lines(1, 1);
        assertEquals(List.of(100), lines.stream().map(HeroItems.Line::itemId).toList());
        assertEquals(3000, lines.get(0).purchases());
        assertTrue(items.lines(1, 2).isEmpty());
        assertEquals(List.of(1), items.positions(1));
    }

    @Test
    void minuteWithoutOtherPurchasesGivesNoEstimate() {
        HeroItems items = new HeroItems();
        items.add(1, 1, 100, 0, 40, 1000, 900); // на 40-й минуте больше ничего не покупали
        HeroItems.Line l = items.lines(1, 1).get(0);
        assertTrue(Double.isNaN(l.lift()), "не с чем сравнить");
        assertEquals(0.9, l.winrate(), 1e-9);
    }

    private static HeroItems.Line line(List<HeroItems.Line> lines, int item) {
        return lines.stream().filter(l -> l.itemId() == item).findFirst().orElseThrow();
    }
}
