# Развёртывание на сервере

Решения и причины — [ADR 0004](decisions/0004-server-deploy.md). Итог: сайт по адресу `https://<домен>`,
вход по общему паролю команды, данные обновляются на сервере раз в сутки.

## 0. Что нужно заранее

- Доступ на сервер по SSH и право запускать Docker (`docker compose version` работает).
- Домен (или поддомен) с A-записью на IP сервера — для HTTPS-сертификата.
- Свободные порты 80 и 443. Если их занимает прокси друга — см. шаг 4б.

## 1. Код на сервер

```bash
sudo mkdir -p /opt/dota-assistant && sudo chown $USER /opt/dota-assistant
git clone https://github.com/Wrongass/test2.git /opt/dota-assistant   # приватный репозиторий — через deploy key
cd /opt/dota-assistant
```

## 2. Настройки

```bash
cp .env.example .env
nano .env        # SITE_PASSWORD, DOMAIN, при желании OPENDOTA_API_KEY
chmod 600 .env
```

## 3. Сборка и данные

```bash
docker compose build                 # 2–4 минуты
docker compose run --rm fetch        # первая выгрузка с OpenDota, пара минут
```

## 4а. Запуск с Caddy (порты 80/443 свободны)

```bash
docker compose --profile https up -d
docker compose ps                    # web и caddy — running
curl -s https://$DOMAIN/health       # ok
```

## 4б. Запуск за уже имеющимся прокси (nginx у друга)

```bash
docker compose up -d                 # сайт на 127.0.0.1:8070
```

В конфиг nginx — сервер для домена с сертификатом и:

```nginx
location / {
    proxy_pass http://127.0.0.1:8070;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $remote_addr;
}
```

`X-Forwarded-Proto` нужен, чтобы cookie входа была только для HTTPS; `X-Forwarded-For` — для лимита попыток входа.

## 5. Обновление данных по расписанию

```bash
crontab -e
# каждый день в 05:00
0 5 * * * /opt/dota-assistant/deploy/update-data.sh >> /opt/dota-assistant/update.log 2>&1
```

Перед игрой вручную: `./deploy/update-data.sh`.

## 6. Обновление сайта (новый код, состав, планы)

```bash
cd /opt/dota-assistant
git pull
docker compose build && docker compose up -d     # если менялся код
docker compose restart web                       # если менялись только config/ или планы
```

## Проверка после развёртывания

- `https://<домен>/` без входа → страница входа; неверный пароль → «Неверный пароль».
- После входа — главная, «Выйти» в шапке; с телефона вход сохраняется 30 дней.
- `http://<домен>` перенаправляет на HTTPS (Caddy делает сам).
- Снаружи порт 8070 закрыт: `curl http://<IP>:8070` не отвечает.
