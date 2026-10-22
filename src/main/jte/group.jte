@import dota.web.Models.GroupPage
@param GroupPage model
!{var g = model.group();}
@template.layout(page = model.page(), content = @`
    <h1>Группа</h1>
    <p class="muted">${g.name()}. Матчи между командами группы из лиги OpenDota ${g.leagueId()}.
        Переигровок отброшено (по всей лиге): ${g.remakes()}.</p>
    <div class="panel">
        <table>
            <thead><tr><th>Команда</th><th class="num">Карт</th><th class="num">Побед</th><th class="num">Поражений</th></tr></thead>
            <tbody>
            @for(var s : g.standings())
                <tr><td><b>${s.team()}</b></td><td class="num">${s.games()}</td><td class="num">${s.wins()}</td><td class="num">${s.games() - s.wins()}</td></tr>
            @endfor
            </tbody>
        </table>
        <p class="small">Соперники:
            @for(var t : model.teams())<a class="chip" href="${t.href()}">${t.label()}</a>@endfor</p>
    </div>
    <h2>Матчи</h2>
    <div class="panel table-wrap">
        <table>
            <thead><tr><th>Дата</th><th>Radiant</th><th>Dire</th><th>Победитель</th><th class="num">Мин</th><th>Пики Radiant</th><th>Пики Dire</th><th>Матч</th></tr></thead>
            <tbody>
            @for(var m : g.matches())
                @if(m.matchId() == 0)
                <tr><td class="small">${m.date()}</td><td>${m.radiant()}</td><td>${m.dire()}</td><td><b>${m.winner()}</b></td>
                    <td class="num">—</td><td class="small">—</td><td class="small">—</td><td class="small">${m.note()}</td></tr>
                @else
                <tr><td class="small">${m.date()}</td><td>${m.radiant()}</td><td>${m.dire()}</td><td><b>${m.winner()}</b></td>
                    <td class="num">${m.minutes()}</td><td class="small">${String.join(", ", m.radiantHeroes())}</td>
                    <td class="small">${String.join(", ", m.direHeroes())}</td>
                    <td class="small"><a href="https://www.opendota.com/matches/${m.matchId()}" target="_blank">OpenDota</a> ·
                        <a href="https://ru.dotabuff.com/matches/${m.matchId()}" target="_blank">Dotabuff</a></td></tr>
                @endif
            @endfor
            </tbody>
        </table>
    </div>
`)
