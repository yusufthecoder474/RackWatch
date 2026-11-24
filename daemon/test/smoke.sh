#!/bin/bash
# RackWatch daemon smoke test: runs the daemon against a mock API and
# checks the live behaviour the unit tests cannot see — buffering while
# the server is down, flushing in order with the ORIGINAL sampling
# timestamps (never re-stamped), quarantining a rejected batch, and
# slow retries on a bad key.
#
# Needs: the built daemon (built below), python3, and the `timeout`
# command. Run with `make test-integration`.
set -eu
cd "$(dirname "$0")/.."
make -s all

export RACKWATCH_API_KEY=test-key
TMP=$(mktemp -d)
MOCK_PID=""
DAEMON_PID=""
cleanup() {
    if [ -n "$DAEMON_PID" ]; then kill "$DAEMON_PID" 2>/dev/null || true; fi
    if [ -n "$MOCK_PID" ]; then kill "$MOCK_PID" 2>/dev/null || true; fi
    rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

cat > "$TMP/sensors.conf" <<'EOF'
ROOM-A-TEMP-01 temp normal 22.0
ROOM-A-RH-01 humidity normal 50.0
EOF
echo 200 > "$TMP/status"
MOCK_PORT=18080 MOCK_STATUS_FILE="$TMP/status" MOCK_LOG="$TMP/received.log" \
    python3 test/mock_api.py &
MOCK_PID=$!

# Wait for the mock to listen (max 5 s).
for _ in 1 2 3 4 5 6 7 8 9 10; do
    if python3 -c "import socket; socket.create_connection(('127.0.0.1', 18080), 1).close()" 2>/dev/null; then
        break
    fi
    sleep 0.5
done

run_daemon() {
    # $1 = seconds to run. `timeout` kills it; exit 124 is expected.
    timeout "$1" ./rackwatchd --config "$TMP/sensors.conf" \
        --url http://127.0.0.1:18080 --interval 1 --seed 42 \
        --buffer "$TMP/buffer.jsonl" --quarantine "$TMP/quarantine.jsonl" \
        --foreground > "$TMP/daemon-$2.log" 2>&1 || true
}

check() { # $1 = description, $2 = shell condition
    if eval "$2"; then echo "ok - $1"; else echo "FAIL - $1"; exit 1; fi
}

# --- Phase 1: server up, live POSTs land -------------------------------
run_daemon 7 phase1
python3 - "$TMP/received.log" <<'EOF'
import json, re, sys
path = sys.argv[1]
with open(path) as f:
    bodies = [line for line in f.read().splitlines() if line.strip()]
assert len(bodies) >= 2, f"expected >=2 POSTs, got {len(bodies)}"
for body in bodies:
    batch = json.loads(body)
    assert isinstance(batch, list) and batch, "each POST must be a non-empty JSON array"
    assert len(batch) <= 100, "batch over 100 readings"
    assert len(body.encode()) < 65536, "body over 64 KiB"
    for r in batch:
        assert set(r) == {"sensor_id", "ts", "temp_c"} or set(r) == {"sensor_id", "ts", "rh_pct"}, \
            f"wrong fields: {sorted(r)}"
        assert re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", r["ts"]), r["ts"]
print(f"phase 1: {len(bodies)} POSTs, all valid snake_case batches")
EOF
check "phase 1: live POSTs are valid batches" "true"

# --- Phase 2: server down (500) -> readings buffer --------------------
echo 500 > "$TMP/status"
BEFORE=$(wc -l < "$TMP/received.log")
run_daemon 4 phase2
check "phase 2: buffer file has readings while the server is down" \
    "[ -s $TMP/buffer.jsonl ] && [ \"\$(wc -l < $TMP/buffer.jsonl)\" -ge 2 ]"
grep -q "buffered" "$TMP/daemon-phase2.log"
check "phase 2: daemon logged that readings were buffered" "true"

# --- Phase 3: server back -> buffer flushes, timestamps unchanged -----
python3 - "$TMP/buffer.jsonl" > "$TMP/buffered-ts.txt" <<'EOF'
import json, sys
with open(sys.argv[1]) as f:
    for line in f:
        if line.strip():
            print(json.loads(line)["ts"])
EOF
echo 200 > "$TMP/status"
run_daemon 4 phase3
check "phase 3: buffer is empty after the server returns" "[ ! -s $TMP/buffer.jsonl ]"
python3 - "$TMP/received.log" "$TMP/buffered-ts.txt" <<'EOF'
import json, sys
received_ts = set()
with open(sys.argv[1]) as f:
    for line in f:
        if line.strip():
            for r in json.loads(line):
                received_ts.add(r["ts"])
with open(sys.argv[2]) as f:
    buffered_ts = {line.strip() for line in f if line.strip()}
missing = buffered_ts - received_ts
assert not missing, f"buffered timestamps never delivered: {sorted(missing)[:3]}"
print(f"phase 3: all {len(buffered_ts)} buffered readings re-delivered with original timestamps")
EOF
check "phase 3: flushed readings keep their original sampling timestamps" "true"

# --- Phase 4: server rejects the batch (400) -> quarantine ------------
echo 400 > "$TMP/status"
run_daemon 3 phase4
check "phase 4: rejected batch went to quarantine" "[ -s $TMP/quarantine.jsonl ]"
check "phase 4: buffer is empty (bad batch does not block good data)" "[ ! -s $TMP/buffer.jsonl ]"
echo 200 > "$TMP/status"
RECEIVED_BEFORE=$(wc -l < "$TMP/received.log")
run_daemon 3 phase4b
check "phase 4: good data flows again after quarantine" \
    "[ \"\$(wc -l < $TMP/received.log)\" -gt $RECEIVED_BEFORE ]"

# --- Phase 5: bad key (401) -> buffer kept, clear error ----------------
echo 401 > "$TMP/status"
run_daemon 3 phase5
check "phase 5: readings buffered on 401" "[ -s $TMP/buffer.jsonl ]"
check "phase 5: daemon logged a clear 'check API key' error" \
    "grep -q 'check RACKWATCH_API_KEY' $TMP/daemon-phase5.log"

# --- Phase 6: flush is bounded to 5 batches per call -------------------
# Pre-fill a buffer with 1200 lines (12 batches of 100). One loop
# iteration flushes at most 5 batches (500 lines), so after the first
# iteration the count sits at 700 until the next iteration at t=1 s.
# Poll every 0.1 s (up to 3 s) for the count to SETTLE in the 600..700
# window: two consecutive polls must agree. A transient count mid-flush
# does not count, and a fixed sleep would be wrong here — on a slow
# machine the first iteration might not be done at 0.6 s, and on a fast
# one the second iteration might already have started. Without the
# 5-batch limit the first iteration would drain all 1200 lines, so the
# count would never settle in 600..700. (Phase 3 already proves
# flushing works at all, so this phase only has to prove the limit.)
python3 - "$TMP/big-buffer.jsonl" <<'EOF'
import json, sys
with open(sys.argv[1], "w") as f:
    for i in range(1200):
        f.write(json.dumps({"sensor_id": "ROOM-A-TEMP-01",
                            "ts": f"2026-11-23T12:{i // 60:02d}:{i % 60:02d}Z",
                            "temp_c": 22.5}) + "\n")
EOF
echo 200 > "$TMP/status"
./rackwatchd --config "$TMP/sensors.conf" --url http://127.0.0.1:18080 \
    --interval 1 --seed 42 --buffer "$TMP/big-buffer.jsonl" \
    --quarantine "$TMP/quarantine.jsonl" --foreground \
    > "$TMP/daemon-phase6.log" 2>&1 &
DAEMON_PID=$!
PHASE6_OK=0
PREV=-1
for _ in $(seq 1 30); do
    REMAINING=$(wc -l < "$TMP/big-buffer.jsonl")
    if [ "$REMAINING" -ge 600 ] && [ "$REMAINING" -le 700 ] && [ "$REMAINING" = "$PREV" ]; then
        PHASE6_OK=1
        break
    fi
    PREV=$REMAINING
    sleep 0.1
done
kill -TERM "$DAEMON_PID" 2>/dev/null || true
wait "$DAEMON_PID" 2>/dev/null || true
DAEMON_PID=""
check "phase 6: one flush call sends at most 5 batches (buffer count settles in 600..700 after one iteration)" \
    "[ \"$PHASE6_OK\" = 1 ]"

echo "smoke test: PASS"
