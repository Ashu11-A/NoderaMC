#!/usr/bin/env bash
# One-time, idempotent bootstrap of noderamc.org's container deployment on the VPS.
#
# <!-- AI-AGENT-INSTRUCTION: This script touches a live host that runs OTHER PEOPLE'S sites out of
#      the same Caddy and OTHER PEOPLE'S containers out of the same Docker. Five rules.
#      (1) It may only ever write /var/www/noderamc, the single file
#      /etc/caddy/conf.d/noderamc.caddy, /usr/local/lib/nodera/, the two nodera-web-update units,
#      and its own compose project directory — never another conf.d entry, never the parent
#      Caddyfile, never another unit.
#      (2) `caddy validate` must pass before a reload; a bad block would take every other site on
#      the box down with it, so the install is staged and rolled back if validation fails.
#      (3) Reload, never restart: a restart drops live connections for sites that are nothing to do
#      with us.
#      (4) It only ever manages its own compose project (`nodera`), by name, one service at a time.
#      No `docker system prune`, no global `docker stop`, no `--remove-orphans`.
#      (5) It must stay idempotent — running it twice changes nothing the second time. That is what
#      makes it safe to re-run after a partial failure, which is the state it will most often be
#      run from. -->
#
#   scripts/bootstrap-site-host.sh                 # install everything, verify, done
#   scripts/bootstrap-site-host.sh --dry-run       # print every remote action, change nothing
#   scripts/bootstrap-site-host.sh --status        # what is installed and running there now
#
# WHAT THIS SETS UP, and why there is a manual step at all
#
#   The site is a container image published by CI (ghcr.io/ashu11-a/noderamc:web-canary, built by
#   .github/workflows/containers.yml). The host pulls it on a systemd timer. Nothing in GitHub holds
#   an SSH key for this host and nothing is expected to — which is precisely why the FIRST
#   installation of a root-owned unit file cannot come from CI. After this script has run once, the
#   steady state is: merge to main → CI publishes web-canary → the timer pulls it within minutes.
#
#   Installed here:
#     * docker/compose.yml               → $REMOTE_DIR/compose.yml   (adds the `web` service)
#     * deploy/vps/nodera-web-update.sh  → /usr/local/lib/nodera/nodera-web-update.sh
#     * deploy/vps/nodera-web-update.*   → /etc/systemd/system/, timer enabled
#     * web/noderamc.caddy               → /etc/caddy/conf.d/noderamc.caddy  (validated, reloaded)
#
# The key is the same PuTTY key scripts/deploy-vps.sh and scripts/deploy-site.sh use, converted in a
# temp file that is removed on exit. Nothing here prints, copies, or logs key material.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export

VPS_HOST="${NODERA_VPS_HOST:-150.230.84.206}"
VPS_USER="${NODERA_VPS_USER:-ubuntu}"
VPS_PORT="${NODERA_VPS_PORT:-22}"
VPS_KEY="${NODERA_VPS_KEY:-$HOME/Documents/vps/ChavePrivada.ppk}"
REMOTE_DIR="${NODERA_VPS_DIR:-/home/$VPS_USER/nodera}"

# Where the host keeps the pieces. Root-owned, all of them.
HELPER_DIR="/usr/local/lib/nodera"
HELPER="$HELPER_DIR/nodera-web-update.sh"
UNIT_DIR="/etc/systemd/system"
WEB_ROOT="/var/www/noderamc"
CADDY_FILE="/etc/caddy/conf.d/noderamc.caddy"
LOG_FILE="/var/log/caddy/noderamc.log"

DRY_RUN=0
STATUS_ONLY=0

while [[ $# -gt 0 ]]; do
	case "$1" in
		--host) VPS_HOST="$2"; shift 2 ;;
		--user) VPS_USER="$2"; shift 2 ;;
		--port) VPS_PORT="$2"; shift 2 ;;
		--key) VPS_KEY="$2"; shift 2 ;;
		--dir) REMOTE_DIR="$2"; shift 2 ;;
		--dry-run) DRY_RUN=1; shift ;;
		--status) STATUS_ONLY=1; shift ;;
		-h|--help) sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
		*) echo "unknown option: $1" >&2; exit 2 ;;
	esac
done

die() { echo "bootstrap-site-host: $*" >&2; exit 1; }
say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

# --- the inputs, before anything is touched ------------------------------------------------------

