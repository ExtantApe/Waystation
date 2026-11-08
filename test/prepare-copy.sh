#!/bin/bash
# Makes the throwaway test copy from your server as it is now. Your server is only read.
# Usage: prepare-copy.sh <your server folder> <folder for the copy> <GriefPrevention jar>
set -e
SOURCE="${1:?your server folder}"; BASE="${2:?folder for the copy}"; GP="${3:?GriefPrevention jar}"
if lsof -nP -iTCP:25565 -sTCP:LISTEN >/dev/null 2>&1; then echo "Something is listening on 25565. Stop it first."; exit 1; fi
rm -rf "${BASE:?}/server" "${BASE:?}/run"; mkdir -p "$BASE"
cp -cR "$SOURCE" "$BASE/server"
T="$BASE/server"
sed -i '' -e 's/^server-ip=.*/server-ip=127.0.0.1/' -e "s/^server-port=.*/server-port=${WAYSTATION_TEST_PORT:-25599}/" -e 's/^online-mode=.*/online-mode=false/' \
  -e 's/^white-list=.*/white-list=false/' -e 's/^enforce-whitelist=.*/enforce-whitelist=false/' -e 's/^require-resource-pack=.*/require-resource-pack=false/' \
  -e 's/^resource-pack=.*/resource-pack=/' -e 's/^resource-pack-sha1=.*/resource-pack-sha1=/' -e 's/^resource-pack-id=.*/resource-pack-id=/' \
  -e 's/^accepts-transfers=.*/accepts-transfers=true/' "$T/server.properties"
# Nothing on the copy may reach the real backups.
rm -f "$T/plugins/DriveBackupV2.jar"; rm -rf "$T/plugins/DriveBackupV2"
# BlueMap: local only, and its download accepted on this copy alone.
sed -i '' -e 's/^accept-download: false/accept-download: true/' -e 's/^metrics: true/metrics: false/' -e 's/^render-thread-count: .*/render-thread-count: 1/' "$T/plugins/BlueMap/core.conf"
sed -i '' -e 's/^port: .*/port: 8199/' "$T/plugins/BlueMap/webserver.conf"; printf '\nip: "127.0.0.1"\n' >> "$T/plugins/BlueMap/webserver.conf"
cp "$(dirname "$0")/../setup/bluemap-waystation.conf" "$T/plugins/BlueMap/maps/waystation.conf"
cp "$GP" "$T/plugins/GriefPrevention.jar"; mkdir -p "$T/plugins/GriefPreventionData"
printf 'GriefPrevention:\n  MaxPlayersPerIpAddress: 1000\n  Spam:\n    LoginCooldownSeconds: 0\n  Claims:\n    Mode:\n      flatlands: Disabled\n' > "$T/plugins/GriefPreventionData/config.yml"
# The station's world joins the Lobby group, as the install steps say.
python3 - "$T/plugins/Multiverse-Inventories/groups.yml" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
a='  Lobby:\n    worlds:\n    - lobby\n'; assert a in s
open(p,'w').write(s.replace(a,a+'    - waystation\n'))
PY
echo "Prepared $T"
