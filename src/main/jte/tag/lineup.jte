@import dota.analysis.Views.Lineup
@param Lineup lineup
@param String back
@param String error
<details class="panel lineup" open="${lineup.changed() || error != null}">
    <summary><b>Состав на игру</b>
        @if(lineup.changed())
            <span class="chip strong">изменён на сайте</span>
        @else
            <span class="muted small">по конфигу · нажмите, чтобы поставить замену</span>
        @endif
        @for(var pl : lineup.players())
            @if(pl.position() == 0)
                <span class="chip">запасной: ${pl.nick()}${pl.maps() > 0 ? " · карт в лиге: " + pl.maps() : ""}</span>
            @endif
        @endfor
    </summary>
    @if(error != null)
        <p class="warn">${error}</p>
    @endif
    <form method="post" action="/lineup" class="lineup-form">
        <input type="hidden" name="team" value="${lineup.team()}">
        <input type="hidden" name="back" value="${back}">
        @for(int pos = 1; pos <= 5; pos++)
            !{final int p = pos;}
            <label>Позиция ${pos}
                <select name="p${pos}">
                    @for(var pl : lineup.players())
                        <option value="${pl.accountId()}" selected="${pl.position() == p}">${pl.nick()}${pl.reserve() ? " (запасной)" : ""}${pl.configPosition() > 0 && pl.configPosition() != p ? " · в конфиге " + pl.configPosition() : ""}</option>
                    @endfor
                </select>
            </label>
        @endfor
        <span class="lineup-buttons">
            <button name="action" value="save" class="primary">Сохранить</button>
            @if(lineup.changed())
                <button name="action" value="reset" title="Вернуть состав из config/players.yaml">Вернуть по конфигу</button>
            @endif
        </span>
    </form>
    <p class="small muted">После сохранения новый состав сразу везде: прогнозы, пулы, угрозы, драфт. Запасные:
        @for(var pl : lineup.players())
            @if(pl.position() == 0)
                <span class="chip"><a href="/player/${pl.accountId()}">${pl.nick()}</a> @template.tag.rank(rankTier = pl.rankTier()) @template.tag.profiles(accountId = pl.accountId())
                    <span class="muted">${pl.maps() > 0 ? "карт в лиге: " + pl.maps() : "в лиге не играл"}</span></span>
            @endif
        @endfor
        @if(lineup.players().stream().noneMatch(pl -> pl.position() == 0))
            нет (добавьте игрока в config/players.yaml с <code>reserve: true</code>)
        @endif
    </p>
</details>
