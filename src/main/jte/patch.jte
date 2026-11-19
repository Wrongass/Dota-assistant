@import dota.web.Models.PatchPage
@param PatchPage model
@template.layout(page = model.page(), content = @`
    @if(model.version() == null)
        <h1>Патч</h1>
        <div class="panel"><p>Разбора патча пока нет: его собирает <code>python tools/patch/build.py --fetch</code>
            (файл <code>analysis/patch/{версия}/patch.json</code>).</p></div>
    @else
        <h1>Патч ${model.version()}</h1>
        <div id="patch-root" class="patch-page"><div class="panel muted">Загружаю разбор патча…</div></div>
    @endif
`, scripts = "<script src=\"/static/patchcard.js\"></script><script src=\"/static/patch.js\"></script>")
