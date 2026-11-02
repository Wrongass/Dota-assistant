@import dota.web.Models.ErrorPage
@param ErrorPage model
@template.layout(page = model.page(), content = @`
    <div class="panel error-page">
        <h1>${model.title()}</h1>
        <p>${model.message()}</p>
        <p class="small"><a href="/">На главную</a> · <a href="/draft">Драфт</a> · <a href="javascript:history.back()">Назад</a></p>
    </div>
`)
