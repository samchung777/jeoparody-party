# syntax=docker/dockerfile:1
#
# App image. Packages the jar that already passed the tests (`mvn verify`), so the artifact we ship
# is exactly the artifact we tested. Build it after the jar exists:
#
#   ./mvnw -B verify                      # or package -DskipTests for a quick local image
#   docker build -t jeoparody-party .
#
# Uses Spring Boot's layered extraction so dependency layers are cached between builds and only the
# small application layer changes on most commits.

# Stage 1 ("extract"): a throwaway stage that unpacks the fat jar into Spring Boot's layers.
# Multi-stage build: nothing from this stage ships except the folders copied below.
FROM eclipse-temurin:21-jre AS extract
WORKDIR /builder
# The jar from `mvn verify` (in CircleCI it arrives through the workspace from the test job).
ARG JAR_FILE=target/jeoparody-party.jar
COPY ${JAR_FILE} application.jar
# Splits the jar into dependencies / spring-boot-loader / snapshot-dependencies / application folders.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Stage 2: the image that ships. A JRE (runtime only, no compiler) keeps it smaller and with less to patch.
FROM eclipse-temurin:21-jre
# CircleCI passes --build-arg GIT_SHA=$CIRCLE_SHA1, so every image records the commit it was built from.
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="jeoparody-party" \
      org.opencontainers.image.description="Jeopardy-style trivia party game" \
      org.opencontainers.image.source="https://github.com/samchung777/jeoparody-party" \
      org.opencontainers.image.revision="${GIT_SHA}"

# Run as an unprivileged user
RUN groupadd --system app && useradd --system --gid app --home-dir /application app
WORKDIR /application

# Copy layers from least to most often changed. Docker caches each COPY as a layer, so a normal commit
# only changes the last, small 'application' layer: faster builds and smaller pushes to Artifact Registry.
COPY --from=extract /builder/extracted/dependencies/ ./
COPY --from=extract /builder/extracted/spring-boot-loader/ ./
COPY --from=extract /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract /builder/extracted/application/ ./
USER app

# Size the heap from the container's memory limit (Cloud Run sets one); Cloud Run injects PORT.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    PORT=8080 \
    APP_VERSION=${GIT_SHA}
# Documents the port Tomcat listens on; Cloud Run sends traffic to $PORT (8080).
EXPOSE 8080
# Start the app. Spring Boot reads config from env vars set by Cloud Run (SPRING_PROFILES_ACTIVE, DB_USER, ...).
ENTRYPOINT ["java", "-jar", "application.jar"]
