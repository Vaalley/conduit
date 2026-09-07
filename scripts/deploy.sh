#!/usr/bin/env bash
# Production deploy on the server box: pull, build (unit + gametests), swap the jar, restart.
# See README "Deploying to Production".
set -euo pipefail
cd "$(dirname "$0")/.."

server_dir="${MCTRAVELER_SERVER_DIR:-/srv/mctraveler}"
service="${MCTRAVELER_SERVICE:-mctraveler}"

[[ -d "$server_dir/mods" ]] || { echo "missing mods directory: $server_dir/mods" >&2; exit 1; }

git pull --ff-only
./gradlew build

version=$(sed -n 's/^mod_version=//p' gradle.properties)
jar="build/libs/mctraveler-${version}.jar"
[[ -f "$jar" ]] || { echo "expected $jar after build" >&2; exit 1; }

systemctl stop "$service"
rm -f "$server_dir"/mods/mctraveler-*.jar
cp "$jar" "$server_dir/mods/"
systemctl start "$service"
echo "deployed $(basename "$jar") to $server_dir/mods/"
