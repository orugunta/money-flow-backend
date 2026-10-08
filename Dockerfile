# Build stage: compile and extract the jar into layers for better image caching.
# Pinned for reproducible builds; bump deliberately.
FROM eclipse-temurin:21.0.12.1_1-jdk-resolute AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/money-flow-backend-*.jar extract --layers --launcher --destination target/extracted

# Runtime stage: JRE only, non-root user.
FROM eclipse-temurin:21.0.12.1_1-jre-resolute
RUN useradd --system --uid 1001 --no-create-home app
WORKDIR /app
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
