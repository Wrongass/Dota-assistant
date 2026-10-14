package dota.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** Markdown-отчёты в analysis/ из {@link Views}. Файлы *_plan.md пишутся вручную и здесь не трогаются. */
final class MarkdownRenderer {

    private static final int TOP = Squad.TOP;

    private final AnalysisContext ctx;

    MarkdownRenderer(AnalysisContext ctx) {
        this.ctx = ctx;
    }

    void writeAll(Path out) throws IOException {
        Squad ours = ctx.ours;
        write(out.resolve("README.md"), readme());
        write(out.resolve("team/roster.md"), roster(ours.roster()));
        write(out.resolve("team/draft_data.md"), draftData(ours.draftData()));
        if (ours.hasTournament()) {
            write(out.resolve("team/tournament.md"), tournament(ours.tournament()));
        }
        write(out.resolve("meta/patch_" + ctx.patch() + ".md"), meta(ours.metaView()));
        for (PlayerStats p : ours.players) {
            write(out.resolve("players").resolve(ours.playerFile(p)), playerCard(ours.playerCard(p)));
        }
        for (Squad opp : ctx.opponents) {
            Path dir = out.resolve("opponents").resolve(opp.slug());
            write(dir.resolve("README.md"), opponentReadme(opp));
            write(dir.resolve("team/roster.md"), roster(opp.roster()));
            write(dir.resolve("team/threats.md"), threats(opp.threats(ours)));
            if (opp.hasTournament()) {
                write(dir.resolve("team/tournament.md"), tournament(opp.tournament()));
            }
            for (PlayerStats p : opp.players) {
                write(dir.resolve("players").resolve(opp.playerFile(p)), playerCard(opp.playerCard(p)));
            }
        }
        if (ctx.hasLeague()) {
            write(out.resolve("league/group.md"), group(ctx.group()));
        }
    }

    // ---------------------------------------------------------------- оглавления

    private String readme() {
        Squad ours = ctx.ours;
        String patch = ctx.patch();
        Md md = new Md().h1("Анализ: " + ours.teamName());
        md.line("Сгенерировано %s из `data/` (патч **%s**, начало %s). Пересобрать: `./gradlew analyze`.",
                ours.today, patch, ctx.patchStart().toString().substring(0, 10));
        md.h2("Что где лежит");
        md.line("| Файл | Что внутри |").line("|---|---|");
        md.line("| [team/draft_plan.md](team/draft_plan.md) | **План драфта**: выводы и рекомендации (пишется вручную на основе файлов ниже) |");
        md.line("| [team/roster.md](team/roster.md) | Состав, позиции, ранги, на каких позициях кто реально играет, сыгранность |");
        md.line("| [team/draft_data.md](team/draft_data.md) | Цифры для драфта: пулы по позициям, связки внутри команды, неудобные герои, кандидаты в баны |");
        if (ctx.hasLeague()) {
            md.line("| [team/tournament.md](team/tournament.md) | Наши турнирные матчи: драфты, кто что играл, что банят против нас |");
            md.line("| [league/group.md](league/group.md) | Группа: результаты и драфты всех матчей группы |");
        }
        for (Squad opp : ctx.opponents) {
            md.line("| [opponents/%s/](opponents/%s/README.md) | Соперник %s: состав, угрозы, турнирные драфты, игроки |",
                    opp.slug(), opp.slug(), opp.teamName());
        }
        md.line("| [meta/patch_%s.md](meta/patch_%s.md) | Мета патча: сильные/слабые по позициям, про-сцена (CM), лучшие связки и контрпики |", patch, patch);
        for (Views.PlayerRef p : ours.playerRefs()) {
            md.line("| [players/%s](players/%s) | Позиция %d, %s: лучшие герои и герои под патч |",
                    p.file(), p.file(), p.pos(), p.nick());
        }
        md.h2("Как считаются оценки");
        md.line("- **Умение на герое (0–100)**: 40% — место среди всех игроков героя (OpenDota rankings, percent_rank);")
                .line("  35% — винрейт с весом по давности (игра годичной давности весит ½), сглаженный к 50% весом 10 игр;")
                .line("  25% — опыт (40 «свежих» игр = максимум). Турбо не учитывается.")
                .line("- **Сила в мете (0–1)**: 60% — винрейт героя в пабах на ранге игрока ±1; 20% — винрейт на этой")
                .line("  позиции в разобранных матчах патча (в основном про и высокий MMR); 20% — доля про-матчей с пиком/баном.")
                .line("- **Пригодность в патче (0–100)** = поровну сила в мете и умение игрока.")
                .line("- **▲ сильный / ▼ слабый** — винрейт в пабах на нужном ранге ≥ %.0f%% / ≤ %.0f%%.", 100 * Meta.STRONG_WR, 100 * Meta.WEAK_WR)
                .line("- **Синергия пары** — винрейт пары в одной команде минус среднее базовых винрейтов героев;")
                .line("  **контрпик** — винрейт героя против героя минус ожидаемый. Выборка: ~250 тыс. публичных матчей")
                .line("  Legend+ текущего патча за последние ~15 часов (`data/meta/%s/_sample.json`).", patch);
        md.h2("Ограничения");
        md.line("- Позиции 1–5 в OpenDota напрямую нет: позиция героя оценена по разобранным матчам патча")
                .line("  (4/5 — два последних по GPM в команде, 1/2/3 — по линии). Для редких героев может ошибаться.")
                .line("- Детальная статистика (линия, предметы) есть только у разобранных матчей игроков.")
                .line("- Патч берётся из справочника OpenDota; если он отстаёт от игры, мета будет за прошлый патч.");
        return md.toString();
    }

