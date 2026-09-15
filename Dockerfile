# 兩階段建置：第一階段編譯，執行階段只帶 JRE。

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# 先抓相依套件，只改原始碼時不必重新下載。
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests


FROM eclipse-temurin:25-jre

# 從 GitHub 的 apt 套件庫安裝 gh CLI；GhCli 要從 PATH 找到它。
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

# 以非 root 身分執行；gh 從這個使用者的 home 讀設定。
RUN useradd --create-home --shell /usr/sbin/nologin mcp
USER mcp
WORKDIR /home/mcp

COPY --from=build --chown=mcp:mcp /src/target/project-mcp-*.jar app.jar

# 關掉 gh 自己的提示輸出；stderr 會被分類後回傳給 Client。
ENV GH_NO_UPDATE_NOTIFIER=1 \
    GH_NO_EXTENSION_UPDATE_NOTIFIER=1 \
    GH_TELEMETRY=false \
    GH_PROMPT_DISABLED=1 \
    NO_COLOR=1

# OutOfMemoryError 時直接結束，JVM 訊息寫到 stderr（stdout 承載協定）。
ENV JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError -XX:+DisplayVMOutputToStderr"

# exec 形式、不經 shell：stdout 承載 JSON-RPC。
ENTRYPOINT ["java", "-jar", "/home/mcp/app.jar"]
