# Two-stage build: dependencies and source in one stage, JRE in the runtime stage.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# Dependencies first, so a source-only change does not re-resolve them.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests


FROM eclipse-temurin:25-jre

# Install gh CLI from GitHub's apt repository; must be on PATH for GhCli.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates gpg \
 && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
      -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
 && chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
      > /etc/apt/sources.list.d/github-cli.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends gh \
 && apt-get purge -y gpg \
 && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*

# Run as non-root. gh reads its config from this user's home.
RUN useradd --create-home --shell /usr/sbin/nologin mcp
USER mcp
WORKDIR /home/mcp

COPY --from=build --chown=mcp:mcp /src/target/project-mcp-*.jar app.jar

# Suppress gh's own stderr output; stderr is classified and returned to the Client.
ENV GH_NO_UPDATE_NOTIFIER=1 \
    GH_NO_EXTENSION_UPDATE_NOTIFIER=1 \
    GH_TELEMETRY=false \
    GH_PROMPT_DISABLED=1 \
    NO_COLOR=1

# Exit on OutOfMemoryError with output to stderr (not stdout, which carries the protocol).
ENV JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError -XX:+DisplayVMOutputToStderr"

# Exec form, no shell: stdout carries JSON-RPC.
ENTRYPOINT ["java", "-jar", "/home/mcp/app.jar"]
