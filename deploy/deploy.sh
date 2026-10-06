#!/bin/bash
# Deploys a release folder (jobagent.jar + web/) onto this machine and restarts the app.
# Usage: deploy.sh <release-dir>
#
# 1. keep the running jar as jobagent.jar.prev
# 2. put the new jar and the web UI in place
# 3. restart the launchd service
# 4. wait until the health check answers UP; if it does not, put the old jar back and restart again (exit 1)
#
# Settings (environment, defaults match the home server):
#   APP_DIR      ~/jobagent
#   SERVICE      com.jobagent.app
#   HEALTH_URL   http://localhost:8080/actuator/health
#   WAIT_TRIES   45      (checks, 2 s apart)
#
# Limits: a database migration that the new version ran stays applied when the old jar is put back (migrations
# here only ever add things, so the old jar keeps working); work running during the restart (a crawl, an Opus
# search) is interrupted.
set -euo pipefail

RELEASE="${1:?usage: deploy.sh <release-dir>}"
APP_DIR="${APP_DIR:-$HOME/jobagent}"
SERVICE="${SERVICE:-com.jobagent.app}"
HEALTH_URL="${HEALTH_URL:-http://localhost:8080/actuator/health}"
WAIT_TRIES="${WAIT_TRIES:-45}"

[ -f "$RELEASE/jobagent.jar" ] || { echo "no $RELEASE/jobagent.jar"; exit 1; }
mkdir -p "$APP_DIR" "$APP_DIR/web"

restart() {
  launchctl kickstart -k "gui/$(id -u)/$SERVICE"
}

healthy() {
  for _ in $(seq 1 "$WAIT_TRIES"); do
    if curl -fsS --max-time 3 "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# 1 + 2: the jar is copied next to the old one and renamed, so a crash cannot leave half a file in place
[ -f "$APP_DIR/jobagent.jar" ] && cp "$APP_DIR/jobagent.jar" "$APP_DIR/jobagent.jar.prev"
cp "$RELEASE/jobagent.jar" "$APP_DIR/jobagent.jar.new"
mv "$APP_DIR/jobagent.jar.new" "$APP_DIR/jobagent.jar"
if [ -d "$RELEASE/web" ]; then
  rsync -a --checksum --delete "$RELEASE/web/" "$APP_DIR/web/"
fi

# 3 + 4
echo "Restarting $SERVICE"
restart
if healthy; then
  echo "Deployed: the app is healthy"
  exit 0
fi

echo "The new version did not become healthy; putting the previous jar back"
if [ -f "$APP_DIR/jobagent.jar.prev" ]; then
  cp "$APP_DIR/jobagent.jar.prev" "$APP_DIR/jobagent.jar"
  restart
  healthy && echo "Rolled back: the previous version is running again" || echo "The previous version is not healthy either"
fi
exit 1
