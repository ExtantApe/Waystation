#!/bin/zsh
# One command to walk the test cave in a real game client: builds Waycave, makes a small made-up server in a temp
# folder (test/prepare-small.mjs), runs test/test-cave.mjs on it, and deletes the temp folder when you press Ctrl-C.
#
#   zsh test/walk-cave.sh YOURNAME [family|kids] [26.3|26.2|26.1.2]
#
# Running it accepts the Minecraft EULA for this throwaway server. It listens on 127.0.0.1 only, offline mode.
# Needs Java 25 (WAYCAVE_TEST_JAVA, or JAVA_HOME) and the test jars in WAYCAVE_TEST_JARS (default ~/.cache/waystation-test).
set -euo pipefail
NAME=${1:?Usage: zsh test/walk-cave.sh YOURNAME [family|kids] [26.3|26.2|26.1.2]}
GROUP=${2:-family}; V=${3:-26.3}
[[ $NAME =~ '^[A-Za-z0-9_]{3,16}$' ]] || { print "Your Minecraft name: 3 to 16 letters, digits or _"; exit 1; }
[[ $GROUP == family || $GROUP == kids ]] || { print "Group is family or kids"; exit 1; }
cd ${0:A:h}/..
export WAYCAVE_TEST_JAVA=${WAYCAVE_TEST_JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}; : ${WAYCAVE_TEST_JAVA:=java}
export WAYCAVE_TEST_JARS=${WAYCAVE_TEST_JARS:-$HOME/.cache/waystation-test}
export WAYCAVE_TEST_PORT=${WAYCAVE_TEST_PORT:-25725} WAYCAVE_TEST_EULA=accept
[[ -f $WAYCAVE_TEST_JARS/paper-$V.jar ]] || { print "Missing $WAYCAVE_TEST_JARS/paper-$V.jar"; exit 1; }
[[ -d test/node_modules ]] || (cd test && npm ci --silent)
print "Building Waycave..."
./gradlew -q clean shadowJar hookJar
BASE=$(mktemp -d "${TMPDIR:-/tmp}/waycave-walk.XXXXXX")
cleanup() { [[ $BASE == */waycave-walk.* ]] && rm -rf -- $BASE; [[ -e $BASE ]] && print "Could not delete $BASE" || true }
trap cleanup EXIT INT TERM
print "Making a small made-up server ($V); this takes a minute or two..."
node test/prepare-small.mjs $BASE/base $V >/dev/null
WAYCAVE_TEST_BASE=$BASE/base WAYCAVE_TEST_PLAYER=$NAME WAYCAVE_TEST_GROUP=$GROUP node test/test-cave.mjs