for f in \
	"$NODERA_ROOT/docker/compose.yml" \
	"$NODERA_VPS_UNITS/nodera-web-update.sh" \
	"$NODERA_VPS_UNITS/nodera-web-update.service" \
	"$NODERA_VPS_UNITS/nodera-web-update.timer" \
	"$NODERA_WEB_DIR/noderamc.caddy"
do
	[[ -f "$f" ]] || die "missing input: $f"
done

# --- the key -------------------------------------------------------------------------------------
#
# PuTTY's format is not OpenSSH's. Converted into a private temp file deleted on every exit path
# including a failure; the contents are never read by this script, printed, or copied anywhere else.
# If the key is already OpenSSH it is used where it is.

[[ -r "$VPS_KEY" ]] || die "cannot read $VPS_KEY"

SSH_KEY="$VPS_KEY"
CLEANUP_KEY=""
if [[ "$VPS_KEY" == *.ppk ]]; then
	command -v puttygen >/dev/null || die "puttygen is needed to read a .ppk key (apt install putty-tools)"
	SSH_KEY="$(mktemp)"
	CLEANUP_KEY="$SSH_KEY"
	puttygen "$VPS_KEY" -O private-openssh -o "$SSH_KEY" </dev/null \
		|| die "could not convert $VPS_KEY (is it passphrase-protected?)"
	chmod 600 "$SSH_KEY"
fi
trap '[[ -n "$CLEANUP_KEY" ]] && rm -f "$CLEANUP_KEY"' EXIT

