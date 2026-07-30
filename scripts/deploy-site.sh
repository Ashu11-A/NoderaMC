#!/usr/bin/env bash
# Publish site/ to noderamc.org, and install the Caddy site block that serves it.
#
# <!-- AI-AGENT-INSTRUCTION: This script touches a live host that runs OTHER PEOPLE'S sites out of
#      the same Caddy. Four rules. (1) It may only ever write /var/www/noderamc and the single file
#      /etc/caddy/conf.d/noderamc.caddy — never another conf.d entry, never the parent Caddyfile.
#      (2) `caddy validate` must pass before a reload; a bad block would take every other site on
#      the box down with it, so the install is staged and rolled back if validation fails.
#      (3) Reload, never restart: a restart drops live connections for sites that are nothing to do
#      with us. (4) It must stay idempotent — running it twice changes nothing the second time. -->
#
#   scripts/deploy-site.sh                 # publish and reload
#   scripts/deploy-site.sh --dry-run       # show what would be sent, change nothing
#   scripts/deploy-site.sh --status        # what is being served there now
#
# The key is the same PuTTY key scripts/deploy-vps.sh uses, converted in a temp file that is removed
# on exit. Nothing here prints, copies, or logs key material.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export

VPS_HOST="${NODERA_VPS_HOST:-150.230.84.206}"
VPS_USER="${NODERA_VPS_USER:-ubuntu}"
VPS_PORT="${NODERA_VPS_PORT:-22}"
VPS_KEY="${NODERA_VPS_KEY:-$HOME/Documents/vps/ChavePrivada.ppk}"

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
		--dry-run) DRY_RUN=1; shift ;;
		--status) STATUS_ONLY=1; shift ;;
		-h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
		*) echo "unknown option: $1" >&2; exit 2 ;;
	esac
done

die() { echo "deploy-site: $*" >&2; exit 1; }
say() { echo "==> $*"; }

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

if [[ $STATUS_ONLY -eq 1 ]]; then
	say "caddy"
	remote "systemctl is-active caddy; ls -l $CADDY_FILE 2>/dev/null || echo 'no site block installed'"
	say "published files"
	remote "ls -l $WEB_ROOT 2>/dev/null || echo 'nothing published'"
	say "what it answers"
	curl -sS -o /dev/null -w 'https://noderamc.org/            %{http_code}\n' https://noderamc.org/ || true
	curl -sS -o /dev/null -w 'https://noderamc.org/add-store   %{http_code}\n' 'https://noderamc.org/add-store?url=https%3A%2F%2Fexample.org%2Findex.json' || true
	exit 0
fi

for f in index.html add-store.html noderamc.caddy; do
	[[ -f "$NODERA_WEB_DIR/$f" ]] || die "site/$f is missing"
done

if [[ $DRY_RUN -eq 1 ]]; then
	say "would publish to $VPS_USER@$VPS_HOST"
	echo "  site/index.html      -> $WEB_ROOT/index.html"
	echo "  site/add-store.html  -> $WEB_ROOT/add-store.html"
	echo "  site/noderamc.caddy  -> $CADDY_FILE   (validated, then reload)"
	exit 0
fi

STAGE="/tmp/nodera-site.$$"
say "sending site/ to $VPS_USER@$VPS_HOST"
remote "mkdir -p $STAGE"
scp "${SSH_OPTS[@]/-p/-P}" -q \
	"$NODERA_WEB_DIR/index.html" "$NODERA_WEB_DIR/add-store.html" "$NODERA_WEB_DIR/noderamc.caddy" \
	"$VPS_USER@$VPS_HOST:$STAGE/"

say "installing"
# The Caddy block is staged, validated, reloaded, and rolled back if EITHER step fails. Both are
# needed: `caddy validate` parses the configuration but never opens a log file, so a block that
# validates can still fail at reload — which is exactly what a root-owned log file did here the
# first time. A broken block must never be the state this script leaves behind, because every other
# site on this host is served by the same Caddy.
remote "set -e
  sudo install -d -m 755 '$WEB_ROOT'
  sudo install -m 644 '$STAGE/index.html' '$STAGE/add-store.html' '$WEB_ROOT/'

  # systemd runs the reload as root, so an absent log file is CREATED as root and then cannot be
  # opened by the caddy user the service actually runs as. Own it before the reload, every time:
  # the file may already exist from a previous failed attempt.
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
"

say "checking what it answers"
sleep 3
curl -sS -o /dev/null -w '  https://noderamc.org/            %{http_code}\n' https://noderamc.org/ || true
curl -sS -o /dev/null -w '  https://noderamc.org/add-store   %{http_code}\n' \
	'https://noderamc.org/add-store?url=https%3A%2F%2Fexample.org%2Findex.json' || true

say "done"
