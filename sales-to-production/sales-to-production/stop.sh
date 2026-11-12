#!/usr/bin/env bash
cd "$(dirname "$0")"
if [[ -f .pids ]]; then
  while read -r pid; do kill "$pid" 2>/dev/null || true; done < .pids
  rm -f .pids
  echo "Stopped."
else
  echo "Nothing to stop."
fi
