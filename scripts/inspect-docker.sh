#!/bin/sh
# 建好 image，再把 MCP Inspector 接到容器裡的 Server 上。
#
#   npm run inspect:docker                       # Web UI
#   npm run inspect:docker -- --cli --method tools/list
#   npm run inspect:docker -- --cli --method tools/call \
#       --tool-name get_weather --tool-arg city=Taipei
#
# Inspector 的 target 只吃一個字，`docker run -i --rm ...` 會被拆錯，所以這裡把啟動指令
# 包成一個暫時的腳本再指過去，結束時刪掉。
set -eu

IMAGE=project-mcp:dev
INSPECTOR=@modelcontextprotocol/inspector@2.7.0

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
docker build -t "$IMAGE" "$root"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT INT TERM HUP
# 檔名會變成 Inspector 畫面上那台 Server 的名字，所以取一個看得懂的。
launcher="$workdir/project-mcp-docker.sh"
printf '#!/bin/sh\nexec docker run -i --rm %s\n' "$IMAGE" > "$launcher"
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
