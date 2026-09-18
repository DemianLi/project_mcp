#!/bin/sh
# 建好 image，起一個 PostgreSQL，再把 MCP Inspector 接到容器裡的 Server 上。
#
#   npm run inspect:docker                       # Web UI
#   npm run inspect:docker -- --cli --method tools/list
#   npm run inspect:docker -- --cli --method tools/call \
#       --tool-name get_weather --tool-arg city=Taipei
#
# Server 從 compose 起，所以它拿得到 DATABASE_URL，碰資料庫的 Tool 不必另外設定。
# Inspector 的 target 只吃一個字，`docker compose run ...` 會被拆錯，所以這裡把啟動指令
# 包成一個暫時的腳本再指過去，結束時刪掉。
set -eu

INSPECTOR=@modelcontextprotocol/inspector@2.7.0

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
compose="$root/compose.yaml"

docker compose -f "$compose" build mcp
# 先把資料庫叫起來並等它 healthy。留著不收：Inspector 每連一次就會開一個新的 Server
# 容器，資料庫是它們共用的。
docker compose -f "$compose" up -d --wait db

workdir=$(mktemp -d)
cleanup() {
  rm -rf "$workdir"
  # Inspector 每連一次就用 `compose run` 開一個一次性容器。正常斷線時它會自己收掉，但
  # Inspector 被硬砍時收不掉，會留一個還在跑的容器霸住 network。`compose down` 不管
  # 一次性容器，`compose rm` 也不管，`compose ps -aq <service>` 倒是找得到它們。
  stray=$(docker compose -f "$compose" ps -aq mcp 2>/dev/null || true)
  if [ -n "$stray" ]; then
    # shellcheck disable=SC2086
    docker rm -f $stray >/dev/null 2>&1 || true
  fi
  # 資料庫留著，因為使用者可能還要繼續用。資料在 db-data 這個 volume 裡，`down` 不會清掉。
  printf '\n資料庫還開著。要收掉：docker compose down\n' >&2
}
trap cleanup EXIT INT TERM HUP

# 檔名會變成 Inspector 畫面上那台 Server 的名字，所以取一個看得懂的。
launcher="$workdir/project-mcp-docker.sh"
printf '#!/bin/sh\nexec docker compose -f %s run --rm -T mcp\n' "$compose" > "$launcher"
chmod +x "$launcher"

# `--cli` 必須排在 target 前面，所以從參數裡抽出來，其餘原樣往後傳。
mode=--web
count=$#
i=0
while [ "$i" -lt "$count" ]; do
  arg=$1
  shift
  if [ "$arg" = "--cli" ] || [ "$arg" = "--web" ]; then
    mode=$arg
  else
    set -- "$@" "$arg"
  fi
  i=$((i + 1))
done

# 不用 exec：exec 會取代這個 shell，上面的 trap 就永遠不會跑，暫存目錄會留一地。
# Inspector 對臨時指定的 target 預設走 legacy，那會送 initialize，本 Server 只會回 -32022。
status=0
npx -y "$INSPECTOR" "$mode" "$launcher" --protocol-era modern "$@" || status=$?
exit "$status"