    private String opponentReadme(Squad opp) {
        Md md = new Md().h1("Соперник: " + opp.teamName());
        md.line("Сгенерировано %s, патч %s. Позиции — по турнирным матчам (см. `config/players.yaml`).", opp.today, ctx.patch());
        md.h2("Файлы");
        md.line("| Файл | Что внутри |").line("|---|---|");
        md.line("| [game_plan.md](game_plan.md) | **План на игру** против них (пишется вручную) |");
        md.line("| [team/threats.md](team/threats.md) | Их опасные герои, что они банят, чем их контрить из наших пулов |");
        md.line("| [team/tournament.md](team/tournament.md) | Их турнирные матчи и драфты |");
        md.line("| [team/roster.md](team/roster.md) | Состав, ранги, на каких позициях играют в пабах, сыгранность |");
        for (Views.PlayerRef p : opp.playerRefs()) {
            md.line("| [players/%s](players/%s) | Позиция %d, %s: лучшие герои и герои под патч |",
                    p.file(), p.file(), p.pos(), p.nick());
        }
        md.blank().line("Как считаются оценки — в [README анализа](../../README.md).");
        return md.toString();
    }

    // ---------------------------------------------------------------- отчёты

    static String roster(Views.Roster v) {
        Md md = new Md().h1("Состав: " + v.teamName());
        md.line("Данные на %s, патч %s. Турбо не учитывается.", v.today(), v.patch());
        Md.Table t = md.table("Поз.", "Игрок", "Steam-ник", "account_id", "Ранг", "MMR (оценка)",
                "Матчей всего / год / патч", "WR в патче", "Captains Mode", "Роль в команде");
        for (Views.RosterRow r : v.rows()) {
            t.row(r.pos() + (r.inferred() ? "*" : ""), "[" + r.nick() + "](../players/" + r.file() + ")",
                    r.personaName(), r.accountId(), Meta.bracketName(r.rankTier()), Md.num(r.mmr()),
                    r.totalGames() + " / " + r.yearGames() + " / " + r.patchGames(),
                    r.patchGames() == 0 ? "—" : Md.pct((double) r.patchWins() / r.patchGames()),
                    r.cmGames() == 0 ? "0" : r.cmGames() + " (" + Md.pct((double) r.cmWins() / r.cmGames()) + ")",
                    r.note() == null ? "" : r.note());
        }
        t.end();
        if (v.anyInferred()) {
            md.blank().line("\\* позиция не указана в конфиге и определена по данным (доля игр на героях этой позиции).");
        }

        md.h2("На каких позициях реально играют (пабы за последний год)");
        md.line("Доля игр на героях, которых в патче играют на позиции N (по профилю героя, а не по факту матча).").blank();
        Md.Table share = md.table("Игрок", "Поз. в команде", "1", "2", "3", "4", "5", "Линии в разобранных матчах (safe/mid/off/лес)");
        for (Views.PosShareRow r : v.shares()) {
            int[] l = r.lanes();
            share.row(r.nick(), r.pos(), Md.pct(r.share()[0]), Md.pct(r.share()[1]), Md.pct(r.share()[2]),
                    Md.pct(r.share()[3]), Md.pct(r.share()[4]), l[0] + "/" + l[1] + "/" + l[2] + "/" + l[3]);
        }
        share.end();

        md.h2("Сыгранность: матчей в одной команде (WR)");
        List<String> nicks = v.nicks();
        String[] headers = new String[nicks.size() + 1];
        headers[0] = "";
        for (int i = 0; i < nicks.size(); i++) {
            headers[i + 1] = nicks.get(i);
        }
        Md.Table m = md.table(headers);
        for (int a = 0; a < nicks.size(); a++) {
            Object[] row = new Object[nicks.size() + 1];
            row[0] = nicks.get(a);
            for (int b = 0; b < nicks.size(); b++) {
                int g = v.together()[a][b], w = v.togetherWins()[a][b];
                row[b + 1] = a == b ? "—" : g == 0 ? "0" : g + " (" + Md.pct((double) w / g) + ")";
            }
            m.row(row);
        }
        m.end();
        return md.toString();
    }

