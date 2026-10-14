#!/bin/sh
# Обновить данные OpenDota и перезапустить сайт. Для cron, например ежедневно в 05:00:
#   0 5 * * * /opt/dota-assistant/deploy/update-data.sh >> /opt/dota-assistant/update.log 2>&1
set -e
cd "$(dirname "$0")/.."
date
docker compose run --rm fetch
docker compose restart web
