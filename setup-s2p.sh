#!/usr/bin/env bash
# One-shot setup for the sales-to-production app on a fresh GCP VM (Debian 11/12 or Ubuntu 20.04+).
# Installs Git and Docker, clones the repo, writes .env, builds the images, starts everything, waits until ready.
#
# Usage:
#   GEMINI_API_KEY=your-key bash setup-s2p.sh          # with the language model
#   bash setup-s2p.sh                                  # without it (asks for the key if run interactively)
#   USE_LOCAL_MONGO=1 bash setup-s2p.sh                # keep core records in a local MongoDB container
#   MONGODB_URI='mongodb+srv://...' bash setup-s2p.sh  # keep core records in MongoDB Atlas
# Safe to run again: it updates the code and restarts the containers.
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/Deepak484sakthi2004/sales-to-prod-inference-workflow.git}"
BRANCH="${BRANCH:-working-branch}"
INSTALL_DIR="${INSTALL_DIR:-/opt/s2p}"
APP_SUBDIR="sales-to-production/sales-to-production"   # where docker-compose.yml lives inside the repo
GEMINI_MODEL="${GEMINI_MODEL:-gemini-3.8-flash}"
USE_LOCAL_MONGO="${USE_LOCAL_MONGO:-0}"
MONGODB_URI="${MONGODB_URI:-}"

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[warn] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[error] %s\033[0m\n' "$*"; exit 1; }

SUDO=""; [[ $EUID -ne 0 ]] && SUDO="sudo"

# ---------------------------------------------------------------- 1. OS check
. /etc/os-release
case "$ID" in
  debian|ubuntu) ;;
  *) die "This script supports Debian and Ubuntu images only (found: $ID)." ;;
esac
log "OS: $PRETTY_NAME"

# ---------------------------------------------------------------- 2. Swap on small VMs (the Maven build needs ~2 GB)
MEM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
if (( MEM_MB < 3500 )) && ! swapon --show | grep -q .; then
  log "Only ${MEM_MB} MB RAM: adding a 4 GB swap file so the build doesn't run out of memory"
  $SUDO fallocate -l 4G /swapfile && $SUDO chmod 600 /swapfile
  $SUDO mkswap /swapfile >/dev/null && $SUDO swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | $SUDO tee -a /etc/fstab >/dev/null
fi

# ---------------------------------------------------------------- 3. Git, curl and Docker
log "Installing Git and basic tools"
export DEBIAN_FRONTEND=noninteractive
$SUDO apt-get update -qq
$SUDO apt-get install -y -qq git curl ca-certificates gnupg >/dev/null

if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  log "Installing Docker Engine and the Compose plugin (official Docker repository)"
  $SUDO install -m 0755 -d /etc/apt/keyrings
  curl -fsSL "https://download.docker.com/linux/$ID/gpg" | $SUDO gpg --dearmor --yes -o /etc/apt/keyrings/docker.gpg
  $SUDO chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/$ID $VERSION_CODENAME stable" \
    | $SUDO tee /etc/apt/sources.list.d/docker.list >/dev/null
  $SUDO apt-get update -qq
  $SUDO apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin >/dev/null
fi
$SUDO systemctl enable --now docker >/dev/null
[[ -n "$SUDO" ]] && $SUDO usermod -aG docker "$USER" || true   # lets you run docker without sudo after re-login
log "$(docker --version); $($SUDO docker compose version)"

# ---------------------------------------------------------------- 4. Clone or update the repo
if [[ -d "$INSTALL_DIR/.git" ]]; then
  log "Updating existing clone in $INSTALL_DIR"
  $SUDO git -C "$INSTALL_DIR" fetch -q origin "$BRANCH"
  $SUDO git -C "$INSTALL_DIR" checkout -q "$BRANCH"
  $SUDO git -C "$INSTALL_DIR" reset -q --hard "origin/$BRANCH"
else
  log "Cloning $REPO_URL ($BRANCH) into $INSTALL_DIR"
  $SUDO git clone -q --branch "$BRANCH" "$REPO_URL" "$INSTALL_DIR"
fi
APP_DIR="$INSTALL_DIR/$APP_SUBDIR"
[[ -f "$APP_DIR/docker-compose.yml" ]] || die "docker-compose.yml not found in $APP_DIR"
cd "$APP_DIR"

# ---------------------------------------------------------------- 5. Settings (.env, read by docker compose)
if [[ -z "${GEMINI_API_KEY:-}" && -f .env ]]; then
  GEMINI_API_KEY="$(grep -E '^GEMINI_API_KEY=' .env | cut -d= -f2- || true)"   # keep the key from a previous run
