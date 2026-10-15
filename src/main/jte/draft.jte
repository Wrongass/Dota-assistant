@import dota.web.Models.DraftPage
@param DraftPage model
@template.layout(page = model.page(), content = @`
    <div id="draft" data-heroes="${model.heroesJson()}" data-opponents="${model.opponentsJson()}"
         data-order="${model.orderJson()}" data-default-opponent="${model.defaultOpponent()}">

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
            <input type="search" id="search" placeholder="Герой: имя, начало имени, WK, вк… Enter — внести ход" autocomplete="off" autofocus>
            <span id="search-hint" class="muted small"></span>
        </div>

        <div class="draft-board">
            <div class="panel side ours-side">
                <h3 class="ours">Мы</h3>
                <div class="small muted">Баны</div>
                <div id="our-bans" class="chips"></div>
                <div class="small muted">Пики</div>
                <div id="our-picks"></div>
                <div id="our-remaining"></div>
            </div>
            <div class="panel side their-side">
                <h3 class="theirs">Они</h3>
                <div class="small muted">Баны</div>
                <div id="their-bans" class="chips"></div>
                <div class="small muted">Пики — кто на каком герое</div>
                <div id="their-picks"></div>
                <div id="their-remaining"></div>
            </div>
        </div>

        <div class="draft-advice">
            <div class="panel" id="panel-their-next">
                <h3>Их следующий пик</h3>
                <ol id="their-next" class="advice"></ol>
            </div>
            <div class="panel" id="panel-bans">
                <h3>Нам банить</h3>
                <ol id="bans" class="advice"></ol>
                <h3 class="small" title="Наши герои, которых возьмут наши игроки, ещё не выбравшие героя (турнир, последние игры, умение)">Что они, скорее всего, забанят у нас</h3>
                <div id="their-likely-bans" class="chips"></div>
            </div>
            <div class="panel" id="panel-picks">
                <h3>Нам брать</h3>
                <ol id="picks" class="advice"></ol>
            </div>
        </div>

        <div class="panel">
            <h3>Все герои <span class="muted small">клик — внести в текущий ход</span></h3>
            <div id="grid" class="hero-grid"></div>
        </div>
        <p class="muted small">Порядок ходов Captains Mode — как в 39 матчах нашей лиги. Прогноз — статистика по data/
            (без сети): бэктест на матчах группы — реальный пик в топ-10 прогноза в половине случаев. <a href="/method">Методика</a></p>
    </div>
`, scripts = "<script src=\"/static/draft.js\"></script>")
