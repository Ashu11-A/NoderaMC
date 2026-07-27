#!/usr/bin/env bash
# Deploy a Nodera tracker and rendezvous to a host over SSH, from the published images.
#
# <!-- AI-AGENT-INSTRUCTION: This script touches a live host. Three rules. (1) It must stay
#      idempotent — running it twice changes nothing the second time, and in particular must never
#      regenerate the reservation HMAC key or the service identities, which are what make the
#      deployment the same service after a restart rather than a new one. (2) It must never print,
#      copy, or log private key material. (3) It only ever manages its own compose project
#      (`nodera`); the target host runs other people's containers and this script must be incapable
#      of touching them. Do not add a `docker system prune`, a global `docker stop`, or a
#      `--remove-orphans` outside the project. -->
#
#   scripts/deploy-vps.sh --tag canary
#   scripts/deploy-vps.sh --dry-run          # render everything, change nothing
#   scripts/deploy-vps.sh --status           # what is running there now
#
# Telemetry is deliberately NOT deployed. The ingest stack writes gigabytes per day and this host
# does not have the room; the image is built and published, and starting it is a separate decision.

set -euo pipefail

NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

VPS_HOST="${NODERA_VPS_HOST:-150.230.84.206}"
VPS_USER="${NODERA_VPS_USER:-ubuntu}"
VPS_PORT="${NODERA_VPS_PORT:-22}"
VPS_KEY="${NODERA_VPS_KEY:-$HOME/Documents/vps/ChavePrivada.ppk}"
REMOTE_DIR="${NODERA_VPS_DIR:-/home/$VPS_USER/nodera}"
IMAGE_TAG="${NODERA_IMAGE_TAG:-canary}"

# What the services publish about themselves. These must be the addresses OTHER machines use: a
# service that advertises its bind address is a service nobody outside the container can reach.
# Addresses, not names. noderamc.org sits behind a proxy that does not carry raw TCP on these
# ports, so advertising a hostname that never resolves to this service costs every peer a failing
# probe and every user a red row that means nothing.
TRACKER_ROUTES="${NODERA_TRACKER_ROUTES:-tcp://$VPS_HOST:6969}"
RENDEZVOUS_ROUTES="${NODERA_RENDEZVOUS_ROUTES:-tcp://$VPS_HOST:7500}"
# Where the relay announces ITSELF. This script always deploys both services into one compose
# project, so the sibling is reachable by its compose service name — which is also the only form
# that works: a container reaching its own host's public address has to hairpin back through the
# published port, and on a default bridge network that is refused. Point this elsewhere with
# NODERA_RENDEZVOUS_TRACKERS when the relay should announce to a tracker on another host.
RENDEZVOUS_TRACKERS="${NODERA_RENDEZVOUS_TRACKERS:-tcp://tracker:6969}"

