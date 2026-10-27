@import dota.web.Models.CountersPage
@import dota.analysis.Fmt
@param CountersPage model
!{var v = model.view();}
@template.layout(page = model.page(), content = @`
    <h1>Контрпики</h1>
    <form class="panel counters-form" method="get" action="/counters">
        <label>Их герой <input type="text" name="h" list="hero-names" value="${model.first()}" placeholder="Viper" autocomplete="off" autofocus></label>
        <label>и второй на линии <input type="text" name="h2" list="hero-names" value="${model.second()}" placeholder="необязательно" autocomplete="off"></label>
        <label>Наша позиция
            <select name="pos">
                <option value="0" selected="${model.posAuto()}">авто</option>
                @for(int p = 1; p <= 5; p++)
                    <option value="${p}" selected="${!model.posAuto() && v.position() == p}">${p}${p == 1 ? " — лёгкая" : p == 2 ? " — мид" : p == 3 ? " — сложная" : p == 4 ? " — саппорт сложной" : " — саппорт лёгкой"}</option>
                @endfor
            </select>
        </label>
        <button>Показать</button>
        <datalist id="hero-names">
            @for(var h : model.heroes())<option value="${h.name()}"></option>@endfor
        </datalist>
        <p class="muted small">Кто стоит против этих героев на линии вашей позиции и кто сильнее их по игре. Два героя —
            например, их хард и саппорт: линия считается против обоих. «Авто» — позиция, которая обычно стоит против
            первого героя (мид против мида, наша сложная против их керри, наша лёгкая против их оффлейна).</p>
    </form>

    @if(model.error() != null)
        <p class="warn">${model.error()}</p>
    @endif

    @if(!v.enemies().isEmpty())
        <p>Против <b>${String.join(" + ", v.enemyNames())}</b> на позиции <b>${v.position()}</b>.
            @if(v.hasLane())
                <span class="muted small">Линия — ${v.sample()}: доля выигранных / ничейных / проигранных линий
                    (около 10-й минуты, по формуле STRATZ) и винрейт в этих играх.</span>
            @else
                <span class="warn small">Нет данных о линии: задайте STRATZ_TOKEN и запустите выгрузку. Ниже — только «по игре» из OpenDota.</span>
            @endif
        </p>

        <div class="grid3">
            <div class="panel table-wrap">
                <h2>На линии</h2>
                <p class="muted small">Кто чаще выигрывает линию против них. «Перевес» — выиграно минус проиграно,
                    сглажено к обычному результату героя.</p>
                <table class="sortable">
                    <thead><tr><th>Герой</th><th class="num">Перевес</th><th class="num">W / D / L</th><th class="num">Игру</th><th class="num">Линий</th></tr></thead>
                    <tbody>
                    @for(var c : v.lane())
                        <tr><td>${c.name()}</td>
                            <td class="num ${c.laneAdv() >= 0 ? "strong" : "weak"}">${Fmt.signedPct(c.laneAdv())}</td>
                            <td class="num">${Math.round(100 * c.laneWin())} / ${Math.round(100 * c.laneDraw())} / ${Math.round(100 * c.laneLoss())}%</td>
                            <td class="num">${Fmt.pct(c.gameWin())}</td><td class="num muted">${c.laneMatches()}</td></tr>
                    @endfor
                    </tbody>
                </table>
            </div>
            <div class="panel table-wrap">
                <h2>По игре</h2>
                <p class="muted small">Кто сильнее их за всю игру, независимо от линии: преимущество в винрейте против
                    них сверх обычного.</p>
                <table class="sortable">
                    <thead><tr><th>Герой</th><th class="num">По игре</th><th class="num">Линия</th></tr></thead>
                    <tbody>
                    @for(var c : v.game())
                        <tr><td>${c.name()}</td>
                            <td class="num ${c.gameAdv() >= 0 ? "strong" : "weak"}">${Fmt.signedPct(c.gameAdv())}</td>
                            <td class="num muted">${c.laneMatches() == 0 ? "—" : Fmt.signedPct(c.laneAdv())}</td></tr>
                    @endfor
                    </tbody>
                </table>
            </div>
            <div class="panel table-wrap">
                <h2>Из наших героев
                    @if(v.player() != null)<span class="muted small">· <a href="/player/${v.accountId()}">${v.player()}</a>, позиция ${v.position()}</span>@endif
                </h2>
                <p class="muted small">Только герои, на которых игрок реально играет (5+ игр или 2+ за 2 месяца).
                    Порядок: линия + по игре + немного за опыт на герое.</p>
                @if(v.ours().isEmpty())
                    <p class="muted">Нет подходящих героев в его пуле.</p>
                @else
                <table class="sortable">
                    <thead><tr><th>Герой</th><th class="num">Линия</th><th class="num">По игре</th><th class="num">Игр</th><th class="num">WR</th><th class="num">2 мес.</th></tr></thead>
                    <tbody>
                    @for(var o : v.ours())
                        <tr><td>${o.hero().name()}</td>
                            <td class="num ${o.hero().laneMatches() == 0 ? "muted" : o.hero().laneAdv() >= 0 ? "strong" : "weak"}">${o.hero().laneMatches() == 0 ? "—" : Fmt.signedPct(o.hero().laneAdv())}</td>
                            <td class="num ${Double.isNaN(o.hero().gameAdv()) ? "muted" : o.hero().gameAdv() >= 0 ? "strong" : "weak"}">${Double.isNaN(o.hero().gameAdv()) ? "—" : Fmt.signedPct(o.hero().gameAdv())}</td>
                            <td class="num">${o.games()}${o.games() < 10 ? " · мало" : ""}</td>
                            <td class="num">${Double.isNaN(o.winrate()) ? "—" : Fmt.pct(o.winrate())}</td>
                            <td class="num">${o.recent60() == 0 ? "" : String.valueOf(o.recent60())}</td></tr>
                    @endfor
                    </tbody>
                </table>
                @endif
            </div>
        </div>
    @endif
`)
