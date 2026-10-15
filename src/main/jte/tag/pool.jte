@import dota.analysis.Views.PoolEntry
@import dota.analysis.Fmt
@import java.util.List
@param List<PoolEntry> heroes
@param boolean showFit = true
@for(var h : heroes)
    <span class="chip ${h.strong() ? "strong" : ""}" title="пригодность ${Fmt.num(h.fit())}${h.strong() ? ", сильный в патче" : ""}">${h.name()}@if(showFit) <span class="muted">${Fmt.num(h.fit())}</span>@endif@if(h.strong()) ▲@endif</span>
@endfor
