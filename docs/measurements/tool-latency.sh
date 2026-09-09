#!/usr/bin/env bash
#
# What one Tool call actually costs, end to end, against real GitHub.
#
# Drives the packaged jar over its own stdio and reads the number back out of the
# Server's own trace line rather than timing it from the shell: `durationMs` covers the
# whole Tool body -- the `gh` round trip, the parse, the serialise -- which is what the
# Client waits for. See docs/adr/0013-what-a-call-leaves-behind.md.
#
# Produces the table in docs/deploying.md under "Two clocks".
#
# Reads only. Every Tool it calls is read-only; nothing here writes to GitHub.
#
# Requires: a JDK 25 (JAVA_HOME or `java` on PATH), python3, an authenticated `gh`,
# and `mvn package` already run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
OUT="${OUT:-$ROOT/target/measurements}"
SAMPLES="${SAMPLES:-5}"

# The repositories the numbers in the documents were taken against. Override to measure
# your own -- the shapes matter more than the names: one small repository and one with a
# hundred open issues and a long comment thread.
SMALL_OWNER="${SMALL_OWNER:-DemianLi}"
SMALL_REPO="${SMALL_REPO:-project_mcp}"
SMALL_ISSUE="${SMALL_ISSUE:-1}"
SMALL_THREAD="${SMALL_THREAD:-32}"
BIG_OWNER="${BIG_OWNER:-modelcontextprotocol}"
BIG_REPO="${BIG_REPO:-modelcontextprotocol}"
BIG_THREAD="${BIG_THREAD:-3350}"

JAR="$(ls -t "$ROOT"/target/*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1 || true)"
if [ -z "$JAR" ]; then
    echo "No jar in $ROOT/target. Run: mvn package" >&2
    exit 1
fi

mkdir -p "$OUT"
LOG="$OUT/tool-latency.log"
rm -f "$LOG" "$OUT/tool-latency.stdout"

# One request per line, then a pause: this is a latency measurement, not a load test, and
# overlapping calls would measure the SDK's scheduling instead.
call() {
    printf '{"jsonrpc":"2.0","id":%s,"method":"tools/call","params":{"name":"%s","arguments":%s}}\n' "$1" "$2" "$3"
    sleep 1.5
}

{
    printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"measurement","version":"0"}}}\n'
    sleep 3
    printf '{"jsonrpc":"2.0","method":"notifications/initialized"}\n'
    sleep 1
    n=10
    for _ in $(seq 1 "$SAMPLES"); do
        n=$((n+1)); call $n list_issues         "{\"owner\":\"$SMALL_OWNER\",\"repo\":\"$SMALL_REPO\",\"state\":\"ALL\",\"limit\":30}"
        n=$((n+1)); call $n get_issue           "{\"owner\":\"$SMALL_OWNER\",\"repo\":\"$SMALL_REPO\",\"number\":$SMALL_ISSUE}"
        n=$((n+1)); call $n list_labels         "{\"owner\":\"$SMALL_OWNER\",\"repo\":\"$SMALL_REPO\"}"
        n=$((n+1)); call $n list_issue_comments "{\"owner\":\"$SMALL_OWNER\",\"repo\":\"$SMALL_REPO\",\"number\":$SMALL_THREAD,\"limit\":100}"
        n=$((n+1)); call $n list_issues         "{\"owner\":\"$BIG_OWNER\",\"repo\":\"$BIG_REPO\",\"state\":\"OPEN\",\"limit\":100}"
        n=$((n+1)); call $n list_issue_comments "{\"owner\":\"$BIG_OWNER\",\"repo\":\"$BIG_REPO\",\"number\":$BIG_THREAD,\"limit\":100}"
    done
    # Without this the pipe closes while the last call is still in flight and the Server
    # shuts down before answering -- which measures the Client going away, not the call.
    sleep 3
} | "$JAVA" -jar "$JAR" --logging.file.name="$LOG" \
      > "$OUT/tool-latency.stdout" 2> "$OUT/tool-latency.stderr"

echo "errors on the wire: $(grep -c '"isError":true' "$OUT/tool-latency.stdout" || true)"
python3 - "$LOG" <<'PY'
import collections, json, sys

calls = collections.defaultdict(list)
for line in open(sys.argv[1]):
    try:
        entry = json.loads(line)
    except ValueError:
        continue                      # startup lines are not JSON of this shape
    if "durationMs" in entry and "tool" in entry:
        calls[(entry["tool"], entry.get("repo"))].append(
            (int(entry["durationMs"]), entry.get("outcome"), int(entry.get("resultBytes") or 0)))

if not calls:
    sys.exit("No trace lines. Did every call fail input validation? Check the .stdout file.")

for (tool, repo), samples in sorted(calls.items()):
    ms = sorted(s[0] for s in samples)
    print(f"{tool:22} {repo:42} n={len(ms):2} min={ms[0]:5} med={ms[len(ms)//2]:5} "
          f"max={ms[-1]:5} outcomes={sorted({s[1] for s in samples})} "
          f"maxbytes={max(s[2] for s in samples)}")
PY
