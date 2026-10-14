@import dota.analysis.Views.Roster
@import dota.analysis.Fmt
@param Roster roster
<div class="panel table-wrap">
    <table class="sortable">
        <thead><tr>
            <th>Поз.</th><th>Игрок</th><th>Steam-ник</th><th>Ранг</th><th class="num">MMR (оценка)</th>
            <th>Матчей всего / год / патч</th><th class="num">WR в патче</th><th>Captains Mode</th><th>Роль в команде</th><th>Ссылки</th>
        </tr></thead>
        <tbody>
        @for(var r : roster.rows())
            <tr>
                <td>${r.pos()}${r.inferred() ? "*" : ""}</td>
                <td><a href="/player/${r.accountId()}">${r.nick()}</a></td>
                <td>${r.personaName()}</td>
                <td>${Fmt.rank(r.rankTier())}</td>
                <td class="num">${Fmt.num(r.mmr())}</td>
                <td>${r.totalGames()} / ${r.yearGames()} / ${r.patchGames()}</td>
                <td class="num">${Fmt.ratio(r.patchWins(), r.patchGames())}</td>
                <td>${r.cmGames() == 0 ? "0" : r.cmGames() + " (" + Fmt.ratio(r.cmWins(), r.cmGames()) + ")"}</td>
                <td class="small">${r.note() == null ? "" : r.note()}</td>
                <td class="small"><a href="https://www.opendota.com/players/${r.accountId()}" target="_blank">OpenDota</a>
                    · <a href="https://ru.dotabuff.com/players/${r.accountId()}" target="_blank">Dotabuff</a></td>
            </tr>
        @endfor
        </tbody>
    </table>
    @if(roster.anyInferred())
        <p class="small muted">* позиция не указана в конфиге и определена по данным (доля игр на героях этой позиции).</p>
    @endif
</div>

<h2>На каких позициях реально играют (пабы за последний год)</h2>
<p class="muted small">Доля игр на героях, которых в патче играют на позиции N (по профилю героя, а не по факту матча).</p>
<div class="panel table-wrap">
    <table class="sortable">
        <thead><tr><th>Игрок</th><th>Поз. в команде</th><th class="num">1</th><th class="num">2</th><th class="num">3</th>
            <th class="num">4</th><th class="num">5</th><th>Линии в разобранных матчах (safe/mid/off/лес)</th></tr></thead>
        <tbody>
        @for(var r : roster.shares())
            <tr>
                <td>${r.nick()}</td><td>${r.pos()}</td>
                @for(double s : r.share())
                    <td class="num">${Fmt.pct(s)}</td>
                @endfor
                <td>${r.lanes()[0]}/${r.lanes()[1]}/${r.lanes()[2]}/${r.lanes()[3]}</td>
            </tr>
        @endfor
        </tbody>
    </table>
</div>

<h2>Сыгранность: матчей в одной команде (WR)</h2>
<div class="panel table-wrap">
    <table>
        <thead><tr><th></th>
            @for(var n : roster.nicks())<th>${n}</th>@endfor
        </tr></thead>
        <tbody>
        @for(int a = 0; a < roster.nicks().size(); a++)
            <tr><td><b>${roster.nicks().get(a)}</b></td>
                @for(int b = 0; b < roster.nicks().size(); b++)
                    !{int g = roster.together()[a][b]; int w = roster.togetherWins()[a][b];}
                    <td>${a == b ? "—" : g == 0 ? "0" : g + " (" + Fmt.ratio(w, g) + ")"}</td>
                @endfor
            </tr>
        @endfor
        </tbody>
    </table>
</div>
