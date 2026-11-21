#!/usr/bin/env bash
# Quick health check for Local Dream Image.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${LD_ENGINE:-$ROOT/bin/sd-server}"
echo "== Local Dream Image check =="
if [ -x "$ENGINE" ]; then echo "engine: OK ($ENGINE)"; else echo "engine: MISSING"; fi
python3 - "$ROOT" <<'PY'
import sys
sys.path.insert(0, sys.argv[1])
import server
print("engine status:", server.engine_status())
for m in server.model_list()["models"]:
    print(" -", m["id"], m["status"])
PY
