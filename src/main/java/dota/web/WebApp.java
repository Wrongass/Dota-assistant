package dota.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dota.analysis.AnalysisContext;
import dota.analysis.DraftBot;
import dota.analysis.DraftEngine;
import dota.analysis.DraftEvaluation;
import dota.analysis.Fmt;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Локальный сайт. Запуск: {@code ./gradlew web} → http://127.0.0.1:8070
 * <pre>
 *   --port &lt;n&gt;         по умолчанию 8070
 *   --host &lt;addr&gt;      по умолчанию 127.0.0.1 (только этот компьютер); в Docker — 0.0.0.0
 *   --config &lt;path&gt;    по умолчанию config/players.yaml
 *   --data &lt;dir&gt;       по умолчанию data
 *   --analysis &lt;dir&gt;   по умолчанию analysis (ручные планы *_plan.md)
 *   --state &lt;file&gt;     состав на игру, выбранный на сайте; по умолчанию state/lineups.json
 * </pre>
 */
public class WebApp {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Site site;
    private final TemplateEngine templates = TemplateEngine.createPrecompiled(ContentType.Html);

    private final Auth auth;

    WebApp(Site site) {
        this(site, new Auth(null, java.time.Clock.systemUTC()));
    }

    WebApp(Site site, Auth auth) {
        this.site = site;
        this.auth = auth;
    }

