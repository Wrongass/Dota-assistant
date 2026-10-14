package dota.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dota.analysis.AnalysisContext;
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
    private volatile AnalysisContext ctx;
    private volatile Status status;

    /** Шапка: патч, когда скачаны игроки и мета, предупреждения. */
    record Status(String patch, String playersFetched, String metaFetched, List<String> warnings) {
    }

    Site(Path configPath, Path dataDir, Path analysisDir) {
        this.configPath = configPath;
        this.dataDir = dataDir;
        this.analysisDir = analysisDir;
    }

    synchronized void reload() throws IOException {
        AnalysisContext fresh = AnalysisContext.load(configPath, dataDir);
        this.status = computeStatus(fresh);
        this.ctx = fresh;
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

    Squad nextOpponent() {
        PlayerConfig.League league = ctx.config.league();
        if (league == null || league.nextOpponent() == null) {
            return ctx.opponents.isEmpty() ? null : ctx.opponents.get(0);
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
        Instant playersAt = Instant.EPOCH;
        for (PlayerConfig.Player p : c.config.players()) {
            Path profile = dataDir.resolve("raw").resolve(p.team().dir()).resolve(String.valueOf(p.accountId()))
                    .resolve("profile.json");
            try {
                Instant t = Files.getLastModifiedTime(profile).toInstant();
                playersAt = t.isAfter(playersAt) ? t : playersAt;
            } catch (IOException e) {
                warnings.add(p.nick() + ": нет данных — запустите ./gradlew run");
            }
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
        return new Status(c.patch(), playersAt == Instant.EPOCH ? "—" : TIME.format(playersAt), metaAt, warnings);
    }
}
