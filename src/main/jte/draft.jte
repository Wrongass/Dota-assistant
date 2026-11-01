@import dota.web.Models.DraftPage
@param DraftPage model
@template.layout(page = model.page(), content = @`
    <div id="draft" class="no-hero-icons" data-heroes="${model.heroesJson()}" data-opponents="${model.opponentsJson()}"
         data-order="${model.orderJson()}" data-pool="${model.poolJson()}" data-rosters="${model.rostersJson()}"
         data-default-opponent="${model.defaultOpponent()}">

        <div class="panel draft-setup">
            <label>Соперник <select id="opponent"></select></label>
            <label title="Кто первым банит и первым пикает в Captains Mode">Первый пик
                <select id="first">
                    <option value="us">мы</option>
                    <option value="them">они</option>
                </select>
            </label>
            <span class="maps" id="maps" title="Серия bo2/bo3: у каждой карты свой драфт"></span>
            <span id="turn" class="turn"></span>
            <span class="muted small" id="timing"></span>
            <span class="turn-timer small" id="turn-timer" title="Сколько идёт текущий ход. В CM на ход 30 секунд, дальше — резерв"></span>
            <details class="legend">
                <summary title="Что значат подписи в советах">?</summary>
                <div class="legend-body small">
                    <p><b>контрит их X / связка с Y</b> — преимущество героя против их героя или в паре с нашим (выше среднего винрейта пары), по выборке патча.</p>
                    <p><b>линия ▲/▼ vs X: 62% / 14%</b> — сколько линий против этих героев выиграно / проиграно (STRATZ, Legend–Immortal).</p>
                    <p><b>пик-отказ: у них 30%</b> — шанс, что этого героя возьмут они, если не возьмём мы.</p>
                    <p><b>умение 64</b> — насколько игрок хорош на герое, 0–100 (ранг на герое + винрейт с учётом давности).</p>
                    <p><b>угроза 0.81</b> — (60% умение игрока + 40% сила героя в мете на его ранге) × свежесть: без игр за год — 60%.</p>
                    <p><b>▲ 52.9% / ▼</b> — герой сильный / слабый в патче на ранге игрока. <b>×2 в турнире</b> — брал в лиге.
                        <b>23% его игр</b> — доля героя в играх игрока за год и патч.</p>
                    <p><b>нам неудобен 49%</b> — наш винрейт против этого героя ниже обычного. <b>⚠ наш</b> — герой нашего игрока без героя.</p>
                    <p><b>2 мес: 9👥</b> — игр за 2 месяца, 👥 — в пати с командой, 🔥 — новый герой, ★ — сигнатурка.</p>
                    <p><b>свободен 87%</b> — вероятность, что игрок ещё не выбрал героя.</p>
                    <p><b>Состав (под командой)</b> — сколько героев с ролью по справочнику OpenDota: контроль (Disabler), инициация (Initiator), плотные (Durable), урон (Nuker), пуш (Pusher), керри (Carry). ⚠ — чего не хватает после 3+ пиков.</p>
                    <p><b>флекс: kren / Артист</b> — герой играется на нескольких позициях, и он есть у двух наших свободных игроков: можно взять раньше, а позицию решить позже.</p>
                    <p><b>Число у бана (100, 74…)</b> — важность бана относительно первого в списке: шанс их пика × вред для нас.</p>
                </div>
            </details>
            <span class="spacer"></span>
            <button id="skip" title="Если в игре ход пропущен (таймаут)">Пропустить ход</button>
            <button id="undo" title="Ctrl+Z">Отменить ход</button>
            <button id="reset">Сбросить</button>
            <button id="save-history" title="Сохранить драфт в «Историю драфтов» (на сервере)">В историю</button>
            <button id="share" title="Общий драфт: ссылка для команды, у всех по ссылке ходы видны в реальном времени">Поделиться</button>
            <span id="share-status" class="small muted"></span>
        </div>

        <div class="maps-past small" id="maps-past" hidden></div>

        <div class="panel ribbon-panel">
            <div id="ribbon" class="ribbon"></div>
            <div class="small muted ribbon-hint" id="ribbon-hint"></div>
        </div>

        <div class="draft-main">
            <section class="panel side ours-side">
                <h3 class="ours">Мы</h3>
                <div id="our-slots" class="slots"></div>
                <div class="small muted">Наши баны</div>
                <div id="our-bans" class="ban-row"></div>
                <div id="our-comp" class="comp small"></div>
            </section>

            <section class="center">
                <div class="panel search-panel">
                    <input type="search" id="search" placeholder="Герой: имя, начало имени, WK, вк, кунк… ↑↓ и Enter — внести ход"
                           autocomplete="off" autofocus>
                    <ul id="suggest" class="suggest"></ul>
                    <div id="warn" class="warn" hidden></div>
                </div>

                <div class="panel advice-panel" id="panel-their">
                    <div id="their-next-box">
                        <h3>Что они возьмут <span class="hint">шанс до конца драфта</span></h3>
                        <ol id="their-next" class="advice"></ol>
                    </div>
                    <div id="their-bans-box">
                        <h3 title="Наши герои у наших игроков, ещё не выбравших героя, плюс их привычка банить в турнире">
                            Что они забанят у нас <span class="hint" id="their-bans-left"></span></h3>
                        <ol id="their-likely-bans" class="advice"></ol>
                    </div>
                </div>
                <div class="panel advice-panel" id="panel-bans">
                    <h3>Нам банить</h3>
                    <ol id="bans" class="advice"></ol>
                </div>
                <div class="panel advice-panel" id="panel-picks">
                    <div id="pair-box" hidden>
                        <h3>Двойной пик <span class="hint">два хода подряд: пара разным игрокам, связка между ними</span></h3>
                        <ul id="pair-picks" class="lane-options"></ul>
                    </div>
                    <div id="lane-box" hidden>
                        <h3>На линию <span class="hint">их герои на линии известны · только герои, на которых игрок играет · подробности — наведите</span></h3>
                        <ul id="lane-options" class="lane-options"></ul>
                    </div>
                    <h3>Нам брать</h3>
                    <ol id="picks" class="advice"></ol>
                </div>

                <div class="panel" id="summary" hidden>
                    <h3>Итог драфта <button id="copy" class="small-btn">Скопировать текстом</button> <span id="copied" class="muted small"></span></h3>
                    <div id="summary-warn"></div>
                    <div id="overall" class="overall"></div>
                    <div class="small muted">Линии по позициям расстановки; преимущество — среднее по встречным парам героев, справа — самая заметная пара</div>
                    <table id="lanes"></table>
                    <div class="small muted">Их самые опасные герои</div>
                    <ol id="threats" class="advice"></ol>
                </div>
            </section>

            <section class="panel side their-side">
                <h3 class="theirs" id="their-title">Они</h3>
                <div id="their-slots" class="slots"></div>
                <div class="small muted">Их баны</div>
                <div id="their-bans" class="ban-row"></div>
                <div id="their-comp" class="comp small"></div>
            </section>
        </div>

        <details class="panel" id="grid-box" open>
            <summary><b>Все герои</b> <span class="muted small">клик — внести в текущий ход · нажмите, чтобы свернуть</span></summary>
            <div id="grid" class="hero-grid"></div>
        </details>
        <p class="muted small">Порядок ходов Captains Mode — как в 39 матчах нашей лиги. Прогноз — статистика по data/
            (без сети): умение, турнир, герои за 2 месяца и в пати со своей командой. Бэктест на 6 матчах группы: реальный
            пик в топ-5 прогноза в 47% случаев, в топ-10 — 67% (простой бейзлайн «частые герои игроков» — 32% и 47%).
            <a href="/method">Методика</a></p>

        <div id="toast" class="toast" hidden></div>

        <aside id="drawer" class="drawer" hidden>
            <button id="drawer-close" class="drawer-close" title="Закрыть (Esc)">✕</button>
            <div id="drawer-body"></div>
        </aside>
    </div>
`, scripts = "<script src=\"/static/draft.js\"></script>")
