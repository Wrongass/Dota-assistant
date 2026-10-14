# Сайт Dota-помощника. data/, config/ и analysis/ монтируются томами (см. docker-compose.yml).
# Сборка: docker compose build · запуск: docker compose up -d → http://localhost:8070

FROM gradle:8.10.2-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN gradle --no-daemon dependencies -q > /dev/null
COPY src src
# тесты сверяют отчёты с реальными data/, которых в образе нет — их запускают локально (./gradlew build)
RUN gradle --no-daemon installDist -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/dota-assistant/lib /app/lib
EXPOSE 8070
# 0.0.0.0 внутри контейнера; наружу порт публикуется только на 127.0.0.1 (docker-compose.yml)
ENTRYPOINT ["java", "-Dstdout.encoding=UTF-8", "-cp", "/app/lib/*", "dota.web.WebApp", "--host", "0.0.0.0", "--port", "8070"]
