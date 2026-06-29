# 멀티스테이지 — 의존성 레이어를 소스와 분리해 캐싱(소스 변경 시 의존성 재다운로드 없음).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
