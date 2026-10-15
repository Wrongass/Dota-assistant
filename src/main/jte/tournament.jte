@import dota.web.Models.TournamentPage
@param TournamentPage model
@template.layout(page = model.page(), content = @`
    <h1>Турнир: ${model.tournament().teamName()} <span class="small"><a href="${model.backHref()}">группа →</a></span></h1>
    @template.tag.tournament(t = model.tournament())
`)
