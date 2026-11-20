@import dota.web.Models.HeroesPage
@import dota.analysis.Fmt
@param HeroesPage model
!{var x = model.index();}
@template.layout(page = model.page(), content = @`
    <h1>Герои</h1>
    <p class="muted small">Страница героя: кто его контрит на линии и по игре, кого контрит он, с кем его брать, с какими
        предметами он чаще выигрывает и какие предметы хороши против него.
        @if(x.hasStratz())Данные — ${x.sample()}.@endif</p>
    <div class="panel hero-index">
        <input type="search" id="hero-filter" placeholder="Найти героя: имя, сленг, начало имени" autocomplete="off">
        <div class="hero-list" id="hero-grid">
            @for(var h : x.heroes())
                <a href="/hero/${h.id()}" data-name="${h.name()}"><img class="hero-ico" src="/img/icons/${h.id()}.png" alt="" loading="lazy"> ${h.name()}</a>
            @endfor
        </div>
        <p class="muted" id="hero-none" hidden>Не нашёл такого героя.</p>
    </div>

    <h2>Новые предметы патча @if(x.patch() != null)${x.patch()}@endif</h2>
    @if(x.noItemStats() && x.inventorySample() != null)
        <p class="muted small">Сводки STRATZ новые предметы ещё не считают, поэтому цифры — по своей выборке матчей:
            ${x.inventorySample()}. Прибавка — насколько чаще выигрывает герой с предметом, чем тот же герой в играх той же
            длины, собравший другую готовую вещь той же цены. Герой попадает в таблицу от 40 игр с предметом, поэтому
            «1 игра — 100%» сюда не попадает. Цветом выделена прибавка больше двух случайных разбросов. Это связь, а не
            доказательство: часть перекоса «у победителя больше золота» остаётся.</p>
    @else
        <p class="muted small">На ком новые предметы работают лучше и хуже всего. Прибавка — насколько чаще герой выигрывает с
            предметом, чем с другими предметами, купленными на той же минуте; сглажена к нулю по числу покупок. Учитываются
            только герои и позиции, где предмет купили от 500 раз, поэтому редкие покупки вроде «1 игра — 100%» сюда не попадают.
            Цветом выделена прибавка больше двух случайных разбросов.</p>
    @endif
    @if(x.newItems().isEmpty())
        <p class="muted">Нет данных: нужен разбор патча (tools/patch/build.py) и выгрузка STRATZ.</p>
    @elseif(x.noItemStats() && x.inventorySample() == null)
        <p class="warn">Статистики по новым предметам пока нет: STRATZ ещё не добавил их в свой справочник и не считает
            их покупки. Выгрузка подхватит их сама, когда добавит. Пока ниже только ручная оценка из разбора патча:
            это мнение, а не цифры. Свою выборку инвентарей можно собрать: python tools/items/fetch.py и analyze.py.</p>
    @endif
    <div class="grid3">
        @for(var it : x.newItems())
            <div class="panel table-wrap">
                <h3>@if(it.item().icon() != null)<img class="item-ico" src="${it.item().icon()}" alt="" loading="lazy">@endif ${it.item().name()}
                    @if(it.purchases() > 0)<span class="muted small">· ${it.purchases()} ${x.noItemStats() ? "игр в выборке" : "покупок"}</span>@endif</h3>
                @if(it.inventory() != null && x.noItemStats())
                    <p class="small">В целом: WR ${Fmt.pct(it.inventory().winrate())} при ожидаемом ${Fmt.pct(it.inventory().expected())},
                        прибавка <b class="${Math.abs(it.inventory().z()) < 2 ? "" : it.inventory().lift() >= 0 ? "strong" : "weak"}">${Fmt.signedPct(it.inventory().lift())}</b>.</p>
                @endif
                @if(it.reviewComment() != null)
                    <p class="small muted">${it.reviewComment()}</p>
                @endif
                @if(!it.reviewHeroes().isEmpty())
                    <p class="small"><span class="muted">По разбору патча подходит:</span>
                        @for(var h : it.reviewHeroes())<a class="nowrap" href="/hero/${h.id()}"><img class="hero-ico" src="/img/icons/${h.id()}.png" alt="" loading="lazy"> ${h.name()}</a> @endfor</p>
                @endif
                @if(it.best().isEmpty() && it.worst().isEmpty())
                    @if(!x.noItemStats() || x.inventorySample() != null)<p class="muted small">Пока ни на одном герое не набралось игр, оценивать рано.</p>@endif
                @else
                <table>
                    <thead><tr><th>Герой</th>@if(!x.noItemStats())<th class="num">Поз.</th>@endif<th class="num">Прибавка</th><th class="num">WR</th>@if(!x.noItemStats())<th class="num">Мин.</th>@endif<th class="num">${x.noItemStats() ? "Игр" : "Покупок"}</th></tr></thead>
                    <tbody>
                    @for(var u : it.best())
                        <tr><td><a href="/hero/${u.heroId()}"><img class="hero-ico" src="/img/icons/${u.heroId()}.png" alt="" loading="lazy"> ${u.name()}</a></td>
                            @if(!x.noItemStats())<td class="num">${u.pos()}</td>@endif
                            <td class="num ${Math.abs(u.z()) >= 2 ? "strong" : ""}">${Fmt.signedPct(u.lift())}</td>
                            <td class="num">${Fmt.pct(u.winrate())}</td>@if(!x.noItemStats())<td class="num">${Math.round(u.minute())}</td>@endif
                            <td class="num muted">${u.purchases()}</td></tr>
                    @endfor
                    @if(!it.worst().isEmpty())
                        <tr><td colspan="6" class="muted small">Хуже всего:</td></tr>
                        @for(var u : it.worst())
                            <tr><td><a href="/hero/${u.heroId()}"><img class="hero-ico" src="/img/icons/${u.heroId()}.png" alt="" loading="lazy"> ${u.name()}</a></td>
                                @if(!x.noItemStats())<td class="num">${u.pos()}</td>@endif
                                <td class="num ${Math.abs(u.z()) >= 2 ? "weak" : ""}">${Fmt.signedPct(u.lift())}</td>
                                <td class="num">${Fmt.pct(u.winrate())}</td>@if(!x.noItemStats())<td class="num">${Math.round(u.minute())}</td>@endif
                                <td class="num muted">${u.purchases()}</td></tr>
                        @endfor
                    @endif
                    </tbody>
                </table>
                @endif
            </div>
        @endfor
    </div>

    @if(!x.strongItems().isEmpty())
        <h2>Сильные и слабые предметы патча</h2>
        <p class="muted small">${x.inventorySample()}. Готовые предметы от 150 игр; прибавка — против того же героя в
            играх той же длины, собравшего другую готовую вещь той же цены (до 2500, 2500–4000, 4000–5500, дороже).
            Промежуточные предметы (Yasha, Maelstrom, Kaya и другие из рецептов) не показаны: в финальном инвентаре они
            значат «не успел дособрать». Дорогие поздние вещи всё равно немного завышены. На странице героя — его предметы.</p>
        <div class="grid3">
            @for(int k = 0; k < 2; k++)
                !{var list = k == 0 ? x.strongItems() : x.weakItems();}
                <div class="panel table-wrap">
                    <h3>${k == 0 ? "Чаще выигрывают" : "Реже выигрывают"}</h3>
                    <table class="sortable">
                        <thead><tr><th>Предмет</th><th class="num">Цена</th><th class="num">Прибавка</th><th class="num">WR</th><th class="num">Игр</th></tr></thead>
                        <tbody>
                        @for(var i : list)
                            <tr><td>@if(i.item().icon() != null)<img class="item-ico" src="${i.item().icon()}" alt="" loading="lazy">@endif ${i.item().name()}@if(i.fresh()) <span class="chip strong small">новый</span>@endif</td>
                                <td class="num">${i.cost()}</td>
                                <td class="num ${Math.abs(i.z()) < 2 ? "" : i.lift() >= 0 ? "strong" : "weak"}">${Fmt.signedPct(i.lift())}</td>
                                <td class="num">${Fmt.pct(i.winrate())}</td><td class="num muted">${i.games()}</td></tr>
                        @endfor
                        </tbody>
                    </table>
                </div>
            @endfor
        </div>
    @endif
`, scripts = "<script src=\"/static/hero-index.js\"></script>")
