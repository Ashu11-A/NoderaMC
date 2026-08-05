#!/usr/bin/env bash
# Push the built site to noderamc.org by hand. THE FALLBACK PATH, not the primary one.
#
# <!-- AI-AGENT-INSTRUCTION: This script touches a live host that runs OTHER PEOPLE'S sites out of
#      the same Caddy. Four rules. (1) It may only ever write /var/www/noderamc and the single file
#      /etc/caddy/conf.d/noderamc.caddy — never another conf.d entry, never the parent Caddyfile.
#      (2) `caddy validate` must pass before a reload; a bad block would take every other site on
#      the box down with it, so the install is staged and rolled back if validation fails.
#      (3) Reload, never restart: a restart drops live connections for sites that are nothing to do
#      with us. (4) It must stay idempotent — running it twice changes nothing the second time.
#
#      Do NOT delete this script now that the container lane exists. Nothing in .github holds an SSH
#      secret for this host and nothing is expected to, so CI cannot deploy; when the image pipeline
#      is broken, this is the only way anything reaches the site at all. -->
#
#   scripts/deploy-site.sh                 # build, push the standby copy, reload
#   scripts/deploy-site.sh --dry-run       # show what would be sent, change nothing
#   scripts/deploy-site.sh --status        # what is being served there now, and by which path
#
# HOW THE SITE IS NORMALLY SERVED, and what this script actually does
#
#   Primary path (nothing manual):  merge to main → .github/workflows/containers.yml publishes
#   ghcr.io/ashu11-a/noderamc:web-canary → the VPS's nodera-web-update.timer pulls it within minutes
#   → Caddy reverse-proxies 127.0.0.1:8080. Set up once by scripts/bootstrap-site-host.sh.
#
#   This script is the other half of that block's `handle_errors`: it copies the built site to
#   /var/www/noderamc, which the Caddy block serves ONLY when the container cannot be reached. So a
#   run of this script is not "deploying the site" — it is refreshing the standby and reinstalling
#   the site block. If the container is healthy, what you push here is invisible until it is not.
#
#   Which means: when the image lane is working, prefer it. Use this when it is not.
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
		-h|--help) sed -n '2,32p' "${BASH_SOURCE[0]}"; exit 0 ;;
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
	say "which path is serving"
	# The distinction the header is about, answered rather than assumed: if the container answers on
	# loopback, the public site is the image and the disk copy below is dormant.
	remote "curl -fsS -o /dev/null http://127.0.0.1:8080/ \
	          && echo 'container: UP — the public site is the image; the disk copy is dormant' \
	          || echo 'container: DOWN — the public site is the disk copy this script pushes'"
	say "caddy"
	remote "systemctl is-active caddy; ls -l $CADDY_FILE 2>/dev/null || echo 'no site block installed'"
	say "standby files"
	remote "ls -l $WEB_ROOT 2>/dev/null || echo 'nothing published'"
	say "what it answers"
	curl -sS -o /dev/null -w 'https://noderamc.org/            %{http_code}\n' https://noderamc.org/ || true
	curl -sS -o /dev/null -w 'https://noderamc.org/add-store   %{http_code}\n' 'https://noderamc.org/add-store?url=https%3A%2F%2Fexample.org%2Findex.json' || true
	exit 0
fi

# Said loudly, before anything is sent. The most expensive mistake available here is pushing files,
# reloading, seeing the old site, and concluding the push failed — when in fact it worked and the
# container in front of it is serving something else entirely.
say "FALLBACK MODE — the site is normally served by the web container."
say "               This run refreshes the on-disk standby and the Caddy site block."
say "               Primary path: merge to main, then the VPS timer pulls web-canary."

[[ -f "$NODERA_WEB_DIR/noderamc.caddy" ]] || die "web/noderamc.caddy is missing"

# Built here rather than assumed, so the standby is the same artefact the image serves. This is the
# one command docker/web/Dockerfile runs too; if it fails, it fails identically in both places.
say "building the site"
"$NODERA_ROOT/scripts/build-site.sh"
[[ -f "$NODERA_WEB_DIR/dist/index.html" ]] || die "scripts/build-site.sh produced no dist/index.html"

if [[ $DRY_RUN -eq 1 ]]; then
	say "would publish to $VPS_USER@$VPS_HOST"
	echo "  web/dist/            -> $WEB_ROOT/            (overlaid; /.well-known left alone)"
	echo "  web/noderamc.caddy   -> $CADDY_FILE           (validated, then reload)"
	echo
	echo "  files that would be sent:"
	(cd "$NODERA_WEB_DIR/dist" && find . -type f | sed 's|^\./|    |')
	exit 0
fi

STAGE="/tmp/nodera-site.$$"
say "sending web/dist to $VPS_USER@$VPS_HOST"
remote "mkdir -p $STAGE"
scp "${SSH_OPTS[@]/-p/-P}" -q -r "$NODERA_WEB_DIR/dist" "$VPS_USER@$VPS_HOST:$STAGE/"
scp "${SSH_OPTS[@]/-p/-P}" -q "$NODERA_WEB_DIR/noderamc.caddy" "$VPS_USER@$VPS_HOST:$STAGE/"

say "installing"
# The Caddy block is staged, validated, reloaded, and rolled back if EITHER step fails. Both are
# needed: `caddy validate` parses the configuration but never opens a log file, so a block that
# validates can still fail at reload — which is exactly what a root-owned log file did here the
# first time. A broken block must never be the state this script leaves behind, because every other
# site on this host is served by the same Caddy.
remote "set -e
  sudo install -d -m 755 '$WEB_ROOT'

  # Overlaid, not replaced. /var/www/noderamc also holds /.well-known, which is served from disk on
  # purpose (Android App Links) and is nothing to do with the site build — wiping the directory
  # would take assetlinks.json with it. Old hashed assets left behind are harmless: their names are
  # unique, so nothing new ever collides with them.
  sudo cp -a '$STAGE/dist/.' '$WEB_ROOT/'
  sudo chmod -R a+rX '$WEB_ROOT'

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
  true
"

say "checking what it answers"
sleep 3
curl -sS -o /dev/null -w '  https://noderamc.org/            %{http_code}\n' https://noderamc.org/ || true
curl -sS -o /dev/null -w '  https://noderamc.org/add-store   %{http_code}\n' \
	'https://noderamc.org/add-store?url=https%3A%2F%2Fexample.org%2Findex.json' || true

# The last word, because a green 200 above proves the site is up and says nothing about which copy
# answered it.
remote "curl -fsS -o /dev/null http://127.0.0.1:8080/ \
          && echo '==> the container is up, so those responses came from the IMAGE, not from what \
this run just pushed. The standby is refreshed and dormant.' \
          || echo '==> the container is down, so those responses came from the copy this run \
pushed. Bring the image back with: sudo systemctl start nodera-web-update.service'"

say "done"