DRY_RUN=0
STATUS_ONLY=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --host) VPS_HOST="$2"; shift 2 ;;
        --user) VPS_USER="$2"; shift 2 ;;
        --port) VPS_PORT="$2"; shift 2 ;;
        --key) VPS_KEY="$2"; shift 2 ;;
        --dir) REMOTE_DIR="$2"; shift 2 ;;
        --tag) IMAGE_TAG="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --status) STATUS_ONLY=1; shift ;;
        -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mdeploy:\033[0m %s\n' "$*" >&2; exit 1; }

# --- the key -----------------------------------------------------------------------------------
#
# PuTTY's format is not OpenSSH's. Converted into a private temp file that is deleted on every exit
# path including a failure; the contents are never read by this script, printed, or copied anywhere
# else. If the key is already OpenSSH, it is used where it is.

WORK_DIR="$(mktemp -d)"
chmod 700 "$WORK_DIR"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

[[ -r "$VPS_KEY" ]] || die "cannot read $VPS_KEY"

SSH_KEY="$VPS_KEY"
if [[ "$VPS_KEY" == *.ppk ]]; then
    command -v puttygen >/dev/null || die "puttygen is needed to read a .ppk key (apt install putty-tools)"
    SSH_KEY="$WORK_DIR/id_deploy"
    puttygen "$VPS_KEY" -O private-openssh -o "$SSH_KEY" </dev/null \
        || die "could not convert $VPS_KEY (is it passphrase-protected?)"
    chmod 600 "$SSH_KEY"
fi

SSH_OPTS=(-i "$SSH_KEY" -p "$VPS_PORT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)
remote() { ssh "${SSH_OPTS[@]}" "$VPS_USER@$VPS_HOST" "$@"; }

# --- what is there now -------------------------------------------------------------------------

say "checking $VPS_USER@$VPS_HOST:$VPS_PORT"
remote true || die "cannot reach the host"
remote 'sudo -n true' >/dev/null 2>&1 || die "sudo needs a password on that host; this script cannot supply one"
remote 'command -v docker >/dev/null' || die "docker is not installed on that host"

if [[ "$STATUS_ONLY" == 1 ]]; then
    say "compose project state"
    remote "cd '$REMOTE_DIR' 2>/dev/null && sudo docker compose ps || echo '(nothing deployed)'"
    exit 0
fi

# --- the environment file ----------------------------------------------------------------------
#
# Rendered locally, then compared with what is already there. The reservation HMAC key is generated
# ONCE and reused for every later deploy: regenerating it invalidates every outstanding relay
# reservation, which is a small outage nobody would connect to a deployment that "changed nothing".

say "reading the existing deployment secret, if there is one"
EXISTING_HMAC="$(remote "grep -h '^NODERA_RENDEZVOUS_RESERVATION_HMAC_KEY_HEX=' '$REMOTE_DIR/.env' 2>/dev/null | cut -d= -f2-" || true)"
if [[ -n "$EXISTING_HMAC" ]]; then
    say "reusing the existing reservation key (outstanding reservations survive this deploy)"
    HMAC_KEY="$EXISTING_HMAC"
else
    say "generating a reservation key for the first time"
    HMAC_KEY="$(openssl rand -hex 32)"
fi

cat > "$WORK_DIR/.env" <<EOF
# Written by scripts/deploy-vps.sh — edit that, not this.
NODERA_IMAGE_TAG=$IMAGE_TAG
NODERA_TRACKER_PORT=6969
NODERA_RENDEZVOUS_PORT=7500
NODERA_TRACKER_ADVERTISED_ROUTES=$TRACKER_ROUTES
NODERA_RENDEZVOUS_ADVERTISED_ROUTES=$RENDEZVOUS_ROUTES
NODERA_RENDEZVOUS_TRACKER_ENDPOINTS=$RENDEZVOUS_TRACKERS
NODERA_RENDEZVOUS_RESERVATION_HMAC_KEY_HEX=$HMAC_KEY
NODERA_RENDEZVOUS_MAX_CONCURRENT_CIRCUITS=0
NODERA_RENDEZVOUS_DRAIN_GRACE_SECONDS=30
NODERA_TRACKER_MAX_SERVICES=256
NODERA_TRACKER_PER_IP_ANNOUNCE_QUOTA=60
EOF
chmod 600 "$WORK_DIR/.env"

if [[ "$DRY_RUN" == 1 ]]; then
    say "dry run — this is what would be written to $REMOTE_DIR/.env"
    sed 's/^\(NODERA_RENDEZVOUS_RESERVATION_HMAC_KEY_HEX=\).*/\1<redacted>/' "$WORK_DIR/.env"
    say "and the compose file: $NODERA_ROOT/docker/compose.yml"
    say "nothing was changed"
    exit 0
fi

# --- ship it ------------------------------------------------------------------------------------

say "copying compose.yml and .env to $REMOTE_DIR"
remote "mkdir -p '$REMOTE_DIR'"
scp "${SSH_OPTS[@]/-p/-P}" -q "$NODERA_ROOT/docker/compose.yml" "$WORK_DIR/.env" \
    "$VPS_USER@$VPS_HOST:$REMOTE_DIR/" || die "copy failed"
remote "chmod 600 '$REMOTE_DIR/.env'"

say "pulling ${IMAGE_TAG} images"
remote "cd '$REMOTE_DIR' && sudo docker compose pull --quiet tracker rendezvous"

# Only this project's services are named. Telemetry is not started here at all — its image exists,
# and running it is a separate decision with a storage budget attached.
say "starting tracker and rendezvous"
remote "cd '$REMOTE_DIR' && sudo docker compose up -d tracker rendezvous"

# --- prove it works ------------------------------------------------------------------------------
#
# Two checks, and both matter. The in-container healthcheck speaks the real protocol, so it fails
# when a service is listening but not answering. The check from THIS machine proves the path from
# the public internet — firewall, NAT, port publishing — which is the half a local check cannot see.

say "waiting for the services to answer"
sleep 5

for attempt in 1 2 3 4 5 6; do
    if remote "cd '$REMOTE_DIR' && sudo docker compose exec -T tracker nodera-tracker --healthcheck 127.0.0.1:6969" >/dev/null 2>&1; then
        break
    fi
    [[ "$attempt" == 6 ]] && die "the tracker did not become healthy; see: scripts/deploy-vps.sh --status"
    sleep 5
done
say "tracker answers its own protocol"

remote "cd '$REMOTE_DIR' && sudo docker compose exec -T rendezvous nodera-rendezvous --healthcheck 127.0.0.1:7500" >/dev/null \
    || die "the rendezvous service did not become healthy"
say "rendezvous answers its own protocol"

if [[ -x "$NODERA_ROOT/build/nodera-tracker" ]]; then
    say "checking the public path from this machine"
    "$NODERA_ROOT/build/nodera-tracker" --healthcheck "$VPS_HOST:6969" \
        || die "the tracker is healthy on the host but unreachable from here — check the firewall"
    "$NODERA_ROOT/build/nodera-rendezvous" --healthcheck "$VPS_HOST:7500" \
        || die "the relay is healthy on the host but unreachable from here — check the firewall"
else
    say "skipping the public-path check (no local build/ binaries; run scripts/dev.sh --build-only)"
fi

say "deployed"
remote "cd '$REMOTE_DIR' && sudo docker compose ps --format 'table {{.Service}}\t{{.Image}}\t{{.Status}}'"
