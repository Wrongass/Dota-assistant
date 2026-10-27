package dota.web;

import dota.analysis.Views;

import java.util.List;

/** Модели страниц для JTE-шаблонов (src/main/jte). У каждого шаблона один параметр — своя модель. */
public final class Models {

    private Models() {
    }

    public record Link(String href, String label) {
    }

    /** Общее для всех страниц: заголовок, активный пункт меню, шапка со свежестью данных. */
    public record Page(String title, String active, boolean authEnabled, String teamName, String patch, String playersFetched,
                       String metaFetched, List<String> warnings, List<Link> opponents) {
    }

    // ---------------------------------------------------------------- главная

    public record OurLine(int pos, String nick, long accountId, int rankTier, List<Views.PoolEntry> pool) {
    }

    public record NextOpponent(String slug, String name, String record, List<Views.RosterRow> roster,
                               List<Views.ThreatRow> threats, List<Views.Count> bans) {
    }

    public record Home(Page page, List<OurLine> ours, Views.Group group, NextOpponent next) {
    }

    public record Method(Page page) {
    }

    /** Страница входа: куда вернуть после входа и текст ошибки (или null). */
    public record Login(String next, String error) {
    }

    // ---------------------------------------------------------------- отчёты

    public record TeamPage(Page page, Views.Roster roster, String playerBase) {
    }

    public record PlayerPage(Page page, Views.PlayerCard card, String teamName, String teamHref) {
    }

    public record MetaPage(Page page, Views.MetaView meta) {
    }

    public record DraftDataPage(Page page, Views.DraftData data, String planHtml) {
    }

    public record TournamentPage(Page page, Views.Tournament tournament, String backHref) {
    }

    public record GroupPage(Page page, Views.Group group, List<Link> teams) {
    }

    public record OpponentPage(Page page, String slug, String name, String planHtml, Views.Roster roster,
                               Views.Threats threats, Views.Tournament tournament) {
    }

    /** Контрпики: введённые имена героев (как в форме), «авто» ли позиция, ошибка ввода или null. */
    public record CountersPage(Page page, List<dota.analysis.AnalysisContext.HeroInfo> heroes, String first,
                               String second, boolean posAuto, dota.analysis.Counters.View view, String error) {
    }

    // ---------------------------------------------------------------- драфт

    /** poolJson — наши герои: id героя → кто из наших на нём играет (для предупреждения «баните своего»). */
    /** rostersJson — составы для слотов игроков: {"ours": [...], "<slug соперника>": [...]}, см. Squad.draftRoster. */
    public record DraftPage(Page page, String heroesJson, String opponentsJson, String defaultOpponent,
                            String orderJson, String poolJson, String rostersJson) {
    }
}
