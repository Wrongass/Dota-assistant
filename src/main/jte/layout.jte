@import dota.web.Models.Page
@import gg.jte.Content
@param Page page
@param Content content
@param String scripts = ""
<!doctype html>
<html lang="ru">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${page.title()} · Dota-помощник</title>
    <link rel="stylesheet" href="/static/app.css">
    <script>try { if (localStorage.getItem("theme") === "light") document.documentElement.dataset.theme = "light"; } catch (e) {}</script>
</head>
<body>
<header class="top">
    <div class="topline">
        <a class="brand" href="/">Dota-помощник · ${page.teamName()}</a>
        <div class="status">
            <span>Патч ${page.patch()}</span>
            <span title="Самые старые данные игроков: с этого времени новых матчей хотя бы у одного игрока в статистике нет">игроки от ${page.playersFetched()}</span>
            <span title="Когда скачана выборка меты: синергии и контрпики">мета: ${page.metaFetched()}</span>
            @if(!page.warnings().isEmpty())
                <a href="/#warnings" title="Предупреждения о данных">⚠ ${page.warnings().size()}</a>
            @endif
            @if(page.authEnabled())
                <a href="/logout" title="Выйти на этом устройстве">Выйти</a>
            @endif
            <button type="button" class="theme-toggle" id="theme-toggle" title="Светлая или тёмная тема">◐</button>
            <form method="post" action="/reload">
                <button title="Перечитать data/ и config/ с диска без перезапуска (к OpenDota не обращается)">Перечитать данные</button>
            </form>
        </div>
    </div>
    <nav class="menu">
        <a href="/" class="${page.active().equals("home") ? "active" : null}">Главная</a>
        <a href="/team" class="${page.active().equals("team") ? "active" : null}">Команда</a>
        <a href="/draft-data" class="${page.active().equals("draft-data") ? "active" : null}">Наш драфт</a>
        <a href="/tournament" class="${page.active().equals("tournament") ? "active" : null}">Турнир</a>
        <a href="/group" class="${page.active().equals("group") ? "active" : null}">Группа</a>
        <details>
            <summary class="${page.active().equals("opponents") ? "active" : null}">Соперники ▾</summary>
            <div class="dropdown">
                @for(var o : page.opponents())
                    <a href="${o.href()}">${o.label()}</a>
                @endfor
            </div>
        </details>
        <a href="/meta" class="${page.active().equals("meta") ? "active" : null}">Мета</a>
        <a href="/counters" class="${page.active().equals("counters") ? "active" : null}">Контрпики</a>
        <a href="/method" class="${page.active().equals("method") ? "active" : null}">Методика</a>
        <a href="/draft" class="cta">Драфт ▶</a>
    </nav>
</header>
<main>
    ${content}
</main>
<script src="/static/sort.js"></script>
<script src="/static/heroes.js"></script>
$unsafe{scripts}
</body>
</html>
