# The reference deployment for this Server, and the one docs/deploying.md's conditions were
# written against. Built and driven before being committed; see that file for what each of
# these lines is satisfying.
#
# Two stages so the image needs nothing on the host but Docker. The build stage is the only
# place Maven and a full JDK exist.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# Dependencies first, so a source-only change does not re-resolve them.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests


FROM eclipse-temurin:25-jre

# Condition 2 of docs/deploying.md: `gh` has to be on the PATH of the Server's own process.
# GhCli is a @Component built through its no-argument constructor, which hardcodes the name,
# and no property points anywhere else. Installed from GitHub's own apt repository rather
# than a distribution's, so the version is the CLI's own and can be pinned if it ever needs
# to be.
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

# Nothing here runs as root that does not have to. `gh` reads its configuration from this
# user's home, which is also condition 3's second route.
RUN useradd --create-home --shell /usr/sbin/nologin mcp
USER mcp
WORKDIR /home/mcp

COPY --from=build --chown=mcp:mcp /src/target/project-mcp-*.jar app.jar

# Everything `gh` would otherwise write to stderr for reasons of its own. On a failing call
# this Server hands the whole of that stderr to its classifier and then to the Client, where
# a model reads it -- so an upgrade notice is not cosmetic here. See docs/deploying.md.
ENV GH_NO_UPDATE_NOTIFIER=1 \
    GH_NO_EXTENSION_UPDATE_NOTIFIER=1 \
    GH_TELEMETRY=false \
    GH_PROMPT_DISABLED=1 \
    NO_COLOR=1

# The pair, and it has to be the pair. ExitOnOutOfMemoryError alone prints "Terminating due
# to java.lang.OutOfMemoryError" -- to stdout, which is the JSON-RPC stream. Measured, along
# with -XX:OnOutOfMemoryError, which prints four lines there. DisplayVMOutputToStderr moves
# the JVM's own output to stderr, where the specification says a stdio server may write.
#
# The Server halts itself on a fatal Error (ADR-0015) and this covers what happens outside
# that reach.
ENV JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError -XX:+DisplayVMOutputToStderr"

# Condition 5: stdout is the protocol. Exec form, no shell, no entrypoint script -- a single
# echo anywhere in front of this would corrupt the stream before the first message.
ENTRYPOINT ["java", "-jar", "/home/mcp/app.jar"]
