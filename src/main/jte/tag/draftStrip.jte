@import dota.analysis.Views.Step
@import java.util.List
@param List<Step> steps
<div class="draft-strip" title="Драфт по порядку: синяя рамка — ходы этой команды, зачёркнуто — бан">
    @for(var s : steps)
        <span class="step ${s.pick() ? "pick" : "ban"} ${s.ours() ? "ours" : ""}">${s.pick() ? "P" : "b"} ${s.heroName()}</span>
    @endfor
</div>
