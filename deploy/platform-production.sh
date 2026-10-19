#!/bin/sh
# Deploy prebuilt application images only. Does not run migrations or touch DB.
set -eu
if [ "$#" -ne 3 ]; then
  echo 'Usage: platform-production.sh check|up|status RELEASE_DIRECTORY IMMUTABLE_IMAGE' >&2
  exit 2
fi
action=$1
release=$(readlink -f "$2")
BJTU_PLATFORM_IMAGE=$3
export BJTU_PLATFORM_IMAGE
case "$action" in check|up|status) ;; *) exit 2 ;; esac
printf '%s\n' "$BJTU_PLATFORM_IMAGE" | grep -Eq '^(sha256:[a-f0-9]{64}|[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64})$' || {
  echo 'An immutable locally loaded image digest is required' >&2
  exit 2
}
test -d "$release"
ops=$(CDPATH= cd -- "$(dirname -- "$(readlink -f "$0")")" && pwd)
docker image inspect "$BJTU_PLATFORM_IMAGE" >/dev/null
compose() {
  docker compose -p bjtu-plugin-platform \
    -f "$release/deploy/docker-compose.plugins.yml" \
    -f "$ops/docker-compose.production.yml" \
    -f "$ops/docker-compose.resources.yml" "$@"
}
compose config --format json | python3 -c '
import json,os,sys
c=json.load(sys.stdin)
for name in ("api","worker","migrate"):
 s=c["services"][name]
 if s.get("build") or s["image"]!=os.environ["BJTU_PLATFORM_IMAGE"]:
  raise SystemExit("Production source build or image mismatch rejected")
print("Validated image-only production configuration")
'
case "$action" in
  check) ;;
  status) compose ps ;;
  up) compose up -d --no-deps --no-build --pull never api worker ;;
esac
