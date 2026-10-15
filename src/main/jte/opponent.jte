@import dota.web.Models.OpponentPage
@param OpponentPage model
@template.layout(page = model.page(), content = @`
    <h1>${model.name()}
        <a href="/draft?opponent=${model.slug()}"><button class="primary">Начать драфт ▶</button></a></h1>

    @if(model.planHtml() != null)
        <details class="panel plan" open>
            <summary><b>План на игру</b> <span class="muted small">game_plan.md — пишется вручную</span></summary>
            $unsafe{model.planHtml()}
        </details>
    @else
        <p class="panel muted">Плана на игру пока нет: analysis/opponents/${model.slug()}/game_plan.md.</p>
    @endif

    @template.tag.threats(t = model.threats())

    @if(model.tournament() != null)
        <h2>Их турнир</h2>
        @template.tag.tournament(t = model.tournament())
    @endif

    <h2>Состав</h2>
    @template.tag.roster(roster = model.roster())
`)
