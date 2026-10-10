# Dota 2 assistant

Учебный ИИ-помощник для любительской команды (Captains Mode). Шаг 1: выгрузка сырых данных из OpenDota API.

## Команды

- Сборка и тесты: `./gradlew build`
- Выгрузка: `./gradlew run` (`--args="--refresh"` — перекачать всё)

Нужен JDK 21. Gradle сам находит его через toolchain (в т.ч. в `~/.jdks`).
