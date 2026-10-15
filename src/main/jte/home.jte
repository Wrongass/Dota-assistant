@import dota.web.Models.Home
@import dota.analysis.Fmt
@param Home model
@template.layout(page = model.page(), content = @`
    <div class="grid2">
        <div class="panel">
            @if(model.next() == null)
                <h2>Следующая игра</h2>
                <p class="muted">Соперник не задан: укажите <code>league.next_opponent</code> в config/players.yaml.</p>
            @else
                !{var n = model.next();}
                <h2>Следующая игра: <a href="/opponent/${n.slug()}">${n.name()}</a> <span class="muted">${n.record()}</span></h2>
                <p>
                    @for(var r : n.roster())
                        <span class="chip"><a href="/player/${r.accountId()}">${r.pos()} ${r.nick()}</a> <span class="muted">${Fmt.rankShort(r.rankTier())}</span></span>
                    @endfor
                </p>
                <p><b>Банят в турнире:</b> ${Fmt.countList(n.bans(), false)}</p>
                <p><b>Самые опасные:</b>
                    @for(var t : n.threats())
                        <span class="chip">${t.name()} <span class="muted">${t.nick()}</span></span>
                    @endfor
                </p>
                <p>
                    <a href="/opponent/${n.slug()}"><button>План на игру</button></a>
                    <a href="/draft?opponent=${n.slug()}"><button class="primary">Начать драфт ▶</button></a>
                </p>
            @endif
        </div>

        <div class="panel">
            <h2>Группа</h2>
            @if(model.group() == null)
                <p class="muted">Лига не задана в config/players.yaml.</p>
            @else
                <p class="muted small">${model.group().name()}</p>
                <table>
                    <thead><tr><th>Команда</th><th class="num">Карт</th><th class="num">Побед</th><th class="num">Поражений</th></tr></thead>
                    <tbody>
                    @for(var s : model.group().standings())
                        <tr><td>${s.team()}</td><td class="num">${s.games()}</td><td class="num">${s.wins()}</td><td class="num">${s.games() - s.wins()}</td></tr>
                    @endfor
                    </tbody>
                </table>
                <p class="small"><a href="/group">Все матчи группы →</a></p>
            @endif
        </div>
    </div>

    <div class="panel">
        <h2>Наш состав <span class="muted small">топ-3 под патч на позиции</span></h2>
        <table>
            <tbody>
            @for(var o : model.ours())
                <tr><td>${o.pos()}</td><td><a href="/player/${o.accountId()}"><b>${o.nick()}</b></a></td>
                    <td class="muted">${Fmt.rankShort(o.rankTier())}</td>
                    <td>@template.tag.pool(heroes = o.pool(), showFit = false)</td></tr>
            @endfor
            </tbody>
        </table>
        <p class="small"><a href="/team">Состав подробно →</a> · <a href="/draft-data">Наш драфт →</a></p>
    </div>

    @if(!model.page().warnings().isEmpty())
        <div class="panel warnings" id="warnings">
            <h2>Предупреждения о данных</h2>
            <ul>
                @for(var w : model.page().warnings())
                    <li>${w}</li>
                @endfor
            </ul>
            <p class="small muted">Данные обновляются командой <code>./gradlew run</code> (≈140 запросов к OpenDota, пара минут),
                затем кнопка «Перечитать данные».</p>
        </div>
    @endif
`)
