#!/usr/bin/env bash
set -euo pipefail

report_dir=build/ci/replay
device_screenshot=/sdcard/Download/signa-replay.png
mkdir -p "$report_dir"
adb shell mkdir -p /sdcard/Download
adb shell rm -f "$device_screenshot"
adb logcat -c

test_status=0
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.signaReplayScreenshot=true || test_status=$?
adb logcat -d -v raw -s SignaReplay:I SignaVisionParity:I > "$report_dir/vision.log"
adb pull "$device_screenshot" "$report_dir/replay-screen.png" || true
if (( test_status != 0 )); then
  exit "$test_status"
fi
test -s "$report_dir/replay-screen.png"

python3 - "$report_dir" <<'PY'
import json
import sys
from pathlib import Path

report = Path(sys.argv[1])
events = [
    json.loads(line)
    for line in (report / "vision.log").read_text().splitlines()
    if line.startswith('{"frames":')
]
if len(events) != 1 or events[0]["frames"] != 48 or events[0]["abi"] != "x86_64":
    raise SystemExit("Thiếu số đo đủ 48 khung trên emulator x86_64.")
output = json.dumps(events[0], ensure_ascii=False, indent=2)
(report / "replay-benchmark.json").write_text(output + "\n")
print(output)
PY
