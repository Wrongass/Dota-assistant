@import dota.web.Models.DraftDataPage
@import dota.analysis.Fmt
@param DraftDataPage model
!{var d = model.data();}
@template.layout(page = model.page(), content = @`
    <h1>Наш драфт: ${d.teamName()}</h1>

    @if(model.planHtml() != null)
        <details class="panel plan" open>
            <summary><b>План драфта</b> <span class="muted small">analysis/team/draft_plan.md — пишется вручную</span></summary>
            $unsafe{model.planHtml()}
        </details>
    @endif

    <h2>Пулы по позициям (топ-6 по пригодности в патче)</h2>
    <div class="panel">
        <table>
            <tbody>
            @for(var p : d.pools())
                <tr><td>${p.pos()}</td><td><b>${p.nick()}</b></td><td>@template.tag.pool(heroes = p.heroes())</td></tr>
            @endfor
            </tbody>
        </table>
    </div>

    <div class="grid2">
        <div class="panel">
            <h2>Связки внутри наших пулов</h2>
            <p class="muted small">Герои из топ-10 пулов двух игроков (с опытом ≥ 3 игр), синергия ≥ +2%.</p>
            <table class="sortable">
                <thead><tr><th>Связка</th><th>Игроки</th><th class="num">Синергия</th><th class="num">WR пары</th><th class="num">Игр</th></tr></thead>
                <tbody>
                @for(var c : d.combos())
                    <tr><td>${c.pair().aName()} + ${c.pair().bName()}</td><td class="small">${c.players()}</td>
                        <td class="num strong">${Fmt.signedPct(c.pair().value())}</td><td class="num">${Fmt.pct(c.pair().winrate())}</td>
                        <td class="num">${c.pair().games()}</td></tr>
                @endfor
                </tbody>
            </table>
        </div>
        <div class="panel">
            <h2>Кандидаты в баны</h2>
            <p class="muted small">½ «мы проигрываем против» (≥ 40 игр) + ½ сила в мете. Герои из топ-6 наших пулов исключены.</p>
            <table class="sortable">
                <thead><tr><th>Герой</th><th class="num">Оценка</th><th class="num">Наш WR против</th><th class="num">WR в пабах</th><th class="num">Про пик+бан</th><th>Поз.</th></tr></thead>
                <tbody>
                @for(var b : d.bans())
                    <tr><td><b>${b.name()}</b></td><td class="num">${Fmt.dec(b.score())}</td>
                        <td class="num">${Double.isNaN(b.vsWinrate()) ? "—" : Fmt.pct(b.vsWinrate()) + " (" + b.vsGames() + ")"}</td>
                        <td class="num">${Fmt.pct(b.pubWinrate())}</td><td class="num">${Fmt.pct(b.proContest())}</td><td>${b.positions()}</td></tr>
                @endfor
                </tbody>
            </table>
        </div>
    </div>

    <div class="grid2">
        <div class="panel">
            <h2>Против кого мы проигрываем</h2>
            <p class="muted small">Матчи 5 игроков за 2 года, сглажено к нашему среднему (${Fmt.pct(d.avgVs())}).</p>
            <table class="sortable">
                <thead><tr><th>Герой соперника</th><th class="num">Наш WR против</th><th class="num">Игр</th><th class="num">WR в пабах (${d.rangeName()})</th></tr></thead>
                <tbody>
                @for(var r : d.nemesis())
                    <tr><td>${r.name()}</td><td class="num weak">${Fmt.pct(r.vsWinrate())}</td><td class="num">${r.games()}</td><td class="num">${Fmt.pct(r.pubWinrate())}</td></tr>
                @endfor
                </tbody>
            </table>
            <p class="small"><b>Против кого играем лучше всего:</b>
                @for(var h : d.bestAgainst())<span class="chip">${h.name()} <span class="strong">${Fmt.pct(h.value())}</span></span>@endfor</p>
        </div>
        <div class="panel">
            <h2>Чем контрят наших лучших героев</h2>
            <table>
                <thead><tr><th>Игрок</th><th>Наш герой</th><th>Опасные против него</th></tr></thead>
                <tbody>
                @for(var t : d.threats())
                    <tr><td>${t.nick()}</td><td><b>${t.name()}</b></td>
                        <td>@for(var h : t.threats())<span class="chip">${h.name()} <span class="weak">${Fmt.signedPct(h.value())}</span></span>@endfor</td></tr>
                @endfor
                </tbody>
            </table>
        </div>
    </div>
`)