    static String playerCard(Views.PlayerCard p) {
        Md md = new Md().h1("Позиция " + p.pos() + " — " + p.nick());
        md.line("- Steam: %s, account_id %d, [OpenDota](https://www.opendota.com/players/%d), [Dotabuff](https://ru.dotabuff.com/players/%d)",
                p.personaName(), p.accountId(), p.accountId(), p.accountId());
        md.line("- Ранг: %s, оценка MMR %s", Meta.bracketName(p.rankTier()), Md.num(p.mmr()));
        md.line("- Матчей (без турбо): всего %d, за год %d, в патче %s — %d (WR %s); Captains Mode: %d",
                p.totalGames(), p.yearGames(), p.patch(), p.patchGames(),
                p.patchGames() == 0 ? "—" : Md.pct((double) p.patchWins() / p.patchGames()), p.cmGames());
        if (p.note() != null) {
            md.line("- Роль: %s", p.note());
        }
        md.line("- Мета для этого игрока считается на рангах **%s**.", p.rangeName());

        md.h2("Топ-" + TOP + " героев, на которых играет лучше всего");
        md.line("Не просто винрейт или число игр — см. «Как считаются оценки» в [README](../README.md). Позиции героя — как его играют в патче.").blank();
        Md.Table best = md.table("#", "Герой", "Умение", "Игр всего / год / патч", "WR всего", "WR за год",
                "Ранг на герое", "KDA", "Поз. героя", "В патче (" + p.rangeName() + ")");
        int i = 1;
        for (Views.BestHero h : p.best()) {
            best.row(i++, h.name(), Md.num(h.skill()), h.games() + " / " + h.yearGames() + " / " + h.patchGames(),
                    Md.pct(h.winrate()), Md.pct(h.yearWinrate()), topPercent(h.percentRank()), Md.dec(h.kda()),
                    h.positions(), Md.pct(h.metaWinrate()) + " " + Squad.label(h.metaWinrate()));
        }
        best.end();

        md.h2("Топ-" + TOP + " героев под патч " + p.patch() + " на позиции " + p.pos());
        md.line("Герои, которых в патче играют на позиции %d (≥15%% игр), по пригодности = сила в мете + умение игрока.", p.pos()).blank();
        Md.Table fit = md.table("#", "Герой", "Пригодность", "Сила в мете", "WR на ранге", "WR на поз. (про/high)",
                "Про пик+бан", "Умение игрока", "Игр у игрока", "Отметка");
        i = 1;
        for (Views.FitRow f : p.fits()) {
            fit.row(i++, f.name(), Md.num(f.fit()), Md.dec(f.meta()), Md.pct(f.winrate()), Md.pct(f.posWinrate()),
                    Md.pct(f.proContest()), Md.num(f.skill()), f.games(), f.mark());
        }
        fit.end();

        md.h2("Выделяются в патче на позиции " + p.pos() + " (" + p.rangeName() + ")");
        md.line("**▲ Сильные:** %s", heroList(p.strong()));
        md.blank().line("**▼ Слабые:** %s", heroList(p.weak()));

        md.h2("Осторожно: из личного топа, но слабы в патче");
        md.line(p.weakInPool().isEmpty() ? "Нет." : p.weakInPool().stream()
                .map(h -> h.name() + " (" + Md.pct(h.value()) + ")").collect(Collectors.joining(", ")));
        return md.toString();
    }

