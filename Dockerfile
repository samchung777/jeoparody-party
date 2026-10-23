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

FROM eclipse-temurin:21-jre AS extract
WORKDIR /builder
ARG JAR_FILE=target/jeoparody-party.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="jeoparody-party" \
      org.opencontainers.image.description="Jeopardy-style trivia party game" \
      org.opencontainers.image.source="https://github.com/samchung777/jeoparody-party" \
      org.opencontainers.image.revision="${GIT_SHA}"

# Run as an unprivileged user
RUN groupadd --system app && useradd --system --gid app --home-dir /application app
WORKDIR /application

COPY --from=extract /builder/extracted/dependencies/ ./
COPY --from=extract /builder/extracted/spring-boot-loader/ ./
COPY --from=extract /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract /builder/extracted/application/ ./
USER app

# Size the heap from the container's memory limit (Cloud Run sets one); Cloud Run injects PORT.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    PORT=8080 \
    APP_VERSION=${GIT_SHA}
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
