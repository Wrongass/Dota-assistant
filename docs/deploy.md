# Развёртывание на сервере

Решения и причины — [ADR 0004](decisions/0004-server-deploy.md). Итог: сайт по адресу `https://<домен>`,
вход по общему паролю команды, данные обновляются на сервере раз в сутки.

## Коротко для администратора сервера

- **Что это:** один Java-сайт в Docker (Javalin, Java 21) + по желанию Caddy для HTTPS. Базы данных нет — всё
  в папке `data/` (~150 МБ JSON с OpenDota).
- **Ресурсы:** ~300–500 МБ RAM, ~1 ГБ диска (образ + данные + кэш сборки).
- **Сеть наружу:** при сборке — Docker Hub, Maven Central, plugins.gradle.org; при работе — только
  `api.opendota.com` (раз в сутки, ≈140 запросов).
- **Порты:** сайт слушает только `127.0.0.1:8070`. Наружу — 80/443 через Caddy (шаг 4а) или через ваш прокси (шаг 4б).
- **Проверено** (Docker 29, Windows/WSL2 x86_64): сборка образа, вход по паролю, страницы и API, выгрузка
  данных контейнером `fetch`, конфиг Caddy. Реальный HTTPS-сертификат не проверялся — нужен домен.

## 0. Что нужно заранее

- Доступ на сервер по SSH и право запускать Docker (`docker compose version` работает).
- Домен (или поддомен) с A-записью на IP сервера — для HTTPS-сертификата.
- Свободные порты 80 и 443. Если их занимает прокси друга — см. шаг 4б.

## 1. Код на сервер

Вариант А — из GitHub (репозиторий приватный: нужен доступ на чтение или deploy key):

```bash
sudo mkdir -p /opt/dota-assistant && sudo chown $USER /opt/dota-assistant
git clone https://github.com/Wrongass/test2.git /opt/dota-assistant
cd /opt/dota-assistant
```

Вариант Б — из архива `dota-assistant-<версия>.zip` (собирается `deploy/make-bundle.sh`):

```bash
sudo mkdir -p /opt && cd /opt
unzip dota-assistant-*.zip           # распакуется в /opt/dota-assistant
cd /opt/dota-assistant
```

Обновление в варианте Б — новый архив распаковать поверх (`.env` и `data/` в архиве нет, они не затрутся).

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

Без `DOMAIN` в `.env` Caddy не стартует (в логах `docker compose logs caddy` — ошибка разбора Caddyfile).

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
