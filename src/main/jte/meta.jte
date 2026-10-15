@import dota.web.Models.MetaPage
@import dota.analysis.Fmt
@param MetaPage model
!{var m = model.meta();}
@template.layout(page = model.page(), content = @`
    <h1>Мета патча ${m.patch()}</h1>
    <p class="muted small">Данные на ${m.today()}. Винрейты в пабах — на рангах <b>${m.rangeName()}</b> (медиана нашей команды ±1).
        Про-сцена — ${m.proMatches()} матчей в выборке OpenDota.
        <input type="search" id="hero-filter" placeholder="Найти героя…" style="margin-left:8px"></p>

    <div class="tabs">
        @for(var p : m.positions())<a href="#pos${p.pos()}">Позиция ${p.pos()}</a>@endfor
        <a href="#pro">Про-сцена</a><a href="#synergy">Связки</a><a href="#counters">Контрпики</a>
    </div>

    <div class="grid-wide">
    @for(var p : m.positions())
        <div class="panel table-wrap" id="pos${p.pos()}">
            <h2>Позиция ${p.pos()}</h2>
            <table class="sortable filterable">
                <thead><tr><th>#</th><th>Герой</th><th class="num">Сила в мете</th><th class="num">WR в пабах</th><th class="num">Пикрейт</th>
                    <th class="num">WR на поз.</th><th class="num">Про пик+бан</th></tr></thead>
                <tbody>
                @for(int i = 0; i < p.top().size(); i++)
                    !{var r = p.top().get(i);}
                    <tr><td>${i + 1}</td><td><b>${r.name()}</b></td><td class="num">${Fmt.dec(r.strength())}</td>
                        <td class="num ${Fmt.labelClass(r.winrate())}">${Fmt.pct(r.winrate())}</td><td class="num">${Fmt.pct(r.pickRate())}</td>
                        <td class="num">${Fmt.pct(r.posWinrate())}</td><td class="num">${Fmt.pct(r.proContest())}</td></tr>
                @endfor
                </tbody>
            </table>
            <p class="small"><b class="weak">▼ Слабые:</b>
                @if(p.weak().isEmpty()) нет @endif
                @for(var h : p.weak())<span class="chip">${h.name()} ${Fmt.pct(h.value())}</span>@endfor</p>
        </div>
    @endfor
    </div>

    <h2 id="pro">Про-сцена: самые оспариваемые герои (пик + бан)</h2>
    <div class="panel table-wrap">
        <table class="sortable filterable">
            <thead><tr><th>#</th><th>Герой</th><th class="num">Пик+бан</th><th class="num">Пики</th><th class="num">Баны</th><th class="num">WR в про</th><th>Позиции</th></tr></thead>
            <tbody>
            @for(int i = 0; i < m.pro().size(); i++)
                !{var r = m.pro().get(i);}
                <tr><td>${i + 1}</td><td><b>${r.name()}</b></td><td class="num">${Fmt.pct(r.contest())}</td><td class="num">${r.picks()}</td>
                    <td class="num">${r.bans()}</td><td class="num">${Fmt.pct(r.proWinrate())}</td><td>${r.positions()}</td></tr>
            @endfor
            </tbody>
        </table>
    </div>

    <div class="grid2">
        <div class="panel" id="synergy">
            <h2>Лучшие связки</h2>
            <p class="muted small">Синергия = винрейт пары − среднее базовых винрейтов героев. Минимум 400 игр.</p>
            <table class="sortable filterable">
                <thead><tr><th>Связка</th><th class="num">Синергия</th><th class="num">WR пары</th><th class="num">Игр</th></tr></thead>
                <tbody>
                @for(var r : m.synergies())
                    <tr><td>${r.aName()} + ${r.bName()}</td><td class="num strong">${Fmt.signedPct(r.value())}</td>
                        <td class="num">${Fmt.pct(r.winrate())}</td><td class="num">${r.games()}</td></tr>
                @endfor
                </tbody>
            </table>
        </div>
        <div class="panel" id="counters">
            <h2>Самые жёсткие контрпики</h2>
            <p class="muted small">Преимущество = винрейт против героя − ожидаемый. Минимум 400 игр.</p>
            <table class="sortable filterable">
                <thead><tr><th>Герой</th><th>контрит</th><th class="num">Преимущество</th><th class="num">WR</th><th class="num">Игр</th></tr></thead>
                <tbody>
                @for(var r : m.counters())
                    <tr><td>${r.aName()}</td><td>${r.bName()}</td><td class="num strong">${Fmt.signedPct(r.value())}</td>
                        <td class="num">${Fmt.pct(r.winrate())}</td><td class="num">${r.games()}</td></tr>
                @endfor
                </tbody>
            </table>
        </div>
    </div>
`, scripts = "<script>document.getElementById('hero-filter').addEventListener('input', e => { const q = e.target.value.toLowerCase(); document.querySelectorAll('table.filterable tbody tr').forEach(r => r.style.display = r.innerText.toLowerCase().includes(q) ? '' : 'none'); });</script>")
