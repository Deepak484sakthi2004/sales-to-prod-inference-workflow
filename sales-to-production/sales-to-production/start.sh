#!/usr/bin/env bash
# One command: build (if needed), start the five stand-ins and the intelligence layer, wait until all answer.
#   ./start.sh            build if jars are missing, then start
#   ./start.sh --build    always rebuild first
set -euo pipefail
cd "$(dirname "$0")"

command -v java >/dev/null || { echo "Java 21 is required (java not found)"; exit 1; }
if [[ "${1:-}" == "--build" || ! -f standins/target/standins.jar || ! -f core/target/core.jar ]]; then
  command -v mvn >/dev/null || { echo "Maven 3.9+ is required to build (mvn not found)"; exit 1; }
  echo "Building..."
  mvn -q -DskipTests package
fi

[[ -f .pids ]] && ./stop.sh >/dev/null 2>&1 || true
mkdir -p logs data
# Settings such as GEMINI_API_KEY, S2P_STORE and MONGODB_URI can live in .env (see .env.example).
if [[ -f .env ]]; then set -a; . ./.env; set +a; fi
# Every process shares one business clock: same simulated start, same real-time anchor.
export SIM_START="${SIM_START:-2026-11-11T09:30:00+05:30}"
export SIM_REAL_ANCHOR="$(date +%s)"

for profile in mailbox crm erp planning statusfeed; do
  nohup java -jar standins/target/standins.jar --spring.profiles.active="$profile" > "logs/$profile.log" 2>&1 &
  echo $! >> .pids
done

wait_for() {
  local name=$1 url=$2
  for _ in $(seq 1 90); do
    if curl -sf -o /dev/null "$url"; then echo "  $name up"; return 0; fi
    sleep 1
  done
  echo "  $name did not start; see logs/"; exit 1
}
echo "Starting stand-ins..."
wait_for mailbox    http://localhost:8081/inbox
wait_for crm        http://localhost:8082/snapshot
wait_for erp        http://localhost:8083/snapshot
wait_for planning   http://localhost:8084/snapshot
wait_for statusfeed http://localhost:8085/statuses/ACC-SUND

# The AI service (Python, Gemini) reads mail and questions. Without a key, mail waits unread.
if [[ -n "${GEMINI_API_KEY:-}" ]] && command -v python3 >/dev/null; then
  if [[ ! -x ai-service/.venv/bin/uvicorn ]]; then
    echo "Setting up the AI service (first run)..."
    python3 -m venv ai-service/.venv && ai-service/.venv/bin/pip install -q -r ai-service/requirements.txt
  fi
  (cd ai-service && nohup .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8090 > ../logs/ai.log 2>&1 & echo $! >> ../.pids)
  wait_for ai http://localhost:8090/health
  export AI_ENABLED="${AI_ENABLED:-true}"
else
  echo "  GEMINI_API_KEY not set (or python3 missing): starting without the language model. Mail waits unread and"
  echo "  plain-language questions are not understood; promise checks work from the form. Put the key in .env."
  export AI_ENABLED=false
fi
if [[ "${S2P_STORE:-memory}" == "mongo" ]]; then
  echo "  Core records in MongoDB (${MONGODB_DATABASE:-s2p})"
else
  echo "  Core records in memory (default; set S2P_STORE=mongo and MONGODB_URI to keep them in MongoDB Atlas)"
fi

nohup java -jar core/target/core.jar > logs/core.log 2>&1 &
echo $! >> .pids
echo "Starting intelligence layer..."
wait_for core http://localhost:8080/api/self

cat <<MSG

Ready. Business time starts at $SIM_START.
  Plant desk     http://localhost:8080/   (sales, planning, pipeline, operations, review console)
  Customer view  http://localhost:8080/customer.html?token=kav-2c91   (other tokens in README)
  API            http://localhost:8080/api/...
  AI service     http://localhost:8090/health   (only when GEMINI_API_KEY is set)
Logs in ./logs, decision log in ./data/decision-log.jsonl. Stop with ./stop.sh
MSG
