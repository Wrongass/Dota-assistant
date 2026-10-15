@import dota.analysis.Views.Threats
@import dota.analysis.Fmt
@param Threats t
<h2>Пулы по позициям</h2>
<div class="panel table-wrap">
    <table>
        <thead><tr><th>Поз.</th><th>Игрок</th><th>Ранг</th><th>Кандидаты (пригодность; ▲ — сильный в патче)</th><th>В турнире</th></tr></thead>
        <tbody>
        @for(var p : t.pools())
            <tr><td>${p.pos()}</td><td>${p.nick()}</td><td>${Fmt.rankShort(p.rankTier())}</td>
                <td>@template.tag.pool(heroes = p.heroes())</td>
                <td>${p.tourney().isEmpty() ? "—" : Fmt.countList(p.tourney(), false)}</td></tr>
        @endfor
        </tbody>
    </table>
</div>

<h2>Самые опасные герои (кандидаты в баны)</h2>
<p class="muted small">Угроза = 60% умение игрока на герое + 40% сила героя в мете на его ранге; +0.1, если герой уже был в турнире.</p>
<div class="panel table-wrap">
    <table class="sortable">
        <thead><tr><th>#</th><th>Герой</th><th>Игрок</th><th class="num">Угроза</th><th class="num">Умение</th>
            <th class="num">Сила в мете</th><th>Игр всего / год</th><th class="num">WR за год</th><th>В турнире</th><th>Позиции героя</th></tr></thead>
        <tbody>
        @for(int i = 0; i < t.threats().size(); i++)
            !{var r = t.threats().get(i);}
            <tr><td>${i + 1}</td><td><b>${r.name()}</b></td><td>${r.nick()}</td><td class="num">${Fmt.dec(r.score())}</td>
                <td class="num">${Fmt.num(r.skill())}</td><td class="num">${Fmt.dec(r.meta())}</td>
                <td>${r.games()} / ${r.yearGames()}</td><td class="num">${Fmt.ratio(r.yearWins(), r.yearGames())}</td>
                <td>${r.tourneyPicks() == 0 ? "" : "×" + r.tourneyPicks()}</td><td>${r.positions()}</td></tr>
        @endfor
        </tbody>
    </table>
</div>

<div class="grid2">
    <div class="panel">
        <h3>Что они банят в турнире</h3>
        <p>${Fmt.countList(t.bans(), false)}</p>
        <p class="small">Из наших пулов уже банили: <b>${t.bannedFromOurPool().isEmpty() ? "ничего" : String.join(", ", t.bannedFromOurPool())}</b></p>
    </div>
    <div class="panel">
        <h3>Против кого они проигрывают</h3>
        <p class="muted small">Их матчи за 2 года, сглажено к их среднему (${Fmt.pct(t.avg())}); ✔ — герой есть в наших пулах.</p>
        <table>
            <thead><tr><th>Герой</th><th class="num">Их WR против</th><th class="num">Игр</th><th></th></tr></thead>
            <tbody>
            @for(var r : t.losses())
                <tr><td>${r.name()}</td><td class="num">${Fmt.pct(r.winrate())}</td><td class="num">${r.games()}</td>
                    <td class="strong">${r.inOurPool() ? "✔" : ""}</td></tr>
            @endfor
            </tbody>
        </table>
    </div>
</div>

<h2>Чем контрить их главных героев (из наших пулов)</h2>
<p class="muted small">Наши герои с опытом, у которых преимущество против их героя ≥ +2% (минимум 200 игр пары).</p>
<div class="panel table-wrap">
    <table>
        <thead><tr><th>Их герой</th><th>Игрок</th><th>Наши ответы</th></tr></thead>
        <tbody>
        @for(var r : t.counters())
            <tr><td><b>${r.name()}</b></td><td>${r.nick()}</td>
                <td>
                    @if(r.answers().isEmpty())—@endif
                    @for(var a : r.answers())
                        <span class="chip">${a.name()} <span class="strong">${Fmt.signedPct(a.advantage())}</span> <span class="muted">${String.join("/", a.players())}</span></span>
                    @endfor
                </td></tr>
        @endfor
        </tbody>
    </table>
</div>
