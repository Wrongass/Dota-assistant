@import dota.web.Models.TeamPage
@param TeamPage model
@template.layout(page = model.page(), content = @`
    <h1>Состав: ${model.roster().teamName()}</h1>
    <p class="muted small">Данные на ${model.roster().today()}, патч ${model.roster().patch()}. Турбо не учитывается. Клик по заголовку — сортировка.</p>
    @template.tag.roster(roster = model.roster())
`)
