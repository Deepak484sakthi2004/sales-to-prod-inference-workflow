#!/usr/bin/env bash
# Runs the full scenario suite against the LIVE stack (./start.sh first, with GEMINI_API_KEY set) and writes
# docs/RUN_LOG.live.txt. The stand-ins are reset to their seed before and after the run.
set -euo pipefail
cd "$(dirname "$0")/.."
curl -sf -X POST http://localhost:8080/api/scenarios/run -o docs/RUN_LOG.live.txt
echo "Wrote docs/RUN_LOG.live.txt ($(grep -c '^S[0-9][0-9]\. ' docs/RUN_LOG.live.txt) scenarios)"
