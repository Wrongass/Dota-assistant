#!/bin/sh
# Архив для передачи на сервер (например, в Telegram): всё из git на текущем коммите, без data/ и .env.
# Результат: dist/dota-assistant-<дата>-<коммит>.zip, распаковывается в папку dota-assistant/.
set -e
cd "$(dirname "$0")/.."
if [ -n "$(git status --porcelain)" ]; then
  echo "Есть незакоммиченные изменения — в архив попадёт только закоммиченное." >&2
fi
mkdir -p dist
name="dota-assistant-$(date +%Y%m%d)-$(git rev-parse --short HEAD)"
git archive --format=zip --prefix=dota-assistant/ -o "dist/$name.zip" HEAD
echo "dist/$name.zip"
