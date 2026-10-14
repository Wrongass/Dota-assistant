package dota.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dota.analysis.AnalysisContext;
import dota.analysis.DraftEngine;
import dota.analysis.Squad;
import dota.analysis.Views;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.staticfiles.Location;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Локальный сайт. Запуск: {@code ./gradlew web} → http://127.0.0.1:8070
 * <pre>
 *   --port &lt;n&gt;         по умолчанию 8070
 *   --host &lt;addr&gt;      по умолчанию 127.0.0.1 (только этот компьютер); в Docker — 0.0.0.0
 *   --config &lt;path&gt;    по умолчанию config/players.yaml
 *   --data &lt;dir&gt;       по умолчанию data
 *   --analysis &lt;dir&gt;   по умолчанию analysis (ручные планы *_plan.md)
 * </pre>
 */
public class WebApp {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Site site;
    private final TemplateEngine templates = TemplateEngine.createPrecompiled(ContentType.Html);

    WebApp(Site site) {
        this.site = site;
    }

    public static void main(String[] args) throws IOException {
        int port = 8070;
        String host = "127.0.0.1";
        Path config = Path.of("config/players.yaml");
        Path data = Path.of("data");
        Path analysis = Path.of("analysis");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--config" -> config = Path.of(args[++i]);
                case "--data" -> data = Path.of(args[++i]);
                case "--analysis" -> analysis = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        Site site = new Site(config, data, analysis);
        long started = System.nanoTime();
        site.reload();
        System.out.printf("Data loaded in %d ms%n", (System.nanoTime() - started) / 1_000_000);
        new WebApp(site).create().start(host, port);
        System.out.printf("Open http://%s:%d%n", host.equals("0.0.0.0") ? "localhost" : host, port);
    }