    /** percent_rank 0.995 → "топ <1%", 0.95 → "топ 5%". */
    static String topPercent(Double percentRank) {
        if (percentRank == null) {
            return "—";
        }
        double top = 100 * (1 - percentRank);
        return top < 1 ? "топ <1%" : "топ " + Md.num(top) + "%";
    }

    private static String heroList(List<Views.HeroValue> heroes) {
        if (heroes.isEmpty()) {
            return "нет";
        }
        return heroes.stream().map(h -> h.name() + " " + Md.pct(h.value())).collect(Collectors.joining(", "));
    }

    static String meta(Views.MetaView v) {
        Md md = new Md().h1("Мета патча " + v.patch());
        md.line("Данные на %s. Винрейты в пабах — на рангах **%s** (медиана нашей команды ±1). Про-сцена — %d матчей в выборке OpenDota.",
                v.today(), v.rangeName(), v.proMatches());
        for (Views.PositionMeta pm : v.positions()) {
            md.h2("Позиция " + pm.pos());
            Md.Table t = md.table("#", "Герой", "Сила в мете", "WR в пабах", "Пикрейт", "WR на поз. (про/high)",
                    "Про пик+бан", "Отметка");
            int i = 1;
            for (Views.MetaRow r : pm.top()) {
                t.row(i++, r.name(), Md.dec(r.strength()), Md.pct(r.winrate()), Md.pct(r.pickRate()),
                        Md.pct(r.posWinrate()), Md.pct(r.proContest()), Squad.label(r.winrate()));
            }
            t.end();
            md.blank().line("**▼ Слабые на позиции:** %s", heroList(pm.weak()));
        }

        md.h2("Про-сцена: самые оспариваемые герои (пик + бан)");
        md.line("Для Captains Mode: этих героев чаще всего банят или берут первыми.").blank();
        Md.Table pro = md.table("#", "Герой", "Пик+бан", "Пики", "Баны", "WR в про", "Позиции");
        int i = 1;
        for (Views.ProRow r : v.pro()) {
            pro.row(i++, r.name(), Md.pct(r.contest()), r.picks(), r.bans(), Md.pct(r.proWinrate()), r.positions());
        }
        pro.end();

        md.h2("Лучшие связки (в одной команде)");
        md.line("Синергия = винрейт пары − среднее базовых винрейтов героев. Минимум 400 игр пары.").blank();
        Md.Table syn = md.table("#", "Связка", "Синергия", "WR пары", "Игр");
        i = 1;
        for (Views.PairRow r : v.synergies()) {
            syn.row(i++, r.aName() + " + " + r.bName(), Md.signedPct(r.value()), Md.pct(r.winrate()), r.games());
        }
        syn.end();

        md.h2("Самые жёсткие контрпики");
        md.line("Преимущество = винрейт героя против героя − ожидаемый из их базовых винрейтов. Минимум 400 игр.").blank();
        Md.Table cnt = md.table("#", "Герой", "контрит", "Преимущество", "WR", "Игр");
        i = 1;
        for (Views.PairRow r : v.counters()) {
            cnt.row(i++, r.aName(), r.bName(), Md.signedPct(r.value()), Md.pct(r.winrate()), r.games());
        }
        cnt.end();
        return md.toString();
    }

