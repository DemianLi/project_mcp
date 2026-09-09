#!/usr/bin/env bash
#
# Does this `gh` still fail in the words GhStderr recognises?
#
# GhStderr classifies a failure by matching substrings of the message `gh` chose to print.
# Those are not an API. A `gh` release that rephrases one does not break this Server
# loudly: the marker stops matching, the failure falls to UNKNOWN, and the Client gets a
# well-formed response with no recovery advice in it. ADR-0002 accepts that risk; this
# script is what turns "accepted" into "checked".
#
# It provokes each failure that can be provoked safely, through the Server's own Tools,
# and reads the Remedy and the branch's own sentence back off the wire. Nothing is
# written to GitHub: every probe is a read, and the one that needs a bad credential passes
# it in the environment of a second Server rather than touching `gh auth`.
#
# Unlike the other two scripts here this one has a right answer, so it prints a verdict and
# exits non-zero when a branch stops matching.
#
# Requires: a JDK 25 (JAVA_HOME or `java` on PATH), python3, an authenticated `gh`,
# and `mvn package` already run.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
OUT="${OUT:-$ROOT/target/measurements}"
OWNER="${OWNER:-DemianLi}"
REPO="${REPO:-project_mcp}"
THREAD="${THREAD:-32}"          # any issue in $OWNER/$REPO; only its number is used

JAR="$(ls -t "$ROOT"/target/*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1 || true)"
if [ -z "$JAR" ]; then
    echo "No jar in $ROOT/target. Run: mvn package" >&2
    exit 1
fi
mkdir -p "$OUT"

echo "gh in front of this Server: $(gh --version | head -1)"
echo "versions this release was exercised against: see the Compatibility table in CHANGELOG.md"
echo

# A cursor whose wrapper addresses the right issue and whose inner half is not a cursor.
# Cursors.unwrap() catches a wrapper belonging to a different issue before the call is made,
# so this is the only way to reach GitHub's own complaint about it.
CURSOR="$(python3 -c "
import base64,sys
print(base64.urlsafe_b64encode(f'{sys.argv[1]}/{sys.argv[2]}#{sys.argv[3]}|not-a-cursor'.encode()).decode().rstrip('='))
" "$OWNER" "$REPO" "$THREAD")"

drive() {                        # drive <output file> <<< requests, one JSON-RPC per line
    {
        printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"gh-compatibility","version":"0"}}}\n'
        sleep 3
        printf '{"jsonrpc":"2.0","method":"notifications/initialized"}\n'
        sleep 1
        cat
        sleep 4
    } | "$JAVA" -jar "$JAR" --logging.file.name="$OUT/gh-compatibility.log" \
          > "$1" 2> "$OUT/gh-compatibility.stderr"
}

call() { printf '{"jsonrpc":"2.0","id":%s,"method":"tools/call","params":{"name":"%s","arguments":%s}}\n' "$1" "$2" "$3"; sleep 1; }

# Probe 104 is an empty owner rather than one containing a slash. A slash there is not a
# malformed name to `gh`: `--repo a/b/c` parses as HOST/OWNER/REPO and it dials host `a`.
# See docs/reviews/commercial-readiness.md on where that leads.
drive "$OUT/gh-compatibility.stdout" <<REQUESTS
$(call 101 list_issues         "{\"owner\":\"$OWNER\",\"repo\":\"no-such-repository-4f2a9c\"}")
$(call 102 get_issue           "{\"owner\":\"$OWNER\",\"repo\":\"$REPO\",\"number\":999999}")
$(call 103 list_issue_comments "{\"owner\":\"$OWNER\",\"repo\":\"$REPO\",\"number\":999999}")
$(call 104 list_issues         "{\"owner\":\"\",\"repo\":\"c\"}")
$(call 105 list_issues         "{\"owner\":\"torvalds\",\"repo\":\"linux\"}")
$(call 106 list_issue_comments "{\"owner\":\"$OWNER\",\"repo\":\"$REPO\",\"number\":$THREAD,\"cursor\":\"$CURSOR\"}")
REQUESTS

# The authentication branch needs a credential `gh` will reject. GH_TOKEN wins over
# whatever `gh auth login` stored (see docs/deploying.md), and it reaches `gh` because the
# child inherits this Server's environment verbatim -- so a second Server started this way
# is enough, and nothing on this machine is re-authenticated.
GH_TOKEN=not-a-real-token GITHUB_TOKEN=not-a-real-token \
    drive "$OUT/gh-compatibility-unauth.stdout" <<REQUESTS
$(call 201 get_issue "{\"owner\":\"$OWNER\",\"repo\":\"$REPO\",\"number\":1}")
REQUESTS

python3 - "$OUT/gh-compatibility.stdout" "$OUT/gh-compatibility-unauth.stdout" <<'PY'
import json, sys

# id -> (the GhStderr row being checked, the Remedy it owes, a fragment of its own sentence)
EXPECTED = {
    101: ("no such repository",         "FIX_REQUEST",  "No such repository."),
    102: ("no such issue (porcelain)",  "FIX_REQUEST",  'says "issue or pull request"'),
    103: ("no such issue (graphql)",    "FIX_REQUEST",  "issue Tools take issues only"),
    104: ("malformed repository name",  "FIX_REQUEST",  "did not compose a usable repository name"),
    105: ("issues disabled",            "FIX_REQUEST",  "issues turned off"),
    106: ("bad cursor",                 "FIX_REQUEST",  "not one GitHub recognises"),
    201: ("not authenticated",          "ASK_OPERATOR", "not authenticated, or its token is no longer valid"),
}
NOT_PROVOCABLE = [
    ("rate limit",   "needs a token whose GraphQL budget is actually spent; ADR-0002 could not provoke it either"),
    ("not permitted","needs a login without the permission -- measured once in #33 on a read-only PAT"),
    ("network",      "needs a real network failure; its markers are Go's wording and remain UNMEASURED"),
]

answers = {}
for path in sys.argv[1:]:
    for line in open(path):
        message = json.loads(line)
        if "id" in message and message["id"] in EXPECTED:
            answers[message["id"]] = message.get("result", {})

bad = 0
for call_id, (row, remedy, fragment) in EXPECTED.items():
    result = answers.get(call_id)
    if result is None:
        print(f"  ?? {row:26} no answer came back"); bad += 1; continue
    got = result.get("structuredContent", {})
    stderr = " ".join((got.get("stderr") or "(none)").split())[:96]
    ok = got.get("remedy") == remedy and fragment in (got.get("message") or "")
    bad += not ok
    print(f"  {'ok' if ok else 'XX'} {row:26} {got.get('remedy', '(not an error)'):16} {stderr}")
    if not ok:
        print(f"     expected {remedy} and the sentence containing: {fragment!r}")

print()
for row, why in NOT_PROVOCABLE:
    print(f"  -- {row:26} not provoked here: {why}")

print()
if bad:
    print(f"{bad} of {len(EXPECTED)} rows no longer match. A failure this Server used to")
    print("classify now reaches the Client as UNKNOWN with no recovery advice. Paste the")
    print("stderr above into the row's samples in GhStderr and fix the marker.")
    sys.exit(1)
print(f"All {len(EXPECTED)} provocable rows still match this gh.")
PY