SSH_OPTS=(-i "$SSH_KEY" -p "$VPS_PORT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)
remote() { ssh "${SSH_OPTS[@]}" "$VPS_USER@$VPS_HOST" "$@"; }

# --- the port, which is one decision written in two files ------------------------------------------
#
# compose.yml publishes the container on a loopback port and web/noderamc.caddy proxies to it. There
# is no variable the two can share: the Caddy block is a static file installed on a host that has no
# checkout, and the systemd timer runs `compose up` later with no environment of ours. So the number
# is written twice, and the only defence against drift is to compare them before installing either.
# A mismatch is silent and total — Caddy would answer 502 for the site while the container sat there
# perfectly healthy, and the disk standby would hide it by serving yesterday's copy.
#
# Read here rather than in the preflight below because --status exits before that and still has to
# know which port to ask about.
COMPOSE_PORT="$(sed -n 's/.*"127\.0\.0\.1:\${NODERA_WEB_PORT:-\([0-9]\+\)}:8080\/tcp".*/\1/p' \
	"$NODERA_ROOT/docker/compose.yml" | head -1)"
CADDY_PORT="$(sed -n 's/.*reverse_proxy 127\.0\.0\.1:\([0-9]\+\).*/\1/p' \
	"$NODERA_WEB_DIR/noderamc.caddy" | head -1)"
[[ -n "$COMPOSE_PORT" && -n "$CADDY_PORT" ]] \
	|| die "could not read the web port out of docker/compose.yml ($COMPOSE_PORT) and \
web/noderamc.caddy ($CADDY_PORT) — one of them changed shape"
[[ "$COMPOSE_PORT" == "$CADDY_PORT" ]] \
	|| die "docker/compose.yml publishes the site on $COMPOSE_PORT and web/noderamc.caddy proxies to \
$CADDY_PORT; they have to be the same port"
WEB_PORT="$COMPOSE_PORT"

# --- --status ------------------------------------------------------------------------------------

if [[ $STATUS_ONLY -eq 1 ]]; then
	say "compose project"
	remote "cd '$REMOTE_DIR' 2>/dev/null && sudo docker compose --profile site ps || echo '(nothing deployed)'"
	say "update timer"
	remote "systemctl is-enabled nodera-web-update.timer 2>/dev/null || echo 'not installed';
	        systemctl list-timers nodera-web-update.timer --no-pager 2>/dev/null | head -3"
	say "last update run"
	remote "journalctl -u nodera-web-update -n 8 --no-pager 2>/dev/null || echo '(no journal yet)'"
	say "caddy"
	remote "systemctl is-active caddy; ls -l '$CADDY_FILE' 2>/dev/null || echo 'no site block installed'"
	say "what the container answers on the host"
	remote "curl -sS -o /dev/null -w 'http://127.0.0.1:$WEB_PORT/  %{http_code}\n' http://127.0.0.1:$WEB_PORT/ || true"
	say "what the public answers"
	curl -sS -o /dev/null -w '  https://noderamc.org/            %{http_code}\n' https://noderamc.org/ || true
	curl -sS -o /dev/null -w '  https://noderamc.org/add-store   %{http_code}\n' \
		'https://noderamc.org/add-store?url=https%3A%2F%2Fexample.org%2Findex.json' || true
	exit 0
fi

# --- --dry-run -----------------------------------------------------------------------------------

if [[ $DRY_RUN -eq 1 ]]; then
	say "dry run — nothing below is executed"
	echo "  target                $VPS_USER@$VPS_HOST:$VPS_PORT"
	echo "  compose project dir   $REMOTE_DIR"
	echo
	echo "  docker/compose.yml                    -> $REMOTE_DIR/compose.yml"
	echo "  deploy/vps/nodera-web-update.sh       -> $HELPER            (0755 root)"
	echo "  deploy/vps/nodera-web-update.service  -> $UNIT_DIR/         (NODERA_COMPOSE_DIR=$REMOTE_DIR)"
	echo "  deploy/vps/nodera-web-update.timer    -> $UNIT_DIR/         (enabled --now)"
	echo "  web/noderamc.caddy                    -> $CADDY_FILE        (validated, then reload)"
	echo
	echo "  sudo docker compose --profile site pull web"
	echo "  sudo docker compose --profile site up -d web"
	echo "  systemctl daemon-reload && systemctl enable --now nodera-web-update.timer"
	exit 0
fi

# --- preflight -----------------------------------------------------------------------------------
#
# Every one of these is a hard precondition rather than something to work around. A host where sudo
# wants a password cannot be bootstrapped over BatchMode=yes at all, and finding that out here is
# much better than finding it out with a half-installed unit on the box.

say "checking $VPS_USER@$VPS_HOST:$VPS_PORT"
remote true || die "cannot reach the host"
remote 'sudo -n true' >/dev/null 2>&1 || die "sudo needs a password on that host; this script cannot supply one"
remote 'command -v docker >/dev/null' || die "docker is not installed on that host"
remote 'docker compose version >/dev/null 2>&1 || sudo docker compose version >/dev/null 2>&1' \
	|| die "docker compose v2 is not available on that host"
remote 'systemctl is-active caddy >/dev/null' || die "caddy is not running on that host"
remote "test -f '$REMOTE_DIR/.env'" \
	|| die "$REMOTE_DIR/.env does not exist — run scripts/deploy-vps.sh first; compose.yml refuses \
to start without the tracker and relay variables it holds, even for the web service"


# And that the host will actually give it to us. Docker reports a taken port as a networking driver
# failure that names an endpoint id and a subnet — true, and no help at all in working out what to
# do. This host runs OTHER PEOPLE'S services: 8080 belongs to `wings`, the Pelican daemon supervising
# a dozen game servers, which is exactly why the default is not 8080. Never free a port here; report
# who has it and stop.
HOLDER="$(remote "sudo ss -lptnH \"sport = :$WEB_PORT\" 2>/dev/null \
	| grep -v 'nodera-web\|docker-proxy' | head -1" || true)"
if [[ -n "$HOLDER" ]]; then
	die "port $WEB_PORT is already taken on that host by something that is not ours:
    $HOLDER
  Nothing has been changed. Pick a free port and set it in BOTH docker/compose.yml and
  web/noderamc.caddy, or stop whatever that is if it really is yours."
fi

STAGE="/tmp/nodera-bootstrap.$$"
remote "mkdir -p '$STAGE'"

say "staging files"
scp "${SSH_OPTS[@]/-p/-P}" -q \
	"$NODERA_ROOT/docker/compose.yml" \
	"$NODERA_VPS_UNITS/nodera-web-update.sh" \
	"$NODERA_VPS_UNITS/nodera-web-update.service" \
	"$NODERA_VPS_UNITS/nodera-web-update.timer" \
	"$NODERA_WEB_DIR/noderamc.caddy" \
	"$VPS_USER@$VPS_HOST:$STAGE/" || die "staging copy failed"

# --- the compose service --------------------------------------------------------------------------
#
# compose.yml is replaced wholesale and .env is NOT touched: the reservation HMAC key and the
# service identities in there are what make this the same deployment after a restart rather than a
# new one, and scripts/deploy-vps.sh owns that file.

