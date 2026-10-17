# Multi-stage build: compile with a JDK, run the layered jar on a slim JRE as a non-root user.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test \
    && java -Djarmode=tools -jar build/libs/store-*[!plain].jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system app && useradd --system --gid app --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