fi
if [[ -z "${GEMINI_API_KEY:-}" && -t 0 ]]; then
  read -r -s -p "Gemini API key (press Enter to run without the language model): " GEMINI_API_KEY; echo
fi

STORE="memory"; COMPOSE_PROFILE_ARGS=()
if [[ "$USE_LOCAL_MONGO" == "1" ]]; then
  STORE="mongo"; MONGODB_URI="mongodb://mongo:27017"; COMPOSE_PROFILE_ARGS=(--profile mongo)
elif [[ -n "$MONGODB_URI" ]]; then
  STORE="mongo"
fi

log "Writing .env (store: $STORE, language model: $([[ -n "${GEMINI_API_KEY:-}" ]] && echo on || echo off))"
{
  echo "GEMINI_API_KEY=${GEMINI_API_KEY:-}"
  echo "GEMINI_MODEL=$GEMINI_MODEL"
  echo "GEMINI_THINKING_LEVEL=LOW"
  [[ -z "${GEMINI_API_KEY:-}" ]] && echo "AI_ENABLED=false"
  echo "S2P_STORE=$STORE"
  [[ -n "$MONGODB_URI" ]] && echo "MONGODB_URI=$MONGODB_URI"
  echo "MONGODB_DATABASE=s2p"
} | $SUDO tee .env >/dev/null
$SUDO chmod 600 .env   # the file holds secrets

# Restart containers automatically after a crash or VM reboot.
$SUDO tee docker-compose.override.yml >/dev/null <<'YAML'
services:
  mailbox:    { restart: unless-stopped }
  crm:        { restart: unless-stopped }
  erp:        { restart: unless-stopped }
  planning:   { restart: unless-stopped }
  statusfeed: { restart: unless-stopped }
  core:       { restart: unless-stopped }
  ai:         { restart: unless-stopped }
  mongo:      { restart: unless-stopped }
YAML

# ---------------------------------------------------------------- 6. Build and start
# One shared business clock: every container gets the same real-time anchor.
SIM_REAL_ANCHOR="$(date +%s)"; export SIM_REAL_ANCHOR
dc() {   # docker compose, with sudo when needed, keeping the clock anchor
  local profile=("${COMPOSE_PROFILE_ARGS[@]+"${COMPOSE_PROFILE_ARGS[@]}"}")
  if [[ -n "$SUDO" ]]; then sudo --preserve-env=SIM_REAL_ANCHOR docker compose "${profile[@]+"${profile[@]}"}" "$@"
  else docker compose "${profile[@]+"${profile[@]}"}" "$@"; fi
}
log "Building images (first run takes a few minutes: Maven downloads dependencies)"
dc build
log "Starting containers"
dc down --remove-orphans >/dev/null 2>&1 || true
dc up -d

# ---------------------------------------------------------------- 7. Wait until the app answers
log "Waiting for the core to answer on port 8080"
for i in $(seq 1 120); do
  if curl -sf -o /dev/null http://localhost:8080/api/self; then break; fi
  (( i == 120 )) && { dc logs --tail 50 core; die "Core did not start within 4 minutes (logs above)."; }
  sleep 2
done

EXTERNAL_IP=$(curl -sf -H "Metadata-Flavor: Google" \
  http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip || echo "<VM-EXTERNAL-IP>")
INSTANCE=$(curl -sf -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/name || echo "<VM-NAME>")

cat <<MSG

$(printf '\033[1;32m')Ready.$(printf '\033[0m') Everything is running in Docker from $APP_DIR

  On the VM:        curl http://localhost:8080/api/self

  From your laptop, safest (SSH tunnel, nothing opened to the internet):
    gcloud compute ssh $INSTANCE -- -L 8080:localhost:8080
    then open http://localhost:8080/  and  http://localhost:8080/customer.html?token=kav-2c91

  Or open port 8080 to your own IP only (the app has no login, so never open it to 0.0.0.0/0).
  Run this from Cloud Shell or your laptop, not the VM:
    gcloud compute firewall-rules create allow-s2p-8080 --allow tcp:8080 \\
      --source-ranges \$(curl -s ifconfig.me)/32 --target-tags s2p
    gcloud compute instances add-tags $INSTANCE --tags s2p
    then open http://$EXTERNAL_IP:8080/

  Useful commands (run in $APP_DIR):
    sudo docker compose ps              status
    sudo docker compose logs -f core    follow core logs
    sudo docker compose down            stop everything
    bash $(realpath "$0" 2>/dev/null || echo setup-s2p.sh)   update the code and restart
MSG