    static String draftData(Views.DraftData v) {
        Md md = new Md().h1("Данные для драфта: " + v.teamName());
        md.line("Сгенерировано %s, патч %s. Выводы — в [draft_plan.md](draft_plan.md).", v.today(), v.patch());

        md.h2("Пулы по позициям (топ-6 по пригодности в патче)");
        Md.Table pool = md.table("Поз.", "Игрок", "Кандидаты (пригодность; ▲ — сильный в патче)");
        for (Views.Pool p : v.pools()) {
            pool.row(p.pos(), p.nick(), poolList(p.heroes()));
        }
        pool.end();

        md.h2("Связки внутри наших пулов");
        md.line("Пары героев из топ-10 пулов двух разных игроков (только герои, на которых игрок сыграл ≥ 3 игр),")
                .line("синергия ≥ +2%% (минимум %d игр пары в выборке).", Squad.MIN_PAIR_GAMES).blank();
        Md.Table ct = md.table("#", "Связка", "Игроки", "Синергия", "WR пары", "Игр");
        int i = 1;
        for (Views.ComboRow c : v.combos()) {
            ct.row(i++, c.pair().aName() + " + " + c.pair().bName(), c.players(), Md.signedPct(c.pair().value()),
                    Md.pct(c.pair().winrate()), c.pair().games());
        }
        ct.end();

        md.h2("Против кого мы проигрываем чаще всего");
        md.line("Все матчи 5 игроков за 2 года (без турбо), герой во вражеской команде. Винрейт сглажен к нашему среднему (%s) весом 30 игр.", Md.pct(v.avgVs())).blank();
        Md.Table nem = md.table("#", "Герой соперника", "Наш WR против", "Игр", "WR героя в пабах (" + v.rangeName() + ")");
        i = 1;
        for (Views.VsRow r : v.nemesis()) {
            nem.row(i++, r.name(), Md.pct(r.vsWinrate()), r.games(), Md.pct(r.pubWinrate()));
        }
        nem.end();
        md.blank().line("**Против кого играем лучше всего:** %s", v.bestAgainst().stream()
                .map(h -> h.name() + " " + Md.pct(h.value())).collect(Collectors.joining(", ")));

        md.h2("Кандидаты в баны");
        md.line("Оценка = половина «насколько мы проигрываем против героя» (если против него ≥ 40 игр)")
                .line("+ половина «сила в мете на нашем уровне». Герои из топ-6 наших пулов сюда не попадают.").blank();
        Md.Table bans = md.table("#", "Герой", "Оценка", "Наш WR против", "WR в пабах", "Про пик+бан", "Позиции");
        i = 1;
        for (Views.BanRow r : v.bans()) {
            bans.row(i++, r.name(), Md.dec(r.score()), Double.isNaN(r.vsWinrate()) ? "—" : Md.pct(r.vsWinrate()) + " (" + r.vsGames() + ")",
                    Md.pct(r.pubWinrate()), Md.pct(r.proContest()), r.positions());
        }
        bans.end();

        md.h2("Чем контрят наших лучших героев");
        md.line("Для топ-3 каждого пула — герои с наибольшим преимуществом против него (минимум %d игр).", Squad.MIN_PAIR_GAMES).blank();
        Md.Table th = md.table("Игрок", "Наш герой", "Опасные против него");
        for (Views.CounterThreat t : v.threats()) {
            th.row(t.nick(), t.name(), t.threats().stream().map(h -> h.name() + " " + Md.signedPct(h.value()))
                    .collect(Collectors.joining(", ")));
        }
        th.end();
        return md.toString();
    }

