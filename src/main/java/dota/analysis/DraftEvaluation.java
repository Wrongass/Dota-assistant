package dota.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Оценка законченного драфта (docs/specs/draft-simulation.md, раздел 6): линии, мид-гейм, лейт, масс-файты,
 * сплит-пуш, кайт, неудобные герои и итог. У каждого раздела score от −1 (сильнее они) до +1 (сильнее мы), словесный
 * вердикт и причины с именами героев. Веса итога — {@link Scoring#DRAFT_WEIGHTS}.
 */
public final class DraftEvaluation {

    /** Раздел оценки: score > 0 — в нашу пользу. */
    public record Section(String key, String title, double score, String verdict, List<String> reasons) {
    }

    public record Result(List<Section> lanes, List<Section> sections, Section total) {
    }

    private final AnalysisContext ctx;
    private final Meta meta;
    private final HeroTraits traits;

    public DraftEvaluation(AnalysisContext ctx) {
        this.ctx = ctx;
        this.meta = ctx.meta;
        this.traits = ctx.traits;
    }

    /**
     * ours и theirs — герой → позиция 1..5 (у каждой команды по пять героев на разных позициях). heroes и weFirst —
     * порядок ходов: из него считается, кто кого перепикал.
     */
    public Result evaluate(Map<Integer, Integer> ours, Map<Integer, Integer> theirs, List<Integer> heroes, boolean weFirst) {
        List<Integer> a = List.copyOf(ours.keySet()), b = List.copyOf(theirs.keySet());
        List<Section> lanes = List.of(
                lane("Наша лёгкая", ours, List.of(1, 5), theirs, List.of(3, 4)),
                lane("Мид", ours, List.of(2), theirs, List.of(2)),
                lane("Наша сложная", ours, List.of(3, 4), theirs, List.of(1, 5)));
        double laneMean = lanes.stream().mapToDouble(Section::score).average().orElse(0);

        List<Section> sections = new ArrayList<>();
        sections.add(game(a, b, heroes, weFirst));
        sections.add(midGame(a, b, laneMean));
        sections.add(lateGame(a, b));
        sections.add(compare("teamfight", "Масс-файты", a, b, h -> traits.of(h).get("teamfight") + 0.5 * traits.of(h).get("catch"),
                3, "файт", "сильнее в масс-файтах"));
        sections.add(versus("split", "Сплит-пуш", a, b, h -> traits.of(h).get("split") + traits.of(h).get("illusions"),
                "сплит", "проще сплит-пушить"));
        sections.add(versus("kite", "Кайт", a, b, h -> traits.of(h).get("kite"), "кайт", "проще кайтить"));

        Map<String, Double> parts = new LinkedHashMap<>();
        parts.put("lanes", laneMean);
        sections.forEach(s -> parts.put(s.key(), s.score()));
        double total = 0;
        for (var e : Scoring.DRAFT_WEIGHTS.entrySet()) {
            total += e.getValue() * parts.getOrDefault(e.getKey(), 0.0);
        }
        List<String> reasons = new ArrayList<>();
        List<Section> all = new ArrayList<>(lanes);
        all.addAll(sections);
        all.stream().filter(s -> Math.abs(s.score()) >= 0.15)
                .sorted(Comparator.comparingDouble((Section s) -> Math.abs(s.score())).reversed()).limit(4)
                .forEach(s -> reasons.add(s.title() + ": " + s.verdict()));
        String verdict = Math.abs(total) < 0.08 ? "драфт ровный"
                : (total > 0 ? "драфт выиграли мы" : "драфт выиграли они") + " (" + degree(total) + ")";
        return new Result(lanes, sections, new Section("total", "Итог", total, verdict, reasons));
    }

    // ---------------------------------------------------------------- линии

    /** Линия: исход по STRATZ (наши против их и их против наших), без данных — контрпики меты. */
    private Section lane(String title, Map<Integer, Integer> ours, List<Integer> ourPos, Map<Integer, Integer> theirs,
                         List<Integer> theirPos) {
        Map<Integer, Integer> a = at(ours, ourPos), b = at(theirs, theirPos);
        List<String> reasons = new ArrayList<>();
        double ourAdv = 0, theirAdv = 0;
        int ourN = 0, theirN = 0, matches = 0;
        for (var e : a.entrySet()) {
            LaneStats.Outcome o = ctx.lanes.lane(e.getKey(), e.getValue(), b.keySet());
            if (o != null) {
                ourAdv += o.advantage();
                ourN++;
                matches += o.matches();
                reasons.add(meta.name(e.getKey()) + " против " + names(b.keySet()) + ": линия выиграна в "
                        + Md.pct(o.win()) + ", проиграна в " + Md.pct(o.loss()) + " (" + o.matches() + " линий)");
            }
        }
        for (var e : b.entrySet()) {
            LaneStats.Outcome o = ctx.lanes.lane(e.getKey(), e.getValue(), a.keySet());
            if (o != null) {
                theirAdv += o.advantage();
                theirN++;
                matches += o.matches();
            }
        }
        double adv;
        if (ourN + theirN > 0) {
            adv = (ourN == 0 ? 0 : ourAdv / ourN) - (theirN == 0 ? 0 : theirAdv / theirN);
            adv /= ourN > 0 && theirN > 0 ? 2 : 1;
        } else {
            // данных STRATZ по парам нет — контрпики меты (масштаб: +3% по игре ≈ заметное преимущество на линии)
            double sum = 0;
            int n = 0;
            for (int x : a.keySet()) {
                for (int y : b.keySet()) {
                    double c = meta.counterAdvantage(x, y, Squad.MIN_PAIR_GAMES);
                    if (!Double.isNaN(c)) {
                        sum += c;
                        n++;
                    }
                }
            }
            adv = n == 0 ? 0 : 3 * sum / n;
            reasons.add("мало данных STRATZ по этим парам — оценка по контрпикам меты");
        }
        double score = Math.tanh(adv / 0.25);
        String head = names(a.keySet()) + " против " + names(b.keySet());
        reasons.add(0, head + (matches > 0 ? ", всего " + matches + " линий в выборке" : ""));
        return new Section("lane", title, score, verdict(score, "сильнее на линии"), reasons);
    }

    private static Map<Integer, Integer> at(Map<Integer, Integer> team, List<Integer> positions) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        team.forEach((h, pos) -> {
            if (positions.contains(pos)) {
                result.put(h, pos);
            }
        });
        return result;
    }

    // ---------------------------------------------------------------- по игре и перепик

    /**
     * Преимущество по игре: контрпики пятёрок (среднее по парам), синергия внутри команды, неудобные герои без ответа и
     * «кто кого перепикал» — сколько раз сторона взяла контрпик к уже открытому герою соперника.
     */
    private Section game(List<Integer> a, List<Integer> b, List<Integer> heroes, boolean weFirst) {
        double counter = 0;
        int n = 0;
        for (int x : a) {
            for (int y : b) {
                double c = meta.counterAdvantage(x, y, Squad.MIN_PAIR_GAMES);
                if (!Double.isNaN(c)) {
                    counter += c;
                    n++;
                }
            }
        }
        counter = n == 0 ? 0 : counter / n;
        double syn = synergy(a) - synergy(b);
        List<String> reasons = new ArrayList<>();
        reasons.add("контрпики пятёрок: " + Md.signedPct(counter) + ", синергия: " + Md.signedPct(syn));
        List<String> ourAwkward = awkward(a, b), theirAwkward = awkward(b, a);
        theirAwkward.forEach(t -> reasons.add("у них " + t));
        ourAwkward.forEach(t -> reasons.add("у нас " + t));
        int[] outpicks = outpicks(heroes, weFirst);
        reasons.add("перепик (контрпики к уже открытым героям соперника): у нас " + outpicks[0] + ", у них " + outpicks[1]);
        double g = counter + syn + 0.04 * (ourAwkward.size() - theirAwkward.size()) + 0.005 * (outpicks[0] - outpicks[1]);
        double score = Math.tanh(g / 0.03);
        return new Section("game", "Преимущество по игре", score, verdict(score, "сильнее по контрпикам и синергии"), reasons);
    }

    private double synergy(List<Integer> team) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < team.size(); i++) {
            for (int j = i + 1; j < team.size(); j++) {
                double l = meta.synergyLift(team.get(i), team.get(j), Squad.MIN_PAIR_GAMES);
                if (!Double.isNaN(l)) {
                    sum += l;
                    n++;
                }
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** «Phantom Lancer: нет ответа (AoE против иллюзий)» — неудобные герои team, на которых у enemy нет ответа. */
    private List<String> awkward(List<Integer> team, List<Integer> enemy) {
        List<String> result = new ArrayList<>();
        for (int h : team) {
            List<String> missing = traits.missingAnswers(h, enemy);
            if (!missing.isEmpty()) {
                result.add(meta.name(h) + ", у соперника нет ответа: " + String.join(", ", missing.stream().map(DraftEvaluation::answer).toList()));
            }
        }
        return result;
    }

    static String answer(String trait) {
        return switch (trait) {
            case "anti_illusion" -> "AoE против иллюзий";
            case "catch" -> "ловли";
            case "burst" -> "быстрого урона";
            default -> trait;
        };
    }

    /** [наши, их] контрпики (+2% и больше по мете), взятые после героя соперника, которого они контрят. */
    int[] outpicks(List<Integer> heroes, boolean weFirst) {
        int[] result = new int[2];
        List<Integer> ourSoFar = new ArrayList<>(), theirSoFar = new ArrayList<>();
        for (int i = 0; i < heroes.size() && i < DraftEngine.SLOTS.size(); i++) {
            DraftEngine.Slot s = DraftEngine.SLOTS.get(i);
            int h = heroes.get(i) == null ? 0 : heroes.get(i);
            if (!s.pick() || h <= 0) {
                continue;
            }
            boolean ours = s.firstTeam() == weFirst;
            for (int x : ours ? theirSoFar : ourSoFar) {
                double c = meta.counterAdvantage(h, x, Squad.MIN_PAIR_GAMES);
                if (!Double.isNaN(c) && c >= 0.02) {
                    result[ours ? 0 : 1]++;
                }
            }
            (ours ? ourSoFar : theirSoFar).add(h);
        }
        return result;
    }

    // ---------------------------------------------------------------- стадии игры

    /** Мид-гейм: башни (пуш из справочника + роль Pusher), Рошан, и кто выиграл линии — тот раньше ломает башни. */
    private Section midGame(List<Integer> a, List<Integer> b, double laneMean) {
        ToDoubleFunction<Integer> push = h -> traits.of(h).get("push") + (meta.heroes.containsKey(h)
                && meta.heroes.get(h).roles().contains("Pusher") ? 0.5 : 0);
        double pushA = sum(a, push), pushB = sum(b, push);
        double roshA = traits.sum(a, "roshan"), roshB = traits.sum(b, "roshan");
        double score = Math.tanh(0.5 * (pushA - pushB) / 3 + 0.3 * (roshA - roshB) / 3 + 0.4 * laneMean);
        List<String> reasons = new ArrayList<>();
        reasons.add("урон по башням: мы " + Md.dec(pushA) + " (" + top(a, push) + "), они " + Md.dec(pushB) + " (" + top(b, push) + ")");
        reasons.add("Рошан: мы " + (int) roshA + " (" + top(a, h -> traits.of(h).get("roshan")) + "), они " + (int) roshB
                + " (" + top(b, h -> traits.of(h).get("roshan")) + ")");
        if (Math.abs(laneMean) >= 0.15) {
            reasons.add(laneMean > 0 ? "линии лучше у нас — раньше давим башни" : "линии лучше у них — раньше давят башни");
        }
        return new Section("mid", "Мид-гейм", score, verdict(score, "сильнее в мид-гейме"), reasons);
    }

    /** Лейт: насколько винрейт героев растёт на играх от 45 минут (мета) + поправка справочника. */
    private Section lateGame(List<Integer> a, List<Integer> b) {
        ToDoubleFunction<Integer> late = h -> 20 * meta.lateDelta(h) + 0.5 * traits.of(h).late();
        double la = sum(a, late), lb = sum(b, late);
        double score = Math.tanh((la - lb) / 2);
        List<String> reasons = List.of("сила в длинной игре: мы " + Md.dec(la) + " (" + top(a, late) + "), они "
                + Md.dec(lb) + " (" + top(b, late) + ")");
        return new Section("late", "Лейт-гейм", score, verdict(score, "сильнее в лейте"), reasons);
    }

    /** Сравнение суммы свойства у двух команд. */
    private Section compare(String key, String title, List<Integer> a, List<Integer> b, ToDoubleFunction<Integer> f,
                            double scale, String what, String stronger) {
        double x = sum(a, f), y = sum(b, f);
        double score = Math.tanh((x - y) / scale);
        return new Section(key, title, score, verdict(score, stronger),
                List.of(what + ": мы " + Md.dec(x) + " (" + top(a, f) + "), они " + Md.dec(y) + " (" + top(b, f) + ")"));
    }

    /** Сплит и кайт: свойство команды минус половина ловли соперника — ловля отвечает и на сплит, и на кайт. */
    private Section versus(String key, String title, List<Integer> a, List<Integer> b, ToDoubleFunction<Integer> f,
                           String what, String easier) {
        ToDoubleFunction<Integer> catchF = h -> traits.of(h).get("catch");
        // ловля соперника только мешает тому, что есть: нет сплитовиков — нечему мешать
        double x = Math.max(0, sum(a, f) - 0.5 * sum(b, catchF)), y = Math.max(0, sum(b, f) - 0.5 * sum(a, catchF));
        double score = Math.tanh((x - y) / 3);
        List<String> reasons = List.of(
                what + ": мы " + Md.dec(sum(a, f)) + " (" + top(a, f) + "), они " + Md.dec(sum(b, f)) + " (" + top(b, f) + ")",
                "ловля: у нас " + Md.dec(sum(a, catchF)) + " (" + top(a, catchF) + "), у них " + Md.dec(sum(b, catchF))
                        + " (" + top(b, catchF) + ")");
        String v = Math.abs(score) < 0.15 ? "ровно" : (score > 0 ? "нам " : "им ") + easier + " (" + degree(score) + ")";
        return new Section(key, title, score, v, reasons);
    }

    private static double sum(List<Integer> team, ToDoubleFunction<Integer> f) {
        return team.stream().mapToDouble(f).sum();
    }

    /** Два героя команды с наибольшим значением (только положительные). */
    private String top(List<Integer> team, ToDoubleFunction<Integer> f) {
        List<String> t = team.stream().filter(h -> f.applyAsDouble(h) > 0)
                .sorted(Comparator.comparingDouble(f).reversed()).limit(2).map(meta::name).toList();
        return t.isEmpty() ? "—" : String.join(", ", t);
    }

    private String names(java.util.Collection<Integer> heroes) {
        return heroes.isEmpty() ? "—" : String.join(" + ", heroes.stream().map(meta::name).toList());
    }

    static String degree(double score) {
        double s = Math.abs(score);
        return s < 0.4 ? "слабо" : s < 0.7 ? "заметно" : "сильно";
    }

    static String verdict(double score, String stronger) {
        return Math.abs(score) < 0.15 ? "ровно" : (score > 0 ? "мы " : "они ") + stronger + " (" + degree(score) + ")";
    }
}
