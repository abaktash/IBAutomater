#!/bin/bash
# Tests for QuantConnect.IBAutomater/IBAutomater.sh with stand-in Xvfb and gateway on PATH,
# on displays 97-99 so no real display is touched.
#   bash QuantConnect.IBAutomater.Tests/test_IBAutomater_sh.sh [path/to/IBAutomater.sh]
set -u

here=$(cd "$(dirname "$0")" && pwd)
script=${1:-$here/../QuantConnect.IBAutomater/IBAutomater.sh}
[ -f "$script" ] || { echo "FATAL: $script not found"; exit 2; }

T=$(mktemp -d)
export FAKE_OUT=$T/out FAKE_BIN=$T/bin
mkdir -p "$FAKE_OUT" "$FAKE_BIN"
ln -s "$(command -v sleep)" "$FAKE_BIN/java"
mkdir -p "$T/decoy"
ln -s "$(command -v sleep)" "$T/decoy/java"
ln -s "$(command -v sleep)" "$T/decoy/Xvfb"
cat > "$FAKE_BIN/Xvfb" <<'EOF'
#!/bin/bash
echo "ARGS=$*" > "$FAKE_OUT/xvfb.$$"
exec sleep 300
EOF
cat > "$FAKE_BIN/gateway" <<'EOF'
#!/bin/bash
out="$FAKE_OUT/gateway.$$"
{ echo "DISPLAY=$DISPLAY"; echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS"; echo "ARGS=$*"; } > "$out"
[ -n "${FAKE_GATEWAY_EXIT:-}" ] && exit "$FAKE_GATEWAY_EXIT"
"$FAKE_BIN/java" 300 &
echo "CHILD=$!" >> "$out"
wait
EOF
chmod +x "$FAKE_BIN/Xvfb" "$FAKE_BIN/gateway"
export PATH=$FAKE_BIN:$PATH

pidfile() { echo "/tmp/IBAutomater.display$1.pid"; }
for d in 97 98 99; do rm -f "$(pidfile $d)"; done

cleanup() {
    exec 2>/dev/null
    for d in 97 98 99; do
        if [ -f "$(pidfile $d)" ]; then
            while read -r pid _; do kill -9 -- "-$pid" 2>/dev/null; done < "$(pidfile $d)"
            rm -f "$(pidfile $d)"
        fi
        rm -f "/tmp/.X$d-lock"
    done
    kill -9 $(jobs -p) 2>/dev/null
    rm -rf "$T"
}
trap cleanup EXIT

passed=0; failed=0
check() {
    if eval "$2"; then passed=$((passed+1)); echo "PASS $1"
    else failed=$((failed+1)); echo "FAIL $1   [$2]"; fi
}
dead() { local s; s=$(ps -o stat= -p "$1" 2>/dev/null); [ -z "$s" ] || [ "${s#Z}" != "$s" ]; }
alive() { ! dead "$1"; }
until_true() { local i; for i in $(seq 1 100); do eval "$1" && return 0; sleep 0.2; done; return 1; }
lines() { [ -f "$(pidfile $1)" ] && wc -l < "$(pidfile $1)" || echo 0; }
recorded() { sed -n "${2}p" "$(pidfile $1)" | cut -d' ' -f1; }
field() { sed -n "s/^$2=//p" "$FAKE_OUT/gateway.$1"; }
launch() { IBAUTOMATER_DISPLAY=$1 bash "$script" gateway "-javaagent:fake.jar=fake.json" "-J-DjtsConfigDir=/fake" > "$T/launch.$1.$2.log" 2>&1 & }

setsid -w "$T/decoy/java" 300 & decoyJava=$!
setsid -w "$T/decoy/Xvfb" 300 & decoyXvfb=$!

launch 97 1; launcher97=$!
launch 98 1
until_true '[ "$(lines 97)" = 2 ] && [ "$(lines 98)" = 2 ]' || echo "FATAL: the first starts did not record two processes each"
xvfb97=$(recorded 97 1); gw97=$(recorded 97 2); gw98=$(recorded 98 2)
until_true '[ -n "$(field $gw97 CHILD 2>/dev/null)" ]'
child97=$(field "$gw97" CHILD)

check "a start kills nothing it did not start" 'alive $decoyJava && alive $decoyXvfb'
check "the display comes from IBAUTOMATER_DISPLAY" '[ "$(field $gw97 DISPLAY)" = ":97" ] && grep -q "ARGS=:97 " "$FAKE_OUT/xvfb.$xvfb97"'
check "JAVA_TOOL_OPTIONS and the arguments pass as before" '[ "$(field $gw97 JAVA_TOOL_OPTIONS)" = "-javaagent:fake.jar=fake.json" ] && [ "$(field $gw97 ARGS)" = "-javaagent:fake.jar=fake.json -J-DjtsConfigDir=/fake" ]'
check "each recorded process leads its own process group" '[ "$(ps -o pgid= -p $gw97 | tr -d " ")" = "$gw97" ] && [ "$(ps -o pgid= -p $xvfb97 | tr -d " ")" = "$xvfb97" ]'

launch 97 2
until_true '[ "$(lines 97)" = 2 ] && [ "$(recorded 97 2)" != "$gw97" ]' || echo "FATAL: the restart on display 97 did not record its processes"
until_true 'dead $gw97 && dead $child97 && dead $xvfb97'
check "a restart kills the previous start's Xvfb, gateway and the gateway's child" 'dead $xvfb97 && dead $gw97 && dead $child97'
check "the previous launcher returns when its gateway is killed" 'until_true "dead $launcher97"'
check "another display's gateway survives" 'alive $gw98'
check "decoys survive a restart" 'alive $decoyJava && alive $decoyXvfb'
check "the restart logged what it killed" 'grep -q "killing process group $gw97" "$T/launch.97.2.log"'

echo "$decoyJava Mon Jan  1 00:00:00 2001" > "$(pidfile 99)"
launch 99 1
until_true '[ "$(lines 99)" = 2 ]'
check "a recorded pid whose start time differs is left running" 'alive $decoyJava && grep -q "pid $decoyJava is not the process recorded" "$T/launch.99.1.log"'

before=$(ls "$FAKE_OUT" | wc -l)
IBAUTOMATER_DISPLAY=abc bash "$script" gateway x > "$T/launch.abc.log" 2>&1; rc=$?
check "a display that is not a number exits 2 before anything" '[ "$rc" = 2 ] && grep -q "must be a display number" "$T/launch.abc.log" && [ "$(ls "$FAKE_OUT" | wc -l)" = "$before" ]'

FAKE_GATEWAY_EXIT=7 IBAUTOMATER_DISPLAY=98 bash "$script" gateway x > "$T/launch.98.2.log" 2>&1; rc=$?
check "the script returns the gateway's exit status" '[ "$rc" = 7 ]'
check "that start on display 98 killed the earlier display-98 gateway" 'dead $gw98'

if [ -e /tmp/.X1-lock ] || pgrep -x Xvfb | grep -v -x "$decoyXvfb" | grep -q . || [ -e "$(pidfile 1)" ]; then
    echo "SKIP the default display is 1 (display 1 or its files are in use on this host)"
else
    FAKE_GATEWAY_EXIT=0 bash "$script" gateway x > "$T/launch.1.log" 2>&1
    gw1=$(recorded 1 2)
    check "the default display is :1, as upstream" '[ "$(field $gw1 DISPLAY)" = ":1" ]'
    while read -r pid _; do kill -9 -- "-$pid" 2>/dev/null; done < "$(pidfile 1)"
    rm -f "$(pidfile 1)"
fi

echo "IBAutomater.sh tests: $passed passed, $failed failed"
[ "$failed" = 0 ]