    private static String poolList(List<Views.PoolEntry> heroes) {
        return heroes.stream().map(f -> f.name() + " " + Md.num(f.fit()) + (f.strong() ? " ▲" : ""))
                .collect(Collectors.joining(", "));
    }

    static String tournament(Views.Tournament v) {
        Md md = new Md().h1("Турнир: " + v.teamName());
        md.line("Лига OpenDota %d, матчи с начала сезона. Сыграно карт: %d, побед: %d.%s", v.leagueId(), v.matches().size(),
                v.wins(), v.hadRemakes() ? " Переигровки (пустая статистика) не учитываются." : "");
        for (Views.MatchView m : v.matches()) {
            md.h2(m.date() + " — против " + m.opponent() + ": " + (m.won() ? "победа" : "поражение"));
            md.line("%s, %d мин, [OpenDota](https://www.opendota.com/matches/%d), [Dotabuff](https://ru.dotabuff.com/matches/%d)",
                    m.radiant() ? "Radiant" : "Dire", m.minutes(), m.matchId(), m.matchId());
            Md.Table t = md.table("Игрок", "Герой", "Линия", "GPM", "K/D/A");
            for (Views.MatchPlayer p : m.players()) {
                t.row(p.nick(), p.heroName(), lane(p.laneRole()), p.gpm(), p.kills() + "/" + p.deaths() + "/" + p.assists());
            }
            t.end();
            md.blank().line("Соперник: %s", m.enemies().stream()
                    .map(p -> p.heroName() + " (" + p.nick() + ")").collect(Collectors.joining(", ")));
            md.blank().line("Драфт по порядку (**жирным** — ходы этой команды, b — бан, P — пик):").blank();
            md.line(m.draft().stream().map(d -> {
                String step = (d.pick() ? "P " : "b ") + d.heroName();
                return d.ours() ? "**" + step + "**" : step;
            }).collect(Collectors.joining(" → ")));
        }
        md.h2("Сводка");
        md.line("**Пики команды** (в скобках — победы): %s", countList(v.picks(), true));
        md.blank().line("**Баны команды:** %s", countList(v.bans(), false));
        md.blank().line("**Банят против команды** (чего боятся соперники): %s", countList(v.bansAgainst(), false));
        md.blank().line("**Пики соперников** (в скобках — их победы): %s", countList(v.enemyPicks(), true));
        return md.toString();
    }

    static String lane(int laneRole) {
        return switch (laneRole) {
            case 1 -> "safe";
            case 2 -> "mid";
            case 3 -> "off";
            case 4 -> "лес";
            default -> "—";
        };
    }

    static String countList(List<Views.Count> counts, boolean withWins) {
        if (counts.isEmpty()) {
            return "—";
        }
        return counts.stream()
                .map(c -> c.name() + (c.count() > 1 ? " ×" + c.count() : "") + (withWins ? " (" + c.wins() + ")" : ""))
                .collect(Collectors.joining(", "));
    }

