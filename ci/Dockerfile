# syntax=docker/dockerfile:1
#
# Custom CI image for the pipeline's test job (built and pushed by the pipeline itself).
# Starts from CircleCI's Java convenience image and adds:
#   - the MySQL client (scripts/wait-for-db.sh uses it to wait for the MySQL sidecar)
#   - jq (scripts/smoke-test.sh uses it when available)
#   - the project's Maven dependencies, pre-downloaded, so test runs don't fetch the internet
#
# Its tag is a fingerprint of this file + pom.xml (scripts/ci-image-tag.sh), so the pipeline only
# rebuilds it when one of those changes.
#
# Build from the repo root:  docker build -f ci/Dockerfile -t jeoparody-ci .

# CircleCI's convenience image: Java 21 JDK, Maven, git, Docker CLI, and a 'circleci' user; fast to pull on CircleCI.
FROM cimg/openjdk:21.0

# Installing OS packages needs root; we switch back to the unprivileged 'circleci' user right after.
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends mysql-client jq \
 && rm -rf /var/lib/apt/lists/*
USER circleci

# Warm the Maven cache with this project's dependencies and plugins.
WORKDIR /home/circleci/warmup
# Only pom.xml is copied (see .dockerignore), so the image depends on the dependency list, not the source code.
COPY --chown=circleci:circleci pom.xml .
RUN mvn -B -q dependency:go-offline \
 && mvn -B -q dependency:resolve-plugins \
 && rm -rf /home/circleci/warmup

# CircleCI's default working directory: `checkout` puts the repo here when the test job runs.
WORKDIR /home/circleci/project
