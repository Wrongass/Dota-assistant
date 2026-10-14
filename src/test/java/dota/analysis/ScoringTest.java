package dota.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScoringTest {

    @Test
    void shrinkPullsSmallSamplesToPrior() {
        assertEquals(0.5 + 3.0 / 13 * 0.5, Scoring.shrink(3, 3, 0.5, 10), 1e-9);
        // 3/3 после сглаживания — 61.5%, а не 100%; 70/100 остаётся выше
        assertTrue(Scoring.shrink(3, 3, 0.5, 10) < Scoring.shrink(70, 100, 0.5, 10));
    }

    @Test
    void ageWeightHalvesEveryYear() {
        assertEquals(1.0, Scoring.ageWeight(0), 1e-9);
        assertEquals(0.5, Scoring.ageWeight(365), 1e-9);
        assertEquals(0.25, Scoring.ageWeight(730), 1e-9);
    }

    @Test
    void skillPrefersProvenHeroOverLuckyStreak() {
        double lucky = Scoring.skill(0.5, 3, 3);
        double proven = Scoring.skill(0.5, 60, 100);
        assertTrue(proven > lucky, proven + " vs " + lucky);
        assertTrue(Scoring.skill(1.0, 100, 100) <= 100);
    }

    @Test
    void metaStrengthIsBounded() {
        assertEquals(0, Scoring.metaStrength(0.40, 0.40, 0), 1e-9);
        assertEquals(1, Scoring.metaStrength(0.60, 0.60, 0.5), 1e-9);
        assertEquals(0.5, Scoring.metaStrength(Double.NaN, 0.5, 0.2), 1e-9);
    }

    @Test
    void topPercentFormatting() {
        assertEquals("топ <1%", MarkdownRenderer.topPercent(0.999));
        assertEquals("топ 5%", MarkdownRenderer.topPercent(0.95));
        assertEquals("—", MarkdownRenderer.topPercent(null));
    }
}
