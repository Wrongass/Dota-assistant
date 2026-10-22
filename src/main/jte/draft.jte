@import dota.web.Models.DraftPage
@param DraftPage model
@template.layout(page = model.page(), content = @`
    <div id="draft" data-heroes="${model.heroesJson()}" data-opponents="${model.opponentsJson()}"
         data-order="${model.orderJson()}" data-pool="${model.poolJson()}" data-default-opponent="${model.defaultOpponent()}">

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

        <div class="panel">
            <div id="ribbon" class="ribbon"></div>
            <div class="small muted ribbon-hint" id="ribbon-hint">Клик по сделанному ходу — исправить его.</div>
        </div>

        <div class="panel search-panel">
            <input type="search" id="search" placeholder="Герой: имя, начало имени, WK, вк, кунк… ↑↓ и Enter — внести ход"
                   autocomplete="off" autofocus>
            <ul id="suggest" class="suggest"></ul>
            <div id="warn" class="warn" hidden></div>
        </div>

        <div class="draft-board">
            <div class="panel side ours-side">
                <h3 class="ours">Мы</h3>
                <div class="small muted">Баны</div>
                <div id="our-bans" class="chips"></div>
                <div class="small muted">Пики — кто на каком герое <span class="hint">(клик — выбрать игрока)</span></div>
                <div id="our-picks"></div>
                <div id="our-remaining"></div>
            </div>
            <div class="panel side their-side">
                <h3 class="theirs">Они</h3>
                <div class="small muted">Баны</div>
                <div id="their-bans" class="chips"></div>
                <div class="small muted">Пики — кто на каком герое <span class="hint">(клик — выбрать игрока)</span></div>
                <div id="their-picks"></div>
                <div id="their-outlook"></div>
            </div>
        </div>

        <div class="draft-advice" id="advice">
            <div class="panel" id="panel-their">
                <div id="their-next-box">
                    <h3>Их следующий пик <span class="hint">шанс, что возьмут до конца драфта</span></h3>
                    <ol id="their-next" class="advice"></ol>
                </div>
                <div id="their-bans-box">
                    <h3 title="Наши герои у наших игроков, ещё не выбравших героя, плюс их привычка банить в турнире">
                        Что они забанят у нас <span class="hint" id="their-bans-left"></span></h3>
                    <ol id="their-likely-bans" class="advice"></ol>
                </div>
            </div>
            <div class="panel" id="panel-bans">
                <h3>Нам банить</h3>
                <ol id="bans" class="advice"></ol>
            </div>
            <div class="panel" id="panel-picks">
                <h3>Нам брать</h3>
                <ol id="picks" class="advice"></ol>
            </div>
        </div>

        <div class="panel" id="summary" hidden>
            <h3>Итог драфта <button id="copy" class="small-btn">Скопировать текстом</button> <span id="copied" class="muted small"></span></h3>
            <div class="grid2">
                <div>
                    <div class="small muted">Линии (по позициям расстановки; преимущество — по встречным парам героев)</div>
                    <table id="lanes"></table>
                </div>
                <div>
                    <div class="small muted">Их самые опасные герои</div>
                    <ol id="threats" class="advice"></ol>
                </div>
            </div>
        </div>

        <div class="panel">
            <h3>Все герои <span class="muted small">клик — внести в текущий ход</span></h3>
            <div id="grid" class="hero-grid"></div>
        </div>
        <p class="muted small">Порядок ходов Captains Mode — как в 39 матчах нашей лиги. Прогноз — статистика по data/
            (без сети). Бэктест на 6 матчах группы: реальный пик в топ-10 прогноза в 63% случаев, у простого бейзлайна
            «частые герои игроков» — 47%. <a href="/method">Методика</a></p>
    </div>
`, scripts = "<script src=\"/static/draft.js\"></script>")
