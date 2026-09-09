#!/usr/bin/env bash
#
# Three questions about a call the Client is no longer waiting for.
#
#   1. Does one slow `gh` block the next call on the same stdio session?
#   2. When the slow call finally answers, does its response interleave with the others?
#   3. What does this Server do with `notifications/cancelled`?
#
# Offline. A stand-in `gh` is put in front of the real one on PATH, so nothing here
# touches GitHub and no credential is needed. The stand-in sleeps 20 seconds for
# `issue list` and answers immediately for everything else.
#
# Produces the numbers in docs/deploying.md under "Two clocks" and in
# docs/adr/0016-a-cancelled-call-is-not-cancelled-here.md.
#
# Requires: a JDK 25 (JAVA_HOME or `java` on PATH) and `mvn package` already run.
# Takes about 40 seconds, most of it waiting for the stand-in on purpose.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
OUT="${OUT:-$ROOT/target/measurements}"

JAR="$(ls -t "$ROOT"/target/*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1 || true)"
if [ -z "$JAR" ]; then
    echo "No jar in $ROOT/target. Run: mvn package" >&2
    exit 1
fi

mkdir -p "$OUT/bin"
cat > "$OUT/bin/gh" <<'STANDIN'
#!/usr/bin/env bash
# Stand-in for the GitHub CLI: `issue list` takes 20 seconds, everything else answers now.
case "${1:-}" in
    issue) sleep 20; echo '[]' ;;
    label) echo '[]' ;;
    *)     echo '{}' ;;
esac
STANDIN
chmod +x "$OUT/bin/gh"
export PATH="$OUT/bin:$PATH"

LOG="$OUT/concurrency.log"
rm -f "$LOG"

{
    printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"measurement","version":"0"}}}\n'
    sleep 3
    printf '{"jsonrpc":"2.0","method":"notifications/initialized"}\n'
    sleep 1

    echo "SENT A $(date +%s.%N) list_issues -- the stand-in sleeps 20 s" >&2
    printf '{"jsonrpc":"2.0","id":101,"method":"tools/call","params":{"name":"list_issues","arguments":{"owner":"o","repo":"r","limit":5}}}\n'
    sleep 1

    echo "SENT B $(date +%s.%N) list_labels -- the stand-in answers now" >&2
    printf '{"jsonrpc":"2.0","id":102,"method":"tools/call","params":{"name":"list_labels","arguments":{"owner":"o","repo":"r"}}}\n'
    sleep 1

    echo "SENT C $(date +%s.%N) notifications/cancelled for A" >&2
    printf '{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":101,"reason":"probe gave up"}}\n'

    # Long enough for A to finish. A shorter wait would close the pipe first and measure
    # the shutdown instead of the call.
    sleep 40
} 2> "$OUT/concurrency.sent" \
  | "$JAVA" -jar "$JAR" --logging.file.name="$LOG" 2> "$OUT/concurrency.stderr" \
  | awk '{ "date +%s.%N" | getline t; close("date +%s.%N"); print t, substr($0, 1, 160) }' \
  > "$OUT/concurrency.stdout"

echo "=== sent ==="
cat "$OUT/concurrency.sent"
echo
echo "=== stdout: one JSON-RPC message per line, prefixed with the time it arrived ==="
cat "$OUT/concurrency.stdout"
echo
echo "=== what the log has to say about the cancellation ==="
grep -o '"message":"[^"]*"' "$LOG" | grep -i "no handler" || echo "(none -- did the SDK gain a handler?)"
echo
echo "Read it as: B's answer arriving while A's gh is still asleep means no head-of-line"
echo "blocking; A's answer arriving later, tagged with its own id, means responses leave"
echo "in completion order rather than interleaved; and the WARN line means the"
echo "cancellation was ignored -- including the Client's own reason text, which is now in"
echo "that log file. See ADR-0016."