    public static void main(String[] args) throws IOException {
        int port = 8070;
        String host = "127.0.0.1";
        Path config = Path.of("config/players.yaml");
        Path data = Path.of("data");
        Path analysis = Path.of("analysis");
        Path state = dota.analysis.Lineups.DEFAULT_FILE;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--config" -> config = Path.of(args[++i]);
                case "--data" -> data = Path.of(args[++i]);
                case "--analysis" -> analysis = Path.of(args[++i]);
                case "--state" -> state = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        Site site = new Site(config, data, analysis, state);
        long started = System.nanoTime();
        site.reload();
        System.out.printf("Data loaded in %d ms%n", (System.nanoTime() - started) / 1_000_000);
        Auth auth = Auth.fromEnvironment();
        System.out.println(auth.enabled() ? "Вход по паролю включён (SITE_PASSWORD)"
                : "SITE_PASSWORD не задан — сайт открыт без пароля (только для локального запуска)");
        System.out.println(lineupsStatus(state));
        new WebApp(site, auth).create().start(host, port);
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
        app.before(WebApp::rejectCrossSitePost);
        app.before(this::requireLogin);
        app.get("/health", ctx -> ctx.result("ok"));
        app.get("/login", this::loginPage);
        app.post("/login", this::login);
        app.get("/logout", ctx -> {
            auth.endSession(ctx);
            ctx.redirect("/login");
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
        app.get("/drafts", this::draftHistory);
        app.get("/counters", this::counters);
        app.get("/api/heroes", ctx -> ctx.json(site.ctx().heroList()));
        app.get("/api/player/{id}", this::playerApi);
        app.get("/api/counters", this::countersApi);
        app.get("/api/hero-maps", this::heroMapsApi);
        app.get("/img/{kind}/{file}", this::image);
        app.post("/api/draft", this::draftApi);
        app.post("/api/sim/move", this::simMove);
        app.post("/api/sim/plan", this::simPlan);
        app.post("/api/sim/evaluate", this::simEvaluate);
        app.post("/lineup", this::saveLineup);
        app.post("/api/share", ctx -> shareDraft(ctx, SharedDrafts.newCode()));
        app.post("/api/share/{code}", ctx -> shareDraft(ctx, ctx.pathParam("code")));
        app.get("/api/share/{code}", this::sharedDraft);
        app.get("/method", ctx -> render(ctx, "method.jte", new Models.Method(page("Методика", "method"))));
        app.post("/reload", ctx -> {
            site.reload();
            String back = ctx.header("Referer");
            ctx.redirect(back == null ? "/" : back);
        });
        app.exception(NotFoundResponse.class, (e, ctx) -> notFound(ctx, e.getMessage()));
        app.error(404, ctx -> {
            if (ctx.result() == null || ctx.result().isEmpty() || ctx.result().startsWith("Endpoint ")) {
                notFound(ctx, "такой страницы нет");
            }
        });
        app.exception(Exception.class, (e, ctx) -> {
            System.err.printf("Ошибка на %s: %s%n", ctx.path(), e);
            e.printStackTrace();
            ctx.status(500);
            if (ctx.path().startsWith("/api/")) {
                ctx.json(Map.of("error", "ошибка на сервере: " + e.getClass().getSimpleName()));
            } else {
                render(ctx, "error.jte", new Models.ErrorPage(page("Ошибка", ""), "Что-то сломалось",
                        "Сайт не смог показать эту страницу (" + e.getClass().getSimpleName() + "). Попробуйте обновить "
                                + "страницу; если не поможет — сообщите, что делали, и время."));
            }
        });
        return app;
    }

    // ---------------------------------------------------------------- вход

    /**
     * Защита от подделки запроса с чужого сайта (CSRF): POST, у которого Origin или Referer — другой сайт, отклоняется.
     * Браузер присылает Origin со всеми POST-формами и fetch; cookie сессии и так SameSite=Lax, это второй рубеж.
     * Без Origin и Referer (curl, тесты) пропускаем: подделать такой запрос из браузера нельзя.
     */
    static void rejectCrossSitePost(Context ctx) {
        if (!"POST".equals(ctx.method().name())) {
            return;
        }
        String source = ctx.header("Origin");
        if (source == null || source.isBlank() || "null".equals(source)) {
            source = ctx.header("Referer");
        }
        if (source == null || source.isBlank()) {
            return;
        }
        String host = ctx.header("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = ctx.header("Host");
        }
        // прокси переписал Host на внутренний адрес — сравнить не с чем; не ломаем сайт, защищает SameSite=Lax
        if (host == null || host.isBlank() || host.matches("(?i)(127\\.0\\.0\\.1|localhost|\\[::1]|web)(:\\d+)?")) {
            return;
        }
        try {
            java.net.URI uri = java.net.URI.create(source);
            String from = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            if (host != null && (from.equalsIgnoreCase(host) || uri.getHost().equalsIgnoreCase(host.split(":")[0]))) {
                return;
            }
        } catch (IllegalArgumentException e) {
            // кривой Origin — как чужой
        }
        ctx.status(403).result("Запрос с другого сайта отклонён");
        ctx.skipRemainingHandlers();
    }

    /** Всё, кроме входа, проверки здоровья и статики, — только после входа (если SITE_PASSWORD задан). */
    private void requireLogin(Context ctx) {
        String path = ctx.path();
        if (!auth.enabled() || path.equals("/login") || path.equals("/health") || path.startsWith("/static/")
                || auth.loggedIn(ctx)) {
            return;
        }
        if (path.startsWith("/api/")) {
            ctx.status(401).json(Map.of("error", "нужен вход: откройте сайт и введите пароль"));
        } else {
            String next = path + (ctx.queryString() == null ? "" : "?" + ctx.queryString());
            ctx.redirect("/login?next=" + java.net.URLEncoder.encode(next, java.nio.charset.StandardCharsets.UTF_8));
        }
        ctx.skipRemainingHandlers();
    }

    private void loginPage(Context ctx) {
        if (!auth.enabled() || auth.loggedIn(ctx)) {
            ctx.redirect(Auth.safeNext(ctx.queryParam("next")));
            return;
        }
        render(ctx, "login.jte", new Models.Login(Auth.safeNext(ctx.queryParam("next")), null));
    }

    private void login(Context ctx) throws InterruptedException {
        String next = Auth.safeNext(ctx.formParam("next"));
        if (!auth.enabled()) {
            ctx.redirect(next);
            return;
        }
        String ip = clientIp(ctx);
        if (auth.tooManyFailures(ip)) {
            ctx.status(429);
            render(ctx, "login.jte", new Models.Login(next, "Слишком много попыток. Подождите минуту."));
            return;
        }
        if (auth.checkPassword(ctx.formParam("password"))) {
            auth.startSession(ctx);
            ctx.redirect(next);
            return;
        }
        auth.recordFailure(ip);
        Thread.sleep(500); // замедляем подбор
        ctx.status(401);
        render(ctx, "login.jte", new Models.Login(next, "Неверный пароль."));
    }

    /** За Caddy реальный адрес — первый в X-Forwarded-For (Caddy подставляет его сам, клиентский не доверяет). */
    private static String clientIp(Context ctx) {
        String forwarded = ctx.header("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? ctx.ip() : forwarded.split(",")[0].trim();
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
        List<Models.NextOpponent> next = new ArrayList<>();
        for (Squad opp : site.upcoming()) {
            Views.Threats threats = opp.threats(c.ours);
            next.add(new Models.NextOpponent(next.isEmpty() ? "Следующая игра" : "Готовимся дальше", opp.slug(),
                    opp.teamName(), c.mapRecord(opp.team()), opp.roster().rows(),
                    threats.threats().stream().limit(5).toList(), threats.bans()));
        }
        String stage = c.config.league() == null ? null : c.config.league().stage();
        render(http, "home.jte", new Models.Home(page("Главная", "home"), ours, group, next, stage));
    }

    private void team(Context http) {
        render(http, "team.jte", new Models.TeamPage(page("Команда", "team"), site.ctx().ours.roster(), "/player/",
                site.ctx().ours.lineup(), http.queryParam("lineupError")));
    }

    /** 404 страницей сайта (или JSON для /api) с кодировкой UTF-8, а не служебным текстом фреймворка. */
    private void notFound(Context ctx, String what) {
        ctx.status(404);
        if (ctx.path().startsWith("/api/")) {
            ctx.json(Map.of("error", "не найдено: " + what));
            return;
        }
        render(ctx, "error.jte", new Models.ErrorPage(page("Не найдено", ""), "Не найдено", "Не нашёл: " + what + "."));
    }

    /** id игрока из адреса: число (account_id) или ник из конфига без учёта регистра; иначе 404, а не 500. */
    private long playerId(String raw) {
        if (raw != null && raw.matches("\\d{1,18}")) {
            return Long.parseLong(raw);
        }
        return site.ctx().config.players().stream()
                .filter(p -> p.nick().equalsIgnoreCase(raw == null ? "" : raw.trim()))
                .map(dota.fetch.PlayerConfig.Player::accountId).findFirst()
                .orElseThrow(() -> new NotFoundResponse("игрок «" + raw + "»"));
    }

    private void player(Context http) {
        long id = playerId(http.pathParam("id"));
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
            throw new NotFoundResponse("соперник «" + http.pathParam("slug") + "» (все соперники — в меню «Соперники»)");
        }
        if (!opp.slug().equals(http.pathParam("slug"))) {
            http.redirect("/opponent/" + opp.slug());
            return;
        }
        String plan = site.planFile(opp).map(this::markdown).orElse(null);
        render(http, "opponent.jte", new Models.OpponentPage(page(opp.team().name(), "opponents"), opp.slug(),
                opp.teamName(), plan, opp.lineup(), http.queryParam("lineupError"), opp.roster(), opp.threats(site.ctx().ours),
                opp.hasTournamentData() ? opp.tournament() : null));
    }

    /**
     * Форма состава: team — имя команды из конфига, p1..p5 — account_id на позициях, action=save|reset. После
     * сохранения данные перечитываются — новый состав сразу везде. Возврат на ту же страницу (с ошибкой в адресе).
     */
    private void saveLineup(Context http) throws IOException {
        String team = http.formParam("team");
        String back = Auth.safeNext(http.formParam("back"));
        try {
            Map<Integer, Long> lineup = null;
            if (!"reset".equals(http.formParam("action"))) {
                lineup = new java.util.HashMap<>();
                for (int pos = 1; pos <= 5; pos++) {
                    String v = http.formParam("p" + pos);
                    if (v != null && v.matches("\\d{1,12}")) {
                        lineup.put(pos, Long.parseLong(v));
                    }
                }
            }
            site.saveLineup(team, lineup);
            http.redirect(back);
        } catch (IllegalArgumentException e) {
            redirectWithError(http, back, e.getMessage());
        } catch (IOException | RuntimeException e) {
            // чаще всего папка state/ недоступна для записи (в Docker — нет тома ./state или нет прав на него)
            System.err.printf("Не удалось сохранить состав %s в %s: %s%n", team, site.lineupsFile, e);
            redirectWithError(http, back, "Не удалось сохранить состав на сервере (" + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + "). Проверьте, что папка state доступна сайту для записи.");
        }
    }

    private static void redirectWithError(Context http, String back, String message) {
        http.redirect(back + (back.contains("?") ? "&" : "?") + "lineupError="
                + java.net.URLEncoder.encode(message, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** При запуске: можно ли писать состав на игру. Ответ видно в логе (docker compose logs web). */
    static String lineupsStatus(Path file) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, "write-check", ".tmp");
            Files.delete(probe);
            return "Состав на игру сохраняется в " + file.toAbsolutePath();
        } catch (IOException | RuntimeException e) {
            return "ВНИМАНИЕ: состав на игру не сохранится, нет записи в " + file.toAbsolutePath().getParent() + " (" + e + ")";
        }
    }

    // ---------------------------------------------------------------- общий драфт по ссылке (FEAT-14)

    private void shareDraft(Context http, String code) throws IOException {
        if (!site.sharedDrafts.enabled()) {
            http.status(503).json(Map.of("error", "общий драфт недоступен: сайт запущен без папки состояния"));
            return;
        }
        if (!SharedDrafts.validCode(code)) {
            throw new NotFoundResponse("общий драфт «" + code + "»");
        }
        try {
            com.fasterxml.jackson.databind.JsonNode saved = site.sharedDrafts.save(code, JSON.readTree(http.body()));
            http.json(Map.of("code", code, "version", saved.path("version").asLong()));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            http.status(400).json(Map.of("error", "неверный драфт: " + e.getMessage()));
        }
    }

    /**
     * История драфтов (FEAT-02): сохранённые драфты и реальная карта нашей команды в лиге, если по пикам совпадает
     * хотя бы 8 из 10 героев.
     */
    private void draftHistory(Context http) throws IOException {
        AnalysisContext c = site.ctx();
        List<Views.MatchView> maps = c.ours.hasTournamentData() ? c.ours.tournament().matches() : List.of();
        List<Models.HistoryRow> rows = new ArrayList<>();
        for (SharedDrafts.Entry e : site.sharedDrafts.list()) {
            var d = e.draft();
            boolean weFirst = d.path("weFirst").asBoolean(true);
            List<Integer> ours = new ArrayList<>(), theirs = new ArrayList<>();
            int moves = d.path("heroes").size();
            for (int i = 0; i < moves && i < DraftEngine.SLOTS.size(); i++) {
                int h = d.path("heroes").get(i).asInt(0);
                if (h > 0 && DraftEngine.SLOTS.get(i).pick()) {
                    (DraftEngine.SLOTS.get(i).firstTeam() == weFirst ? ours : theirs).add(h);
                }
            }
            Views.MatchView best = null;
            int bestOverlap = 0;
            for (Views.MatchView m : maps) {
                java.util.Set<Integer> real = new java.util.HashSet<>();
                m.players().forEach(p -> real.add(p.heroId()));
                m.enemies().forEach(p -> real.add(p.heroId()));
                int overlap = (int) java.util.stream.Stream.concat(ours.stream(), theirs.stream()).filter(real::contains).count();
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = m;
                }
            }
            boolean matched = best != null && bestOverlap >= 8 && best.matchId() > 0;
            Squad opp = c.opponent(d.path("opponent").asText(""));
            rows.add(new Models.HistoryRow(e.code(), Site.TIME.format(e.updated()),
                    opp == null ? d.path("opponent").asText("") : opp.teamName(), d.path("map").asInt(1), moves, ours,
                    theirs, matched ? best.matchId() : null, matched ? best.won() : null, matched ? best.date() : null,
                    bestOverlap));
        }
        render(http, "drafts.jte", new Models.HistoryPage(page("История драфтов", "drafts"), rows,
                site.sharedDrafts.enabled()));
    }

    private void sharedDraft(Context http) throws IOException {
        var draft = site.sharedDrafts.read(http.pathParam("code"));
        if (draft.isEmpty()) {
            throw new NotFoundResponse("общий драфт «" + http.pathParam("code") + "»");
        }
        http.contentType("application/json; charset=utf-8").result(JSON.writeValueAsString(draft.get()));
    }

    // ---------------------------------------------------------------- драфт

    /**
     * Тело POST /api/draft: соперник, кто ходит первым, герои по ходам (0 — ход пропущен) и ручное назначение
     * «герой → ник игрока» (обе команды). lineup — выбранный состав соперника (key из theirLineups), null — все составы
     * из турнира с весами.
     */
    record DraftRequest(String opponent, boolean weFirst, List<Integer> heroes, Map<Integer, String> assign, String lineup) {
    }

    private void draftPage(Context http) throws Exception {
        AnalysisContext c = site.ctx();
        Squad next = site.nextOpponent();
        String requested = http.queryParam("opponent");
        String opponent = c.opponent(requested) != null ? c.opponent(requested).slug()
                : next != null ? next.slug() : c.opponents.isEmpty() ? "" : c.opponents.get(0).slug();
        List<Models.Link> opponents = c.opponents.stream()
                .map(o -> new Models.Link(o.slug(), o.teamName())).toList();
        render(http, "draft.jte", new Models.DraftPage(page("Драфт", "draft"), JSON.writeValueAsString(c.heroList()),
                JSON.writeValueAsString(opponents), opponent, JSON.writeValueAsString(DraftEngine.SLOTS),
                JSON.writeValueAsString(c.ours.poolOwners()), JSON.writeValueAsString(rosters(c))));
    }

    /** Контрпики: ?h=Viper&h2=Lina&pos=2 (имена или id героев; pos 0 или нет — позиция по первому герою). */
    /**
     * Контрпики для драфта (JSON): ?h=<id или имя>&pos=<наша позиция 1..5>. NaN (нет данных) → null, top — по 6 героев.
     */
    private void countersApi(Context http) {
        AnalysisContext c = site.ctx();
        Integer hero = heroId(c, http.queryParamAsClass("h", String.class).getOrDefault(""));
        if (hero == null) {
            throw new NotFoundResponse("герой «" + http.queryParam("h") + "»");
        }
        String rawPos = http.queryParam("pos");
        int pos = rawPos != null && rawPos.matches("[1-5]") ? Integer.parseInt(rawPos) : 0;
        dota.analysis.Counters.View v = dota.analysis.Counters.build(c, List.of(hero), pos);
        java.util.function.Function<Double, Double> num = x -> x == null || x.isNaN() ? null : x;
        java.util.function.Function<dota.analysis.Counters.Hero, Map<String, Object>> row = h -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("heroId", h.heroId());
            m.put("name", h.name());
            m.put("laneMatches", h.laneMatches());
            m.put("laneWin", num.apply(h.laneWin()));
            m.put("laneLoss", num.apply(h.laneLoss()));
            m.put("laneAdv", num.apply(h.laneAdv()));
            m.put("gameAdv", num.apply(h.gameAdv()));
            return m;
        };
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("enemy", v.enemyNames().get(0));
        result.put("position", v.position());
        result.put("player", v.player());
        result.put("hasLane", v.hasLane());
        result.put("lane", v.lane().stream().limit(6).map(row).toList());
        result.put("game", v.game().stream().limit(6).map(row).toList());
        result.put("ours", v.ours().stream().limit(6).map(o -> {
            Map<String, Object> m = row.apply(o.hero());
            m.put("games", o.games());
            m.put("winrate", num.apply(o.winrate()));
            m.put("recent60", o.recent60());
            return m;
        }).toList());
        http.json(result);
    }

