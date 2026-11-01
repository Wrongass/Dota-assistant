@import dota.web.Models.HistoryPage
@param HistoryPage model
@template.layout(page = model.page(), content = @`
    <h1>История драфтов</h1>
    <p class="muted small">Драфты, которыми поделились или которые сохранили кнопкой «В историю». Если в лиге есть карта нашей
        команды с теми же пиками (8 из 10 героев и больше), рядом — результат и ссылка на матч.</p>
    @if(!model.enabled())
        <p class="panel muted">История недоступна: сайт запущен без папки состояния.</p>
    @elseif(model.rows().isEmpty())
        <p class="panel muted">Пока пусто. На экране драфта нажмите «В историю» или «Поделиться».</p>
    @else
        <div class="panel table-wrap no-hero-icons">
            <table>
                <thead><tr><th>Когда</th><th>Соперник</th><th>Карта</th><th>Ходов</th><th>Наши пики</th><th>Их пики</th><th>Реальная карта</th><th></th></tr></thead>
                <tbody>
                @for(var r : model.rows())
                    <tr><td class="small">${r.updated()}</td><td>${r.opponent()}</td><td class="num">${r.map()}</td><td class="num">${r.moves()}</td>
                        <td>@for(var h : r.ourPicks())<span class="hist-hero" data-hero="${h}"></span>@endfor</td>
                        <td>@for(var h : r.theirPicks())<span class="hist-hero" data-hero="${h}"></span>@endfor</td>
                        <td>@if(r.matchId() != null)<span class="${r.won() ? "strong" : "weak"}">${r.won() ? "победа" : "поражение"}</span>
                            <span class="small muted">${r.matchDate()}</span>
                            <a href="https://www.opendota.com/matches/${r.matchId()}" target="_blank">OpenDota</a>
                            @else<span class="muted small">не найдена</span>@endif</td>
                        <td><a href="/draft?share=${r.code()}">открыть</a></td></tr>
                @endfor
                </tbody>
            </table>
        </div>
    @endif
`, scripts = "<script>loadHeroes().then(hs => { const by = new Map(hs.map(h => [String(h.id), h.name])); document.querySelectorAll('.hist-hero').forEach(s => { const n = by.get(s.dataset.hero) || '?'; s.innerHTML = heroIcon(+s.dataset.hero, n); s.title = n; }); });</script>")