    Javalin create() {
        Javalin app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.staticFiles.add(sf -> {
                sf.hostedPath = "/static";
                sf.directory = "/static";
                sf.location = Location.CLASSPATH;
            });
        });
        app.get("/", this::home);
        app.get("/team", this::team);
        app.get("/player/{id}", this::player);
        app.get("/meta", this::meta);
        app.get("/draft-data", this::draftData);
        app.get("/tournament", this::tournament);
        app.get("/group", this::group);
        app.get("/opponent/{slug}", this::opponent);
        app.get("/draft", this::draftPage);
        app.post("/api/draft", this::draftApi);
        app.get("/method", ctx -> render(ctx, "method.jte", new Models.Method(page("Методика", "method"))));
        app.post("/reload", ctx -> {
            site.reload();
            String back = ctx.header("Referer");
            ctx.redirect(back == null ? "/" : back);
        });
        app.exception(NotFoundResponse.class, (e, ctx) -> ctx.status(404).result("Не найдено: " + e.getMessage()));
        return app;
    }

    // ---------------------------------------------------------------- страницы

    private void home(Context http) {
        AnalysisContext c = site.ctx();
        List<Models.OurLine> ours = new ArrayList<>();
        Views.DraftData draft = c.ours.draftData();
        for (Views.RosterRow r : c.ours.roster().rows()) {
            List<Views.PoolEntry> pool = draft.pools().stream().filter(p -> p.nick().equals(r.nick()))
                    .findFirst().map(Views.Pool::heroes).orElse(List.of());
            ours.add(new Models.OurLine(r.pos(), r.nick(), r.accountId(), r.rankTier(), pool.stream().limit(3).toList()));
        }
        Views.Group group = c.group();
        Models.NextOpponent next = null;
        Squad opp = site.nextOpponent();
        if (opp != null) {
            Views.Threats threats = opp.threats(c.ours);
            String record = group == null ? "" : group.standings().stream()
                    .filter(s -> s.team().equals(opp.team().name())).findFirst()
                    .map(s -> s.wins() + "–" + (s.games() - s.wins())).orElse("");
            next = new Models.NextOpponent(opp.slug(), opp.teamName(), record, opp.roster().rows(),
                    threats.threats().stream().limit(5).toList(), threats.bans());
        }
        render(http, "home.jte", new Models.Home(page("Главная", "home"), ours, group, next));
    }

    private void team(Context http) {
        render(http, "team.jte", new Models.TeamPage(page("Команда", "team"), site.ctx().ours.roster(), "/player/"));
    }

    private void player(Context http) {
        long id = Long.parseLong(http.pathParam("id"));
        AnalysisContext c = site.ctx();
        Squad squad = c.squadOf(id);
        if (squad == null) {
            throw new NotFoundResponse("игрок " + id);
        }
        Views.PlayerCard card = squad.playerCard(id);
        String teamHref = squad.ours() ? "/team" : "/opponent/" + squad.slug();
        render(http, "player.jte", new Models.PlayerPage(page(card.nick(), squad.ours() ? "team" : "opponents"),
                card, squad.teamName(), teamHref));
    }

    private void meta(Context http) {
        render(http, "meta.jte", new Models.MetaPage(page("Мета " + site.ctx().patch(), "meta"), site.ctx().ours.metaView()));
    }

    private void draftData(Context http) throws IOException {
        Squad ours = site.ctx().ours;
        String plan = site.planFile(ours).map(this::markdown).orElse(null);
        render(http, "draft_data.jte", new Models.DraftDataPage(page("Наш драфт", "draft-data"), ours.draftData(), plan));
    }

    private void tournament(Context http) {
        Squad ours = site.ctx().ours;
        if (!ours.hasTournamentData()) {
            throw new NotFoundResponse("в config/players.yaml нет лиги или team_id нашей команды");
        }
        render(http, "tournament.jte", new Models.TournamentPage(page("Турнир", "tournament"), ours.tournament(), "/group"));
    }

    private void group(Context http) {
        Views.Group group = site.ctx().group();
        if (group == null) {
            throw new NotFoundResponse("в config/players.yaml нет лиги");
        }
        List<Models.Link> teams = site.ctx().opponents.stream()
                .map(s -> new Models.Link("/opponent/" + s.slug(), s.team().name())).toList();
        render(http, "group.jte", new Models.GroupPage(page("Группа", "group"), group, teams));
    }

    private void opponent(Context http) {
        Squad opp = site.ctx().opponent(http.pathParam("slug"));
        if (opp == null) {
            throw new NotFoundResponse("соперник " + http.pathParam("slug"));
        }
        String plan = site.planFile(opp).map(this::markdown).orElse(null);
        render(http, "opponent.jte", new Models.OpponentPage(page(opp.team().name(), "opponents"), opp.slug(),
                opp.teamName(), plan, opp.roster(), opp.threats(site.ctx().ours),
                opp.hasTournamentData() ? opp.tournament() : null));
    }

    // ---------------------------------------------------------------- драфт

    /** Тело POST /api/draft: соперник, кто ходит первым, герои по ходам (0 — ход пропущен). */
    record DraftRequest(String opponent, boolean weFirst, List<Integer> heroes) {
    }

    private void draftPage(Context http) throws Exception {
        AnalysisContext c = site.ctx();
        Squad next = site.nextOpponent();
        String requested = http.queryParam("opponent");
        String opponent = requested != null && c.opponent(requested) != null ? requested
                : next != null ? next.slug() : c.opponents.isEmpty() ? "" : c.opponents.get(0).slug();
        List<Models.Link> opponents = c.opponents.stream()
                .map(o -> new Models.Link(o.slug(), o.teamName())).toList();
        render(http, "draft.jte", new Models.DraftPage(page("Драфт", "draft"), JSON.writeValueAsString(c.heroList()),
                JSON.writeValueAsString(opponents), opponent, JSON.writeValueAsString(DraftEngine.SLOTS)));
    }

    private void draftApi(Context http) {
        DraftRequest req = http.bodyAsClass(DraftRequest.class);
        if (req.opponent() == null || site.ctx().opponent(req.opponent()) == null) {
            http.status(400).json(Map.of("error", "неизвестный соперник: " + req.opponent()));
            return;
        }
        List<Integer> heroes = req.heroes() == null ? List.of() : req.heroes();
        http.json(new DraftEngine(site.ctx()).advise(new DraftEngine.State(req.opponent(), req.weFirst(), heroes)));
    }

    // ---------------------------------------------------------------- общее

    Models.Page page(String title, String active) {
        AnalysisContext c = site.ctx();
        Site.Status s = site.status();
        List<Models.Link> opponents = c.opponents.stream()
                .map(o -> new Models.Link("/opponent/" + o.slug(), o.team().name())).toList();
        return new Models.Page(title, active, c.ours.teamName(), s.patch(), s.playersFetched(), s.metaFetched(),
                s.warnings(), opponents);
    }

    private String markdown(Path file) {
        try {
            return Markdown.fileToHtml(file);
        } catch (IOException e) {
            return "<p>Не удалось прочитать " + file + ": " + e.getMessage() + "</p>";
        }
    }

    void render(Context http, String template, Object model) {
        StringOutput out = new StringOutput();
        templates.render(template, model, out);
        http.html(out.toString());
    }
}
