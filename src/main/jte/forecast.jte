@import dota.web.Models.ForecastPage
@param ForecastPage model
@template.layout(page = model.page(), content = @`
    <h1>Прогноз плей-офф</h1>
    @if(model.introHtml() == null)
        <div class="panel"><p>Прогноза пока нет: его считает <code>python tools/forecast/forecast.py</code>
            (файл <code>analysis/forecast/playoff.md</code>).</p></div>
    @else
        <div class="panel forecast">$unsafe{model.introHtml()}</div>
        @for(var s : model.sections())
            <details class="panel forecast">
                <summary><b>${s.title()}</b></summary>
                $unsafe{s.html()}
            </details>
        @endfor
    @endif
`)
