# 一個 image，兩個進入點。預設是 stdio（開發時 Claude Desktop 以子行程啟動它），
# 覆蓋 CMD 就換成 HTTP（上線時 k8s 跑的那個）：
#
#   docker run -i --rm project-mcp:dev                       # stdio
#   docker run --rm -p 8080:8080 \
#     -e MCP_HTTP_HOST=0.0.0.0 \
#     -e MCP_HTTP_ALLOW_UNAUTHENTICATED=yes-i-know \
#     -e REQUEST_STATE_SECRET="$(openssl rand -base64 48)" \
#     project-mcp:dev dist/httpMain.js                        # HTTP
#
# HTTP 模式在容器裡一定要設 MCP_HTTP_HOST=0.0.0.0：預設只綁 loopback，-p 對映會連不進來。
#
# stdio 模式一定要給 `-i`（保留 stdin），沒有 stdin 就等於開機即關機；這個模式沒有
# healthcheck，因為沒有可以探測的端點，而往 stdout 寫探測結果會弄壞協定通道。
# HTTP 模式的探測端點是 /healthz 與 /readyz，由 k8s 的 probe 去問，不用 HEALTHCHECK。

FROM node:22-alpine AS build
WORKDIR /app

# 先只複製 manifest，讓相依層在原始碼改動時仍能命中快取。
COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build

FROM node:22-alpine
ENV NODE_ENV=production
WORKDIR /app

# 只裝 runtime 相依（MCP SDK、Node 轉接器、zod、pg）。typescript 與 tsx 留在建置階段。
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY --from=build /app/dist ./dist

# node image 內建的非 root 使用者。
USER node

# HTTP 模式聽的 port。EXPOSE 只是文件，不會真的開；stdio 模式用不到它。
EXPOSE 8080

# ENTRYPOINT 只到 node 為止，進入點放 CMD，部署時才換得掉。
ENTRYPOINT ["node"]
CMD ["dist/main.js"]
