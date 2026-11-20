@import dota.web.Models.HeroPage
@import dota.analysis.Fmt
@param HeroPage model
!{var v = model.view();}
@template.layout(page = model.page(), content = @`
    <p class="muted small"><a href="/heroes">← все герои</a></p>
    <h1 class="hero-title"><img class="hero-portrait" src="/img/heroes/${v.heroId()}.png" alt="" onerror="this.remove()"> ${v.name()}</h1>
    <p class="muted">${String.join(", ", v.roles())}
        @if(!v.positionShares().isEmpty())
            · позиции в патче:
            @for(var s : v.positionShares())
                <span class="chip" title="${s.games()} игр, винрейт ${Fmt.pct(s.winrate())}">${s.pos()} — ${Math.round(100 * s.share())}%, WR ${Fmt.pct(s.winrate())}</span>
            @endfor
        @elseif(!v.positions().equals("?"))
            · позиции: ${v.positions()}
        @endif
    </p>
    @if(v.patch() != null)
        <p class="panel small">Патч <a href="/patch">${v.patch().version()}</a>:
            @if(!Double.isNaN(v.patch().winrate()))
                винрейт в первые дни ${Fmt.pct(v.patch().winrate())} против ${Fmt.pct(v.patch().winratePrev())} в прошлом патче
                (<span class="${v.patch().delta() >= 0 ? "strong" : "weak"}">${Fmt.signedPct(v.patch().delta())}</span>, ${v.patch().games()} игр).
            @endif
            @if(v.patch().verdict() != null)
                <b>${v.patch().verdict().equals("up") ? "▲ стал сильнее" : v.patch().verdict().equals("down") ? "▼ стал слабее" : v.patch().verdict().equals("rework") ? "◆ переделан" : "• по ситуации"}</b>@if(v.patch().comment() != null): ${v.patch().comment()}@endif
            @endif
        </p>
    @endif
    <p class="muted small">
        @if(v.hasStratz())
            Линия, игра, союзники и предметы — ${v.sample()}.
        @else
            Данных STRATZ нет: «по игре» и союзники — по выборке OpenDota, линии и предметов нет.
        @endif
        Подобрать контрпик под конкретную нашу позицию и наших игроков — <a href="/counters?h=${v.name()}">Контрпики</a>.
    </p>

    <h2>Кто его контрит</h2>
    <div class="grid3">
        <div class="panel table-wrap">
            <h3>На линии</h3>
            <p class="muted small">Кто чаще выигрывает линию против ${v.name()}. Перевес — выигранные минус проигранные линии,
                сглажено к обычному результату героя; поз. — позиция контрпика.</p>
            @if(v.laneCounters().isEmpty())<p class="muted">Нет данных о линиях.</p>@else
            <table class="sortable">
                <thead><tr><th>Герой</th><th class="num">Поз.</th><th class="num">Перевес</th><th class="num">W / L</th><th class="num">Линий</th></tr></thead>
                <tbody>
                @for(var m : v.laneCounters())
                    <tr><td><a href="/hero/${m.heroId()}"><img class="hero-ico" src="/img/icons/${m.heroId()}.png" alt="" loading="lazy"> ${m.name()}</a></td>
                        <td class="num">${m.pos()}</td>
                        <td class="num ${m.laneAdv() >= 0 ? "strong" : "weak"}">${Fmt.signedPct(m.laneAdv())}</td>
                        <td class="num">${Math.round(100 * m.laneWin())} / ${Math.round(100 * m.laneLoss())}%</td>
                        <td class="num muted">${m.laneMatches()}</td></tr>
                @endfor
                </tbody>
            </table>
            @endif
        </div>
        <div class="panel table-wrap">
            <h3>По игре</h3>
            <p class="muted small">Кто чаще выигрывает игру против ${v.name()}: прибавка к винрейту сверх обычного для этой пары героев.</p>
            @if(v.gameCounters().isEmpty())<p class="muted">Нет данных.</p>@else
            <table class="sortable">
                <thead><tr><th>Герой</th><th class="num">По игре</th><th class="num">Игр</th></tr></thead>
                <tbody>
                @for(var m : v.gameCounters())
                    <tr><td><a href="/hero/${m.heroId()}"><img class="hero-ico" src="/img/icons/${m.heroId()}.png" alt="" loading="lazy"> ${m.name()}</a></td>
                        <td class="num ${m.gameAdv() >= 0 ? "strong" : "weak"}">${Fmt.signedPct(m.gameAdv())}</td>
                        <td class="num muted">${m.games()}</td></tr>
                @endfor
                </tbody>
            </table>
            @endif
        </div>
        <div class="panel">
            <h3>Предметы против него</h3>
            <p class="muted small">Ручной справочник по механикам (config/item_counters.yaml), не статистика: такой статистики нет ни у STRATZ, ни у OpenDota.</p>
            @if(v.goodItems().isEmpty() && v.badItems().isEmpty())
                <p class="muted">Для этого героя в справочнике ничего нет.</p>
            @endif
            @if(!v.goodItems().isEmpty())
                <p class="small"><b class="strong">Хорошо:</b></p>
                <ul class="item-advice">
                @for(var a : v.goodItems())
                    <li>@if(a.item().icon() != null)<img class="item-ico" src="${a.item().icon()}" alt="" loading="lazy">@endif <b>${a.item().name()}</b> — ${a.why()} <span class="muted small">(${a.theme()})</span></li>
                @endfor
                </ul>
            @endif
            @if(!v.badItems().isEmpty())
                <p class="small"><b class="weak">Плохо:</b></p>
                <ul class="item-advice">
                @for(var a : v.badItems())
                    <li>@if(a.item().icon() != null)<img class="item-ico" src="${a.item().icon()}" alt="" loading="lazy">@endif <b>${a.item().name()}</b> — ${a.why()}</li>
                @endfor
                </ul>
            @endif
        </div>
    </div>

    <h2>Кого контрит он</h2>
    <div class="grid3">
        <div class="panel table-wrap">
            <h3>На линии</h3>
            <p class="muted small">Против кого ${v.name()} чаще выигрывает линию; поз. — его позиция.</p>
            @if(v.laneVictims().isEmpty())<p class="muted">Нет данных о линиях.</p>@else
            <table class="sortable">
                <thead><tr><th>Против</th><th class="num">Поз.</th><th class="num">Перевес</th><th class="num">W / L</th><th class="num">Линий</th></tr></thead>
                <tbody>
                @for(var m : v.laneVictims())
                    <tr><td><a href="/hero/${m.heroId()}"><img class="hero-ico" src="/img/icons/${m.heroId()}.png" alt="" loading="lazy"> ${m.name()}</a></td>
                        <td class="num">${m.pos()}</td>
                        <td class="num ${m.laneAdv() >= 0 ? "strong" : "weak"}">${Fmt.signedPct(m.laneAdv())}</td>
                        <td class="num">${Math.round(100 * m.laneWin())} / ${Math.round(100 * m.laneLoss())}%</td>
                        <td class="num muted">${m.laneMatches()}</td></tr>
                @endfor
                </tbody>
            </table>
            @endif
        </div>
        <div class="panel table-wrap">
            <h3>По игре</h3>
            <p class="muted small">Против кого ${v.name()} чаще выигрывает игру.</p>
            @if(v.gameVictims().isEmpty())<p class="muted">Нет данных.</p>@else
            <table class="sortable">
                <thead><tr><th>Против</th><th class="num">По игре</th><th class="num">Игр</th></tr></thead>
                <tbody>
                @for(var m : v.gameVictims())
                    <tr><td><a href="/hero/${m.heroId()}"><img class="hero-ico" src="/img/icons/${m.heroId()}.png" alt="" loading="lazy"> ${m.name()}</a></td>
                        <td class="num ${m.gameAdv() >= 0 ? "strong" : "weak"}">${Fmt.signedPct(m.gameAdv())}</td>
                        <td class="num muted">${m.games()}</td></tr>
                @endfor
                </tbody>
            </table>
            @endif
        </div>
        <div class="panel table-wrap">
            <h3>С кем брать</h3>
            <p class="muted small">Союзники, с которыми ${v.name()} выигрывает чаще ожидаемого (от 100 совместных игр).</p>
            @if(v.allies().isEmpty())<p class="muted">Нет данных.</p>@else
            <table class="sortable">
                <thead><tr><th>Союзник</th><th class="num">Вместе</th><th class="num">Игр</th></tr></thead>
                <tbody>
                @for(var a : v.allies())
                    <tr><td><a href="/hero/${a.heroId()}"><img class="hero-ico" src="/img/icons/${a.heroId()}.png" alt="" loading="lazy"> ${a.name()}</a></td>
                        <td class="num ${a.advantage() >= 0 ? "strong" : "weak"}">${Fmt.signedPct(a.advantage())}</td>
                        <td class="num muted">${a.games()}</td></tr>
                @endfor
                </tbody>
            </table>
            @endif
        </div>
    </div>

    <h2>С какими предметами выигрывает</h2>
    <p class="muted small">Готовые предметы по позициям. Простой винрейт с предметом обманывает: дорогие предметы покупают в уже
        выигранных играх. Поэтому «прибавка» — насколько чаще герой выигрывает с этим предметом, чем с другими предметами,
        купленными на той же минуте; сглажена к нулю по числу покупок. Предметы, которые купили меньше 500 раз, не показаны.
        Цветом выделена прибавка больше двух случайных разбросов (наведите на число). У поздних дорогих предметов перекос
        до конца не убрать: на 40-й минуте их чаще покупает тот, кто уже впереди.</p>
    @if(!v.patchItems().isEmpty())
        <div class="panel">
            <p class="small"><b>Новые предметы патча для него</b> <span class="muted">· ручная оценка из <a href="/patch">разбора патча</a>, не статистика</span></p>
            <ul class="item-advice">
            @for(var a : v.patchItems())
                <li>@if(a.item().icon() != null)<img class="item-ico" src="${a.item().icon()}" alt="" loading="lazy">@endif <b>${a.item().name()}</b> — ${a.why()}</li>
            @endfor
            </ul>
        </div>
    @endif
    @if(v.items().isEmpty() && v.inventoryItems().isEmpty())
        <p class="muted">Данных о предметах нет (их даёт выгрузка STRATZ).</p>
    @endif
    <div class="grid3">
        @for(var p : v.items())
            <div class="panel table-wrap">
                <h3>Позиция ${p.pos()} <span class="muted small">· ${p.games()} игр, WR ${Fmt.pct(p.winrate())}</span></h3>
                <table class="sortable">
                    <thead><tr><th>Предмет</th><th class="num" title="Насколько чаще выигрывает, чем с другими предметами той же минуты">Прибавка</th><th class="num" title="Винрейт игр с этим предметом">WR</th><th class="num" title="Средняя минута покупки">Мин.</th><th class="num" title="Покупок по данным STRATZ">Покупок</th></tr></thead>
                    <tbody>
                    @for(var r : p.items())
                        <tr><td>@if(r.item().icon() != null)<img class="item-ico" src="${r.item().icon()}" alt="" loading="lazy">@endif ${r.item().name()}@if(r.fresh()) <span class="chip strong small" title="Новый предмет патча">новый</span>@endif</td>
                            <td class="num ${Double.isNaN(r.lift()) ? "muted" : Math.abs(r.z()) < 2 ? "" : r.lift() >= 0 ? "strong" : "weak"}" title="${Double.isNaN(r.z()) ? "" : Math.abs(r.z()) >= 2 ? "надёжно" : "слабый сигнал"}">${Double.isNaN(r.lift()) ? "—" : Fmt.signedPct(r.lift())}</td>
                            <td class="num">${Fmt.pct(r.winrate())}</td>
                            <td class="num">${Math.round(r.minute())}</td>
                            <td class="num muted">${r.purchases()}</td></tr>
                    @endfor
                    </tbody>
                </table>
            </div>
        @endfor
    </div>
    @if(!v.inventoryItems().isEmpty())
        <h3>По финальным инвентарям</h3>
        <p class="muted small">${v.inventorySample()}. Тут есть и новые предметы, которых сводки STRATZ пока не знают.
            Прибавка — против того же героя в играх той же длины, собравшего другую готовую вещь той же цены; от 40 игр с
            предметом. Промежуточные вещи (Yasha, Maelstrom, Kaya…) в минусе почти всегда: игра кончилась раньше, чем их
            дособрали.</p>
        <div class="panel table-wrap">
            <table class="sortable">
                <thead><tr><th>Предмет</th><th class="num" title="Насколько чаще выигрывает с предметом">Прибавка</th><th class="num">WR</th><th class="num" title="Ожидаемый винрейт по тем же героям с другой вещью той же цены">Ожидалось</th><th class="num" title="Доля игр героя с этим предметом">Доля игр</th><th class="num">Игр</th></tr></thead>
                <tbody>
                @for(var i : v.inventoryItems())
                    <tr><td>@if(i.item().icon() != null)<img class="item-ico" src="${i.item().icon()}" alt="" loading="lazy">@endif ${i.item().name()}@if(i.fresh()) <span class="chip strong small">новый</span>@endif@if(i.intermediate()) <span class="muted small">(компонент)</span>@endif</td>
                        <td class="num ${Math.abs(i.z()) < 2 ? "" : i.lift() >= 0 ? "strong" : "weak"}">${Fmt.signedPct(i.lift())}</td>
                        <td class="num">${Fmt.pct(i.winrate())}</td><td class="num muted">${Fmt.pct(i.expected())}</td>
                        <td class="num">${Math.round(100 * i.share())}%</td><td class="num muted">${i.games()}</td></tr>
                @endfor
                </tbody>
            </table>
        </div>
    @endif
`)
