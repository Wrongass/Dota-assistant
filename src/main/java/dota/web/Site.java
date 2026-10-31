package dota.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dota.analysis.AnalysisContext;
import dota.analysis.Lineups;
import dota.analysis.Squad;
import dota.analysis.Views;
import dota.fetch.PlayerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Состояние сайта: загруженный {@link AnalysisContext} (перечитывается по кнопке, без перезапуска),
 * свежесть данных и предупреждения. К OpenDota сайт не обращается — только к data/ и config/.
 */
final class Site {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    final Path configPath;
    final Path dataDir;
    final Path analysisDir;
    /** Файл состава на игру (state/lineups.json) или null — состав только по конфигу. */
    final Path lineupsFile;
    private volatile Lineups lineups = Lineups.none();
    private volatile AnalysisContext ctx;
    private volatile Status status;

    /** Шапка: патч, когда скачаны игроки и мета, предупреждения. */
    record Status(String patch, String playersFetched, String metaFetched, List<String> warnings) {
    }

    /** Данные игрока старше стольких дней — предупреждение (перед игрой обычно обновляют). */
    static final int STALE_DAYS = 3;

    Site(Path configPath, Path dataDir, Path analysisDir) {
        this(configPath, dataDir, analysisDir, null);
    }

    Site(Path configPath, Path dataDir, Path analysisDir, Path lineupsFile) {
        this.configPath = configPath;
        this.dataDir = dataDir;
        this.analysisDir = analysisDir;
        this.lineupsFile = lineupsFile;
    }

    synchronized void reload() throws IOException {
        if (lineupsFile != null) {
            lineups = Lineups.load(lineupsFile);
        }
        AnalysisContext fresh = AnalysisContext.load(configPath, dataDir,
                java.time.LocalDate.now(java.time.ZoneOffset.UTC), lineups);
        this.status = computeStatus(fresh);
        this.ctx = fresh;
    }

    /**
     * Сохранить состав команды (позиция → account_id; null — вернуть состав по конфигу) и перечитать данные: состав
     * сразу применяется на всех страницах и в драфте. Ошибка — IllegalArgumentException с текстом для пользователя.
     */
    synchronized void saveLineup(String team, java.util.Map<Integer, Long> lineup) throws IOException {
        if (lineupsFile == null) {
            throw new IllegalArgumentException("сайт запущен без файла состава (--state)");
        }
        Squad squad = ctx.squadNamed(team);
        if (squad == null) {
            throw new IllegalArgumentException("нет команды " + team);
        }
        if (lineup != null) {
            java.util.Set<Long> known = new java.util.HashSet<>();
            squad.lineup().players().forEach(p -> known.add(p.accountId()));
            for (int pos = 1; pos <= 5; pos++) {
                Long id = lineup.get(pos);
                if (id == null || !known.contains(id)) {
                    throw new IllegalArgumentException("позиция " + pos + ": выберите игрока команды");
                }
            }
            if (lineup.values().stream().distinct().count() != 5) {
                throw new IllegalArgumentException("один игрок стоит на двух позициях");
            }
        }
        lineups = Lineups.load(lineupsFile).with(team, lineup);
        reload();
    }

    AnalysisContext ctx() {
        return ctx;
    }

    Status status() {
        return status;
    }

    /** Ручной план: наш draft_plan.md или game_plan.md соперника; пусто, если файла нет. */
    Optional<Path> planFile(Squad squad) {
        Path file = squad.ours() ? analysisDir.resolve("team/draft_plan.md")
                : analysisDir.resolve("opponents").resolve(squad.slug()).resolve("game_plan.md");
        return Files.exists(file) ? Optional.of(file) : Optional.empty();
    }

    /** Следующий соперник и те, к кому готовимся дальше (league.prepare), без повторов. */
    List<Squad> upcoming() {
        PlayerConfig.League league = ctx.config.league();
        List<Squad> result = new java.util.ArrayList<>();
        Squad next = nextOpponent();
        if (next != null) {
            result.add(next);
        }
        if (league != null) {
            for (String name : league.prepareOrEmpty()) {
                ctx.opponents.stream().filter(s -> s.team().name().equals(name) && !result.contains(s))
                        .findFirst().ifPresent(result::add);
            }
        }
        return result;
    }

