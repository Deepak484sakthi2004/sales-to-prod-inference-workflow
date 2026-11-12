#!/usr/bin/env bash
# Regenerates docs/RUN_LOG.txt in-process (no servers needed): real engine, services and stores, frozen clock,
# the scripted stand-in for the language model.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -DskipTests install
mvn -q -pl core test-compile exec:java
echo "Wrote docs/RUN_LOG.txt"
