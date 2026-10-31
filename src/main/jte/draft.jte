@import dota.web.Models.DraftPage
@param DraftPage model
@template.layout(page = model.page(), content = @`
    <div id="draft" class="no-hero-icons" data-heroes="${model.heroesJson()}" data-opponents="${model.opponentsJson()}"
         data-order="${model.orderJson()}" data-pool="${model.poolJson()}" data-rosters="${model.rostersJson()}"
         data-default-opponent="${model.defaultOpponent()}">

        <div class="panel draft-setup">
            <label>Соперник <select id="opponent"></select></label>
            <label>Первый ход
                <select id="first">
                    <option value="us">мы</option>
                    <option value="them">они</option>
                </select>
            </label>
            <span id="turn" class="turn"></span>
            <span class="muted small" id="timing"></span>
            <span class="spacer"></span>
            <button id="skip" title="Если в игре ход пропущен (таймаут)">Пропустить ход</button>
            <button id="undo" title="Ctrl+Z">Отменить ход</button>
            <button id="reset">Сбросить</button>
        </div>

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
                    <div id="lane-box" hidden>
                        <h3>На линию <span class="hint">их герои на линии известны · только герои, на которых игрок играет · подробности — наведите</span></h3>
                        <ul id="lane-options" class="lane-options"></ul>
                    </div>
                    <h3>Нам брать</h3>
                    <ol id="picks" class="advice"></ol>
                </div>

                <div class="panel" id="summary" hidden>
                    <h3>Итог драфта <button id="copy" class="small-btn">Скопировать текстом</button> <span id="copied" class="muted small"></span></h3>
                    <div class="small muted">Линии по позициям расстановки; преимущество — по встречным парам героев</div>
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
            </section>
        </div>

        <div class="panel">
            <h3>Все герои <span class="muted small">клик — внести в текущий ход</span></h3>
            <div id="grid" class="hero-grid"></div>
        </div>
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