say "installing the compose file and starting the site container"
remote "set -e
  install -m 644 '$STAGE/compose.yml' '$REMOTE_DIR/compose.yml'
  cd '$REMOTE_DIR'
  sudo docker compose --profile site pull --quiet web
  sudo docker compose --profile site up -d web
" || die "the web container did not start; see: scripts/bootstrap-site-host.sh --status"

say "waiting for the container to answer"
for attempt in 1 2 3 4 5 6; do
	if remote "curl -fsS -o /dev/null http://127.0.0.1:$WEB_PORT/" 2>/dev/null; then
		break
	fi
	[[ "$attempt" == 6 ]] && die "the container is up but does not answer on 127.0.0.1:$WEB_PORT — see \
'sudo docker compose --profile site logs web' on the host"
	sleep 5
done
say "the container answers on 127.0.0.1:$WEB_PORT"

# --- the systemd units ------------------------------------------------------------------------------
#
# `install` rather than `cp` so the mode is stated rather than inherited from /tmp, and the
# Environment= line is rewritten to the directory actually deployed to — the unit in the repository
# carries a default, not a decision.

say "installing the update timer"
remote "set -e
  sudo install -d -m 755 '$HELPER_DIR'
  sudo install -m 755 '$STAGE/nodera-web-update.sh' '$HELPER'
  sudo install -m 644 '$STAGE/nodera-web-update.timer' '$UNIT_DIR/nodera-web-update.timer'
  sed 's|^Environment=NODERA_COMPOSE_DIR=.*|Environment=NODERA_COMPOSE_DIR=$REMOTE_DIR|' \
      '$STAGE/nodera-web-update.service' > '$STAGE/nodera-web-update.service.resolved'
  sudo install -m 644 '$STAGE/nodera-web-update.service.resolved' '$UNIT_DIR/nodera-web-update.service'
  sudo systemctl daemon-reload
  sudo systemctl enable --now nodera-web-update.timer
" || die "installing the systemd units failed"

# --- the caddy block ------------------------------------------------------------------------------
#
# Staged, validated, reloaded, and rolled back if EITHER step fails. Both are needed: `caddy
# validate` parses the configuration but never opens a log file, so a block that validates can still
# fail at reload — which is exactly what a root-owned log file did here the first time. A broken
# block must never be the state this script leaves behind, because every other site on this host is
# served by the same Caddy.

say "installing the caddy site block"
remote "set -e
  sudo install -d -m 755 '$WEB_ROOT'

  # systemd runs the reload as root, so an absent log file is CREATED as root and then cannot be
  # opened by the caddy user the service actually runs as. Own it before the reload, every time.
  sudo touch '$LOG_FILE'
  sudo chown caddy:caddy '$LOG_FILE'
  sudo chmod 640 '$LOG_FILE'

  BACKUP=''
  if [ -f '$CADDY_FILE' ]; then
      BACKUP=\$(mktemp)
      sudo cp '$CADDY_FILE' \"\$BACKUP\"
  fi
  sudo install -m 644 '$STAGE/noderamc.caddy' '$CADDY_FILE'

  rollback() {
      if [ -n \"\$BACKUP\" ]; then sudo cp \"\$BACKUP\" '$CADDY_FILE'; rm -f \"\$BACKUP\";
      else sudo rm -f '$CADDY_FILE'; fi
      rm -rf '$STAGE'
  }

  if ! sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1; then
      echo 'caddy validate FAILED — rolled back, nothing reloaded' >&2
      rollback; exit 1
  fi

  if ! sudo systemctl reload caddy; then
      echo 'caddy reload FAILED — rolling back and reloading the previous configuration' >&2
      rollback
      sudo systemctl reload caddy || true
      exit 1
  fi

  [ -n \"\$BACKUP\" ] && rm -f \"\$BACKUP\"
  rm -rf '$STAGE'
  true
" || die "the caddy block was rolled back; the site is unchanged"

# --- prove it ---------------------------------------------------------------------------------------

say "checking what it answers"
sleep 3
curl -sS -o /dev/null -w '  https://noderamc.org/            %{http_code}\n' https://noderamc.org/ || true
curl -sS -o /dev/null -w '  https://noderamc.org/add-store   %{http_code}\n' \
	'https://noderamc.org/add-store?url=https%3A%2F%2Fexample.org%2Findex.json' || true

say "timer"
remote "systemctl list-timers nodera-web-update.timer --no-pager | head -3"

say "done — from here, a merge to main publishes web-canary and the timer picks it up"
