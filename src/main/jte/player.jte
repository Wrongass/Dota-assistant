@import dota.web.Models.PlayerPage
@import dota.analysis.Fmt
@param PlayerPage model
!{var c = model.card();}
@template.layout(page = model.page(), content = @`
    <h1>Позиция ${c.pos()} — ${c.nick()} <span class="muted small"><a href="${model.teamHref()}">${model.teamName()}</a></span></h1>
    <div class="panel">
        <p>Steam: <b>${c.personaName()}</b>, account_id ${c.accountId()} · @template.tag.profiles(accountId = c.accountId())</p>
        <p>Ранг: @template.tag.rank(rankTier = c.rankTier()) <b>${Fmt.rank(c.rankTier())}</b>, оценка MMR ${Fmt.num(c.mmr())}</p>
        <p>Матчей (без турбо): всего ${c.totalGames()}, за год ${c.yearGames()}, в патче ${c.patch()} — ${c.patchGames()}
            (WR ${Fmt.ratio(c.patchWins(), c.patchGames())}); Captains Mode: ${c.cmGames()}</p>
        @if(c.note() != null)<p>Роль: ${c.note()}</p>@endif
        <p class="muted small">Мета для этого игрока считается на рангах <b>${c.rangeName()}</b>. <a href="/method">Как считаются оценки</a></p>
    </div>

    <h2>Чем играет сейчас: 14 дней и 2 месяца</h2>
    @template.tag.form(form = c.form())

    <h2>Топ-${c.best().size()} героев, на которых играет лучше всего <span class="muted small">— «всегда может достать»</span></h2>
    <div class="panel table-wrap">
        <table class="sortable">
            <thead><tr><th>#</th><th>Герой</th><th class="num">Умение</th><th>Игр всего / год / патч</th><th class="num">WR всего</th>
                <th class="num">WR за год</th><th>Ранг на герое</th><th class="num">KDA</th><th>Поз. героя</th><th>В патче (${c.rangeName()})</th></tr></thead>
            <tbody>
            @for(int i = 0; i < c.best().size(); i++)
                !{var h = c.best().get(i);}
                <tr><td>${i + 1}</td><td><b>${h.name()}</b></td><td class="num">${Fmt.num(h.skill())}</td>
                    <td>${h.games()} / ${h.yearGames()} / ${h.patchGames()}</td><td class="num">${Fmt.pct(h.winrate())}</td>
                    <td class="num">${Fmt.pct(h.yearWinrate())}</td><td>${Fmt.topPercent(h.percentRank())}</td>
                    <td class="num">${Fmt.dec(h.kda())}</td><td>${h.positions()}</td>
                    <td class="${Fmt.labelClass(h.metaWinrate())}">${Fmt.pct(h.metaWinrate())} ${Fmt.label(h.metaWinrate())}</td></tr>
            @endfor
            </tbody>
        </table>
    </div>

    <h2>Топ-${c.fits().size()} героев под патч ${c.patch()} на позиции ${c.pos()}</h2>
    <p class="muted small">Герои, которых в патче играют на позиции ${c.pos()} (≥15% игр), по пригодности = сила в мете + умение игрока.</p>
    <div class="panel table-wrap">
        <table class="sortable">
            <thead><tr><th>#</th><th>Герой</th><th class="num">Пригодность</th><th class="num">Сила в мете</th><th class="num">WR на ранге</th>
                <th class="num">WR на поз. (про/high)</th><th class="num">Про пик+бан</th><th class="num">Умение игрока</th><th class="num">Игр у игрока</th><th>Отметка</th></tr></thead>
            <tbody>
            @for(int i = 0; i < c.fits().size(); i++)
                !{var f = c.fits().get(i);}
                <tr><td>${i + 1}</td><td><b>${f.name()}</b></td><td class="num">${Fmt.num(f.fit())}</td><td class="num">${Fmt.dec(f.meta())}</td>
                    <td class="num">${Fmt.pct(f.winrate())}</td><td class="num">${Fmt.pct(f.posWinrate())}</td>
                    <td class="num">${Fmt.pct(f.proContest())}</td><td class="num">${Fmt.num(f.skill())}</td><td class="num">${f.games()}</td>
                    <td class="${Fmt.labelClass(f.winrate())}">${f.mark()}</td></tr>
            @endfor
            </tbody>
        </table>
    </div>

    <div class="grid2">
        <div class="panel">
            <h3>Выделяются в патче на позиции ${c.pos()} (${c.rangeName()})</h3>
            <p><b class="strong">▲ Сильные:</b>
                @if(c.strong().isEmpty()) нет @endif
                @for(var h : c.strong())<span class="chip">${h.name()} ${Fmt.pct(h.value())}</span>@endfor</p>
            <p><b class="weak">▼ Слабые:</b>
                @if(c.weak().isEmpty()) нет @endif
                @for(var h : c.weak())<span class="chip">${h.name()} ${Fmt.pct(h.value())}</span>@endfor</p>
        </div>
        <div class="panel">
            <h3>Осторожно: из личного топа, но слабы в патче</h3>
            <p>@if(c.weakInPool().isEmpty())Нет.@endif
                @for(var h : c.weakInPool())<span class="chip weak">${h.name()} ${Fmt.pct(h.value())}</span>@endfor</p>
        </div>
    </div>
`)
