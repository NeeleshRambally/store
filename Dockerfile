# Multi-stage build: compile with a JDK, run the layered jar on distroless Java 17 as a non-root user.
FROM eclipse-temurin:17-jdk-jammy@sha256:73ed442231ec66cfe48cae0b263ce619a75ab96d162ab325f38fc0a269ee3aab AS build
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon resolveDependencies > /dev/null
COPY src ./src
COPY OpenAPI.yaml ./
RUN ./gradlew --no-daemon bootJar -x test \
    && java -Djarmode=tools -jar build/libs/store-*[!plain].jar extract --layers --launcher --destination extracted

FROM gcr.io/distroless/java17-debian13:nonroot@sha256:bb551796de0958c414f26020d4fabd0fb3d23687819f7dbef1b713fac0872448
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 65532:65532
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
EXPOSE 8080 8081
ENTRYPOINT ["/usr/bin/java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=30", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
