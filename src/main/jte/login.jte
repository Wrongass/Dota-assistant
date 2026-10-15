@import dota.web.Models.Login
@param Login model
<!doctype html>
<html lang="ru">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Вход · Dota-помощник</title>
    <link rel="stylesheet" href="/static/app.css">
</head>
<body>
<main class="login">
    <form method="post" action="/login" class="panel">
        <h1>Dota-помощник</h1>
        <p class="muted">Пароль команды</p>
        @if(model.error() != null)
            <p class="weak">${model.error()}</p>
        @endif
        <input type="hidden" name="next" value="${model.next()}">
        <input type="password" name="password" autocomplete="current-password" autofocus required>
        <button class="primary" type="submit">Войти</button>
        <p class="small muted">Вход запоминается на этом устройстве на 30 дней.</p>
    </form>
</main>
</body>
</html>