    /** Следующий соперник из league.next_opponent или null, если он пока неизвестен (драфт тогда берёт первого). */
    Squad nextOpponent() {
        PlayerConfig.League league = ctx.config.league();
        if (league == null || league.nextOpponent() == null) {
            return null;
        }
        return ctx.opponents.stream().filter(s -> s.team().name().equals(league.nextOpponent()))
                .findFirst().orElse(null);
    }

    private Instant profileTime(AnalysisContext c, long accountId) {
        return c.config.players().stream().filter(p -> p.accountId() == accountId).findFirst()
                .map(p -> modified(dataDir.resolve("raw").resolve(p.team().dir()).resolve(String.valueOf(accountId))
                        .resolve("profile.json")))
                .orElse(Instant.EPOCH);
    }

    private static Instant modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private Status computeStatus(AnalysisContext c) {
        List<String> warnings = new ArrayList<>();
        // в шапке — самые старые данные игроков: выгрузка без --refresh не перекачивает уже скачанных, и по самому
        // свежему файлу (новые игроки) устаревшие данные не видно
        Instant playersAt = null;
        Instant staleBefore = Instant.now().minus(java.time.Duration.ofDays(STALE_DAYS));
        List<String> stale = new ArrayList<>();
        for (PlayerConfig.Player p : c.config.players()) {
            Path profile = dataDir.resolve("raw").resolve(p.team().dir()).resolve(String.valueOf(p.accountId()))
                    .resolve("profile.json");
            try {
                Instant t = Files.getLastModifiedTime(profile).toInstant();
                playersAt = playersAt == null || t.isBefore(playersAt) ? t : playersAt;
                if (t.isBefore(staleBefore)) {
                    stale.add(p.nick());
                }
            } catch (IOException e) {
                warnings.add(p.nick() + " (" + (p.teamName() == null ? "наша команда" : p.teamName()) + "): данных ещё нет, игрок появится на сайте после ближайшего обновления данных");
            }
        }
        if (!stale.isEmpty()) {
            warnings.add("Данные игроков старше " + STALE_DAYS + " дней (самые старые от " + TIME.format(playersAt) + "): "
                    + String.join(", ", stale) + ". Последние матчи, турнирные тоже, в статистике не видны. Обновите "
                    + "выгрузку с --refresh: без него уже скачанные игроки не перекачиваются");
        }
        for (Views.RosterRow r : c.ours.roster().rows()) {
            if (r.personaName() != null && !r.personaName().equalsIgnoreCase(r.nick())) {
                warnings.add(r.nick() + ": в Steam сейчас «" + r.personaName() + "»");
            }
        }
        Views.Group group = c.group();
        if (group != null && group.remakes() > 0) {
            warnings.add("Переигровок в лиге отброшено: " + group.remakes());
        }
        List<Squad> squads = new ArrayList<>(c.opponents);
        squads.add(0, c.ours);
        for (Squad s : squads) {
            Optional<Path> plan = planFile(s);
            if (plan.isEmpty()) {
                continue;
            }
            // план сравниваем с данными своей команды: наш — с нашими игроками, план на соперника — с его игроками
            Instant squadData = s.playerRefs().stream()
                    .map(r -> profileTime(c, r.accountId()))
                    .max(Instant::compareTo).orElse(Instant.EPOCH);
            if (modified(plan.get()).isBefore(squadData)) {
                warnings.add("План «" + analysisDir.relativize(plan.get()) + "» старше данных " + s.team().name()
                        + " — перепроверьте");
            }
        }

        String metaAt = "—";
        Path sample = dataDir.resolve("meta").resolve(c.patch()).resolve("_sample.json");
        try {
            JsonNode node = JSON.readTree(sample.toFile());
            metaAt = TIME.format(Instant.parse(node.path("fetched_at").asText()));
        } catch (Exception e) {
            warnings.add("Нет выборки меты (_sample.json) — синергии и контрпики могут отсутствовать");
        }
        return new Status(c.patch(), playersAt == null ? "—" : TIME.format(playersAt), metaAt, warnings);
    }
}
