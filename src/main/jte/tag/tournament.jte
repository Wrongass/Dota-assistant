@import dota.analysis.Views.Tournament
@import dota.analysis.Fmt
@param Tournament t
<p class="muted">Лига OpenDota ${t.leagueId()}, матчи с начала сезона. Сыграно карт: ${t.matches().size()}, побед: ${t.wins()}.
    @if(t.hadRemakes()) Переигровки (пустая статистика) не учитываются.@endif</p>

<div class="panel">
    <p><b>Пики команды</b> <span class="muted">(в скобках — победы)</span>: ${Fmt.countList(t.picks(), true)}</p>
    <p><b>Баны команды:</b> ${Fmt.countList(t.bans(), false)}</p>
    <p><b>Банят против команды</b> <span class="muted">(чего боятся соперники)</span>: ${Fmt.countList(t.bansAgainst(), false)}</p>
    <p><b>Пики соперников</b> <span class="muted">(в скобках — их победы)</span>: ${Fmt.countList(t.enemyPicks(), true)}</p>
</div>

@for(var m : t.matches())
    <div class="panel">
        <h3>${m.date()} — против ${m.opponent()}: <span class="${m.won() ? "strong" : "weak"}">${m.won() ? "победа" : "поражение"}</span></h3>
        <p class="small muted">${m.radiant() ? "Radiant" : "Dire"}, ${m.minutes()} мин ·
            <a href="https://www.opendota.com/matches/${m.matchId()}" target="_blank">OpenDota</a> ·
            <a href="https://ru.dotabuff.com/matches/${m.matchId()}" target="_blank">Dotabuff</a></p>
        @template.tag.draftStrip(steps = m.draft())
        <div class="grid2" style="margin-top:8px">
            <table>
                <thead><tr><th>Игрок</th><th>Герой</th><th>Линия</th><th class="num">GPM</th><th>K/D/A</th></tr></thead>
                <tbody>
                @for(var p : m.players())
                    <tr><td>${p.nick()}</td><td>${p.heroName()}</td><td>${Fmt.lane(p.laneRole())}</td>
                        <td class="num">${p.gpm()}</td><td>${p.kills()}/${p.deaths()}/${p.assists()}</td></tr>
                @endfor
                </tbody>
            </table>
            <table>
                <thead><tr><th>Соперник</th><th>Герой</th><th>Линия</th><th class="num">GPM</th><th>K/D/A</th></tr></thead>
                <tbody>
                @for(var p : m.enemies())
                    <tr><td>${p.nick()}</td><td>${p.heroName()}</td><td>${Fmt.lane(p.laneRole())}</td>
                        <td class="num">${p.gpm()}</td><td>${p.kills()}/${p.deaths()}/${p.assists()}</td></tr>
                @endfor
                </tbody>
            </table>
        </div>
    </div>
@endfor
