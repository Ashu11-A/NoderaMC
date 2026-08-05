#!/usr/bin/env bash
# Pull the published web image and restart the site container. Run on the VPS by systemd, never
# from a laptop.
#
# <!-- AI-AGENT-INSTRUCTION: This runs as root on a host that serves OTHER PEOPLE'S sites and other
#      people's containers. Three rules, and they are the same three scripts/deploy-vps.sh carries.
#      (1) It may only ever touch the `nodera` compose project, by name, one service at a time. A
#      `docker system prune`, a global `docker stop`, a `docker image prune` with no explicit target,
#      or a `--remove-orphans` outside the project is BANNED here — not discouraged, banned; the
#      blast radius is somebody else's production. (2) It must be idempotent: on the overwhelmingly
#      common run there is no new digest and this script must change nothing at all. (3) It must
#      never print or log credentials. -->
#
# Installed by scripts/bootstrap-site-host.sh, together with nodera-web-update.service and .timer.
# The audit log is `journalctl -u nodera-web-update`.
#
# Why a pull loop rather than a webhook or watchtower: this runs the exact command the project
# already documents as its upgrade path, it adds no third-party image, no inbound port, no Docker
# socket handed to code we do not own, and no SSH key stored in GitHub. Its one cost is that a new
# site is live within the timer's period rather than instantly, which for a website is not a cost.

set -euo pipefail

# Where the compose project lives on this host. Overridden by the systemd unit's `Environment=`
# line, which scripts/bootstrap-site-host.sh rewrites to whatever --dir it deployed to. Not
# hardcoded anywhere a second file would have to agree with.
COMPOSE_DIR="${NODERA_COMPOSE_DIR:-/home/ubuntu/nodera}"
PROJECT="${NODERA_COMPOSE_PROJECT:-nodera}"
SERVICE="${NODERA_COMPOSE_SERVICE:-web}"

log() { printf 'nodera-web-update: %s\n' "$*"; }

[ -f "$COMPOSE_DIR/compose.yml" ] || {
	printf 'nodera-web-update: %s/compose.yml is missing — has the host been bootstrapped?\n' \
		"$COMPOSE_DIR" >&2
	exit 1
}

# Every docker invocation in this file goes through here. The project name and the file are pinned
# on the command line rather than inherited from the working directory, so a `cd` that went
# somewhere unexpected cannot silently widen what this touches. `--profile site` is explicit
# because enabling a profile by naming its service only works on newer Compose.
compose() {
	docker compose \
		--project-name "$PROJECT" \
		--project-directory "$COMPOSE_DIR" \
		--file "$COMPOSE_DIR/compose.yml" \
		--profile site \
		"$@"
}

# The image this service is running RIGHT NOW, by id. Recorded before the pull so the reclaim step
# at the bottom has exactly one candidate and never has to guess.
image_of_running_service() {
	local container
	container="$(compose ps --quiet "$SERVICE" 2>/dev/null | head -1)"
	[ -n "$container" ] || return 0
	docker inspect --format '{{.Image}}' "$container" 2>/dev/null || true
}

before="$(image_of_running_service)"

log "pulling ${SERVICE}"
compose pull --quiet "$SERVICE"

log "bringing ${SERVICE} up"
# A no-op when the digest has not moved, which is what nearly every run of this script is.
compose up --detach "$SERVICE"

after="$(image_of_running_service)"

if [ -z "$before" ] || [ "$before" = "$after" ]; then
	log "no change (${after:-not running})"
	exit 0
fi

log "updated ${before:0:19} -> ${after:0:19}"

# --- reclaim, scoped to the single image this run replaced ---------------------------------------
#
# `docker image prune` cannot express "mine": its filters are `dangling`, `until` and `label`, none
# of which distinguish our leftovers from the leftovers of everything else on this box. So the
# reclaim is not a prune at all — it is a removal of one image, identified by id, which this script
# watched go out of use ten lines ago.
#
# Two guards, and both are required. A tag still pointing at it means somebody (or the .env's
# NODERA_IMAGE_TAG pin) can still ask for it by name. A container still referencing it means it is
# in use, possibly by a service this script has no business touching.
tags="$(docker image inspect --format '{{join .RepoTags ","}}' "$before" 2>/dev/null || true)"
users="$(docker ps --all --quiet --filter "ancestor=$before" | wc -l | tr -d ' ')"

if [ -n "$tags" ]; then
	log "keeping the previous image: still tagged ($tags)"
elif [ "$users" != "0" ]; then
	log "keeping the previous image: $users container(s) still reference it"
else
	log "reclaiming the replaced image"
	docker image rm "$before" >/dev/null || log "could not remove ${before:0:19}; leaving it"
fi
