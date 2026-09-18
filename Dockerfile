# stdio 的 MCP Server：Client 以子行程啟動它，因此這個 image 不開任何 port，
# 也沒有 healthcheck——沒有可以探測的端點，而往 stdout 寫探測結果會弄壞協定通道。
#
# 執行時一定要給 `-i`（保留 stdin）。沒有 stdin 就等於開機即關機。

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

# 只裝 runtime 相依（目前是 pg）。typescript 與 tsx 留在建置階段。
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY --from=build /app/dist ./dist

# node image 內建的非 root 使用者。
USER node

ENTRYPOINT ["node", "dist/main.js"]
