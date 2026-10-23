@import dota.analysis.Views.Form
@import dota.analysis.Fmt
@param Form form
<div class="panel table-wrap">
    <p class="muted small">На ${form.asOf()}: за 14 дней ${form.games14()} игр, за 2 месяца ${form.games60()}
        (из них с игроками своей команды — ${form.team60()}), Турбо за 2 месяца — ${form.turbo60()} (в игры не входит).
        <span title="Много игр за 14 дней, но в топе «играет лучше всего» героя нет">🔥 новый</span> ·
        <span title="Герой из топа «играет лучше всего»">★ сигнатурка</span> ·
        <span title="Играл на нём в пати с игроками своей команды">👥 с командой</span></p>
    <table class="sortable">
        <thead><tr><th>Герой</th><th class="num">14 дней</th><th class="num">WR</th><th class="num">2 месяца</th><th class="num">WR</th>
            <th class="num">Турбо</th><th class="num">С командой</th><th>С кем</th><th class="num">KDA</th><th>Последняя</th></tr></thead>
        <tbody>
        @for(var h : form.heroes())
            <tr><td><b>${h.name()}</b>@if(h.fresh()) 🔥@endif@if(h.signature()) ★@endif@if(h.team60() > 0) 👥@endif</td>
                <td class="num">${h.games14()}</td><td class="num">${Fmt.ratio(h.wins14(), h.games14())}</td>
                <td class="num">${h.games60()}</td><td class="num">${Fmt.ratio(h.wins60(), h.games60())}</td>
                <td class="num">${h.turbo60() == 0 ? "" : String.valueOf(h.turbo60())}</td>
                <td class="num">${h.team60() == 0 ? "" : String.valueOf(h.team60())}</td>
                <td class="small">${String.join(", ", h.mates())}</td>
                <td class="num">${h.games60() == 0 ? "—" : Fmt.dec(h.kda60())}</td><td class="small">${h.lastPlayed()}</td></tr>
        @endfor
        </tbody>
    </table>
</div>
<details class="panel">
    <summary><b>Последние ${form.matches().size()} матчей</b></summary>
    <table>
        <thead><tr><th>Дата</th><th>Герой</th><th>Итог</th><th>K/D/A</th><th>Режим</th><th>Пати</th><th>Свои в матче</th><th>Матч</th></tr></thead>
        <tbody>
        @for(var m : form.matches())
            <tr><td class="small">${m.date()}</td><td>${m.hero()}</td>
                <td class="${m.win() ? "strong" : "weak"}">${m.win() ? "победа" : "поражение"}</td>
                <td>${m.kda()}</td><td class="small">${m.mode()}</td>
                <td class="num">${m.partySize() > 1 ? String.valueOf(m.partySize()) : "соло"}</td>
                <td class="small">${String.join(", ", m.mates())}</td>
                <td class="small"><a href="https://ru.dotabuff.com/matches/${m.matchId()}" target="_blank">Dotabuff</a></td></tr>
        @endfor
        </tbody>
    </table>
</details>