    /**
     * FEAT-17: карты лиги, где игрок (ник из конфига) брал героя: ?nick=…&hero=<id>. Для «×2 в турнире» в драфте.
     */
    private void heroMapsApi(Context http) {
        AnalysisContext c = site.ctx();
        long id = playerId(http.queryParam("nick"));
        Integer hero = heroId(c, http.queryParamAsClass("hero", String.class).getOrDefault(""));
        Squad squad = c.squadOf(id);
        if (squad == null || hero == null || !squad.hasTournamentData()) {
            throw new NotFoundResponse("карты игрока на герое");
        }
        String nick = c.config.players().stream().filter(p -> p.accountId() == id).map(dota.fetch.PlayerConfig.Player::nick)
                .findFirst().orElse("");
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Views.MatchView m : squad.tournament().matches()) {
            boolean picked = m.players().stream().anyMatch(p -> p.heroId() == hero && p.nick().equals(nick));
            if (picked) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("matchId", m.matchId());
                row.put("date", m.date());
                row.put("opponent", m.opponent());
                row.put("won", m.won());
                row.put("minutes", m.minutes());
                maps.add(row);
            }
        }
        http.json(Map.of("nick", nick, "hero", c.heroName(hero), "maps", maps));
    }

    private void counters(Context http) {
        AnalysisContext c = site.ctx();
        String first = http.queryParamAsClass("h", String.class).getOrDefault("").trim();
        String second = http.queryParamAsClass("h2", String.class).getOrDefault("").trim();
        // pos: 1..5 или «авто»; мусор в адресе — «авто» с пояснением, а не JSON-ошибка
        String rawPos = http.queryParam("pos");
        int pos = rawPos != null && rawPos.matches("[0-5]") ? Integer.parseInt(rawPos) : 0;
        String posError = rawPos == null || rawPos.isEmpty() || rawPos.matches("[0-5]") ? null
                : "Позиция «" + rawPos + "» — такой нет, показываю «авто».";
        List<Integer> enemies = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String name : List.of(first, second)) {
            if (name.isEmpty()) {
                continue;
            }
            Integer id = heroId(c, name);
            if (id == null) {
                unknown.add(name);
            } else if (!enemies.contains(id)) {
                enemies.add(id);
            }
        }
        String error = unknown.isEmpty() ? posError
                : "Не нашёл героя: " + String.join(", ", unknown) + (posError == null ? "" : " " + posError);
        render(http, "counters.jte", new Models.CountersPage(page("Контрпики", "counters"), c.heroList(), first, second,
                pos < 1 || pos > 5, dota.analysis.Counters.build(c, enemies, pos), error));
    }

    private static Integer heroId(AnalysisContext c, String text) {
        String t = text.trim().toLowerCase(java.util.Locale.ROOT);
        for (AnalysisContext.HeroInfo h : c.heroList()) {
            if (h.name().toLowerCase(java.util.Locale.ROOT).equals(t) || String.valueOf(h.id()).equals(t)) {
                return h.id();
            }
        }
        List<AnalysisContext.HeroInfo> prefix = c.heroList().stream()
                .filter(h -> h.name().toLowerCase(java.util.Locale.ROOT).startsWith(t)).toList();
        return prefix.size() == 1 ? prefix.get(0).id() : null;
    }

    private static Map<String, Object> rosters(AnalysisContext c) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("ours", c.ours.draftRoster());
        c.opponents.forEach(o -> result.put(o.slug(), o.draftRoster()));
        // составы соперника из турнира: «slug|key» — игроки этого состава на своих позициях
        c.opponents.forEach(o -> c.lineupsOf(o).forEach(l -> result.put(o.slug() + "|" + l.key(), l.squad().draftRoster())));
        return result;
    }

    /** Карточка игрока для боковой панели драфта: чем играет сейчас и топ «играет лучше всего». */
    private void playerApi(Context http) {
        long id = playerId(http.pathParam("id"));
        Squad squad = site.ctx().squadOf(id);
        if (squad == null) {
            throw new NotFoundResponse("игрок " + id);
        }
        Views.PlayerCard card = squad.playerCard(id);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("nick", card.nick());
        result.put("pos", card.pos());
        result.put("team", squad.teamName());
        result.put("accountId", card.accountId());
        result.put("rankTier", card.rankTier());
        result.put("note", card.note() == null ? "" : card.note());
        result.put("form", card.form());
        result.put("best", card.best().stream().limit(10).map(h -> Map.of("id", h.heroId(), "name", h.name(),
                "skill", Math.round(h.skill()), "games", h.games(), "yearGames", h.yearGames(),
                "top", Fmt.topPercent(h.percentRank()))).toList());
        http.json(result);
    }

    private void draftApi(Context http) {
        DraftRequest req = http.bodyAsClass(DraftRequest.class);
        if (req.opponent() == null || site.ctx().opponent(req.opponent()) == null) {
            http.status(400).json(Map.of("error", "неизвестный соперник: " + req.opponent()));
            return;
        }
        List<Integer> heroes = req.heroes() == null ? List.of() : req.heroes();
        Map<Integer, String> assign = req.assign() == null ? Map.of() : req.assign();
        http.json(new DraftEngine(site.ctx()).advise(new DraftEngine.State(req.opponent(), req.weFirst(), heroes, assign,
                req.lineup())));
    }

    // ---------------------------------------------------------------- симуляция драфта (docs/specs/draft-simulation.md)

    /**
     * Тело запросов симуляции: как у /api/draft плюс зерно случайности бота. lineup — состав соперника; null — бот
     * выходит одним из составов турнира по зерну (раскрывается в конце, как в настоящей игре).
     */
    record SimRequest(String opponent, boolean weFirst, List<Integer> heroes, Map<Integer, String> assign, Long seed,
                      String lineup) {
    }

    /** Состав бота в симуляции или null — у соперника один состав. */
    private static AnalysisContext.TeamLineup simLineup(AnalysisContext c, SimRequest req) {
        List<AnalysisContext.TeamLineup> lineups = c.lineupsOf(c.opponent(req.opponent()));
        if (lineups.isEmpty()) {
            return null;
        }
        return lineups.stream().filter(l -> l.key().equals(req.lineup())).findFirst()
                .orElseGet(() -> lineups.get(Math.floorMod(new java.util.SplittableRandom(
                        (req.seed() == null ? 0 : req.seed()) ^ 0x5DEECE66DL).nextLong(), lineups.size())));
    }

    private static Squad simSquad(AnalysisContext c, SimRequest req) {
        AnalysisContext.TeamLineup l = simLineup(c, req);
        return l == null ? c.opponent(req.opponent()) : l.squad();
    }

    /** Проверенный запрос: соперник известен, ходов не больше 24. null — ответ с ошибкой уже отправлен. */
    private SimRequest simRequest(Context http) {
        SimRequest req = http.bodyAsClass(SimRequest.class);
        if (req.opponent() == null || site.ctx().opponent(req.opponent()) == null) {
            http.status(400).json(Map.of("error", "неизвестный соперник: " + req.opponent()));
            return null;
        }
        if (req.heroes() != null && req.heroes().size() > DraftEngine.SLOTS.size()) {
            http.status(400).json(Map.of("error", "ходов больше 24"));
            return null;
        }
        return req;
    }

    private static List<Integer> heroesOf(SimRequest req) {
        return req.heroes() == null ? List.of() : req.heroes();
    }

    /** Ход соперника-бота на текущем шаге. */
    private void simMove(Context http) {
        SimRequest req = simRequest(http);
        if (req == null) {
            return;
        }
        AnalysisContext c = site.ctx();
        try {
            DraftBot.Move m = new DraftBot(c).next(c.ours, simSquad(c, req), req.weFirst(), heroesOf(req),
                    req.seed() == null ? 0 : req.seed());
            http.json(Map.of("step", m.step(), "heroId", m.heroId(), "name", m.name(), "pick", m.pick()));
        } catch (IllegalStateException e) {
            http.status(409).json(Map.of("error", e.getMessage()));
        }
    }

    /** Итоговая расстановка бота (раскрывается в конце драфта) и его свапы. */
    private void simPlan(Context http) {
        SimRequest req = simRequest(http);
        if (req == null) {
            return;
        }
        AnalysisContext c = site.ctx();
        AnalysisContext.TeamLineup l = simLineup(c, req);
        DraftBot.Plan plan = new DraftBot(c).plan(c.ours, l == null ? c.opponent(req.opponent()) : l.squad(), req.weFirst(),
                heroesOf(req));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("placements", plan.placements());
        List<String> swaps = new java.util.ArrayList<>();
        if (l != null && req.lineup() == null) {
            swaps.add("Соперник вышел составом: " + l.label());
        }
        swaps.addAll(plan.swaps());
        result.put("swaps", swaps);
        result.put("lineup", l == null ? null : l.key());
        http.json(result);
    }

    /**
     * Оценка законченного драфта по позициям: назначения «герой → игрок» (ручные правки обеих команд и раскрытый
     * план бота) дают позиции через состав.
     */
    private void simEvaluate(Context http) {
        SimRequest req = simRequest(http);
        if (req == null) {
            return;
        }
        AnalysisContext c = site.ctx();
        List<Integer> heroes = heroesOf(req);
        AnalysisContext.TeamLineup l = simLineup(c, req);
        DraftEngine.Advice a = new DraftEngine(c).advise(new DraftEngine.State(req.opponent(), req.weFirst(), heroes,
                req.assign() == null ? Map.of() : req.assign(), l == null ? null : l.key()));
        if (!a.finished()) {
            http.status(409).json(Map.of("error", "драфт ещё не закончен"));
            return;
        }
        http.json(new DraftEvaluation(c).evaluate(positions(a.ourAssignment(), a.ourRoster()),
                positions(a.theirAssignment(), a.theirRoster()), heroes, req.weFirst()));
    }

    private static Map<Integer, Integer> positions(List<DraftEngine.Assignment> assignment, List<DraftEngine.RosterLine> roster) {
        Map<Integer, Integer> result = new java.util.LinkedHashMap<>();
        for (DraftEngine.Assignment x : assignment) {
            roster.stream().filter(r -> r.player().equals(x.player())).findFirst()
                    .ifPresent(r -> result.put(x.heroId(), r.position()));
        }
        return result;
    }

    // ---------------------------------------------------------------- картинки

    private static final Set<String> IMAGE_KINDS = Set.of("heroes", "icons", "ranks");

    /** Картинки Valve из data/img (портреты, иконки героев, значки рангов) — их качает выгрузка, см. ImageFetcher. */
    private void image(Context http) throws IOException {
        String kind = http.pathParam("kind"), file = http.pathParam("file");
        Path path = site.dataDir.resolve("img").resolve(kind).resolve(file);
        if (!IMAGE_KINDS.contains(kind) || !file.matches("[a-z0-9_]{1,40}\\.png") || !Files.isRegularFile(path)) {
            throw new NotFoundResponse(kind + "/" + file);
        }
        http.header("Cache-Control", "public, max-age=604800").contentType("image/png").result(Files.readAllBytes(path));
    }

    // ---------------------------------------------------------------- общее

    Models.Page page(String title, String active) {
        AnalysisContext c = site.ctx();
        Site.Status s = site.status();
        List<Models.Link> opponents = c.opponents.stream()
                .map(o -> new Models.Link("/opponent/" + o.slug(), o.team().name())).toList();
        return new Models.Page(title, active, auth.enabled(), c.ours.teamName(), s.patch(), s.playersFetched(), s.metaFetched(),
                s.warnings(), opponents);
    }

    private String markdown(Path file) {
        try {
            return Markdown.fileToHtml(file, href -> siteLink(file, href));
        } catch (IOException e) {
            return "<p>Не удалось прочитать " + file + ": " + e.getMessage() + "</p>";
        }
    }

    /**
     * Ссылка из ручного плана → страница сайта. Относительный путь считается от папки файла плана, затем
     * путь внутри analysis/ сопоставляется со страницей. null — на сайте такой страницы нет.
     */
    String siteLink(Path planFile, String href) {
        if (href == null || href.startsWith("http://") || href.startsWith("https://") || href.startsWith("#")) {
            return href;
        }
        String anchor = "";
        int hash = href.indexOf('#');
        if (hash >= 0) {
            anchor = href.substring(hash);
            href = href.substring(0, hash);
        }
        Path target = planFile.toAbsolutePath().getParent().resolve(href).normalize();
        Path root = site.analysisDir.toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            return null;
        }
        String rel = root.relativize(target).toString().replace('\\', '/');
        String page = sitePage(rel);
        return page == null ? null : page + anchor;
    }

    private String sitePage(String rel) {
        AnalysisContext c = site.ctx();
        String[] parts = rel.split("/");
        switch (rel) {
            case "README.md":
                return "/method";
            case "team/draft_plan.md", "team/draft_data.md":
                return "/draft-data";
            case "team/roster.md":
                return "/team";
            case "team/tournament.md":
                return "/tournament";
            case "league/group.md":
                return "/group";
            default:
                break;
        }
        if (parts[0].equals("meta")) {
            return "/meta";
        }
        if (parts[0].equals("players") && parts.length == 2) {
            return playerLink(c.ours, parts[1]);
        }
        if (parts[0].equals("opponents") && parts.length >= 3) {
            Squad opp = c.opponent(parts[1]);
            if (opp == null) {
                return null;
            }
            if (parts[2].equals("players") && parts.length == 4) {
                return playerLink(opp, parts[3]);
            }
            return "/opponent/" + opp.slug();
        }
        return null;
    }

    private static String playerLink(Squad squad, String file) {
        return squad.playerRefs().stream().filter(r -> r.file().equals(file)).findFirst()
                .map(r -> "/player/" + r.accountId()).orElse(null);
    }

    void render(Context http, String template, Object model) {
        StringOutput out = new StringOutput();
        templates.render(template, model, out);
        // кодировка явно: иначе браузер может показать русский текст кракозябрами (так было на 404)
        http.contentType("text/html; charset=utf-8").result(out.toString());
    }
}