    static String threats(Views.Threats v) {
        Md md = new Md().h1("Угрозы: " + v.teamName());
        md.line("Сгенерировано %s, патч %s.", v.today(), v.patch());

        md.h2("Пулы по позициям (топ-6 по пригодности в патче)");
        Md.Table pool = md.table("Поз.", "Игрок", "Ранг", "Кандидаты (пригодность; ▲ — сильный в патче)", "В турнире");
        for (Views.OppPool p : v.pools()) {
            pool.row(p.pos(), "[" + p.nick() + "](../players/" + p.file() + ")", Meta.bracketName(p.rankTier()),
                    poolList(p.heroes()), p.tourney().isEmpty() ? "—" : countList(p.tourney(), false));
        }
        pool.end();

        md.h2("Самые опасные герои (кандидаты в баны)");
        md.line("Угроза = 60% умение игрока на герое + 40% сила героя в мете на его ранге; +0.1, если герой уже был в турнире.").blank();
        Md.Table th = md.table("#", "Герой", "Игрок", "Угроза", "Умение", "Сила в мете", "Игр всего / год", "WR за год", "В турнире", "Позиции героя");
        int i = 1;
        for (Views.ThreatRow t : v.threats()) {
            th.row(i++, t.name(), t.nick(), Md.dec(t.score()), Md.num(t.skill()), Md.dec(t.meta()),
                    t.games() + " / " + t.yearGames(), t.yearGames() == 0 ? "—" : Md.pct((double) t.yearWins() / t.yearGames()),
                    t.tourneyPicks() == 0 ? "" : "×" + t.tourneyPicks(), t.positions());
        }
        th.end();

        md.h2("Что они банят в турнире");
        md.line(countList(v.bans(), false));
        md.blank().line("Из наших пулов они уже банили: %s", v.bannedFromOurPool().isEmpty() ? "ничего" : String.join(", ", v.bannedFromOurPool()));

        md.h2("Чем контрить их главных героев (из наших пулов)");
        md.line("Для топ-8 угроз — наши герои (топ-10 пулов, с опытом), у которых преимущество против них ≥ +2%% (минимум %d игр).", Squad.MIN_PAIR_GAMES).blank();
        Md.Table ct = md.table("Их герой", "Игрок", "Наши ответы (игрок, преимущество)");
        for (Views.CounterRow r : v.counters()) {
            String answers = r.answers().stream()
                    .map(a -> a.name() + " " + Md.signedPct(a.advantage()) + " (" + String.join("/", a.players()) + ")")
                    .collect(Collectors.joining(", "));
            ct.row(r.name(), r.nick(), answers.isEmpty() ? "—" : answers);
        }
        ct.end();

        md.h2("Против кого они проигрывают");
        md.line("Их матчи за 2 года (без турбо), сглажено к их среднему (%s) весом 30 игр; минимум 40 игр. ✔ — герой есть в наших пулах.", Md.pct(v.avg())).blank();
        Md.Table nem = md.table("#", "Герой", "Их WR против", "Игр", "В наших пулах");
        i = 1;
        for (Views.LossRow r : v.losses()) {
            nem.row(i++, r.name(), Md.pct(r.winrate()), r.games(), r.inOurPool() ? "✔" : "");
        }
        nem.end();
        return md.toString();
    }

    static String group(Views.Group v) {
        Md md = new Md().h1("Группа: " + v.name());
        md.line("Матчи между командами группы из лиги OpenDota %d. Переигровок отброшено (по всей лиге): %d.", v.leagueId(), v.remakes());
        md.h2("Карты");
        Md.Table st = md.table("Команда", "Карт", "Побед", "Поражений");
        for (Views.Standing s : v.standings()) {
            st.row(s.team(), s.games(), s.wins(), s.games() - s.wins());
        }
        st.end();
        md.h2("Матчи");
        Md.Table mt = md.table("Дата", "Radiant", "Dire", "Победитель", "Мин", "Пики Radiant", "Пики Dire", "Матч");
        for (Views.GroupMatch m : v.matches()) {
            mt.row(m.date(), m.radiant(), m.dire(), m.winner(), m.minutes(), String.join(", ", m.radiantHeroes()),
                    String.join(", ", m.direHeroes()), "[" + m.matchId() + "](https://www.opendota.com/matches/" + m.matchId() + ")");
        }
        mt.end();
        return md.toString();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
