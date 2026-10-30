#!/bin/bash

export JAVA_TOOL_OPTIONS=$2

display=${IBAUTOMATER_DISPLAY:-1}
case "$display" in
    ''|*[!0-9]*)
        echo "IBAutomater.sh: IBAUTOMATER_DISPLAY must be a display number, not '$display'" >&2
        exit 2
        ;;
esac
command -v setsid >/dev/null || { echo "IBAutomater.sh: setsid not found" >&2; exit 2; }

pidfile=/tmp/IBAutomater.display$display.pid
ownGroup=$(ps -o pgid= -p $$ | tr -d ' ')

startedAt() {
    ps -o lstart= -p "$1" 2>/dev/null | sed 's/^ *//;s/ *$//'
}

ps -AFH

# kill only the process groups a previous start on this display recorded; a pid now
# belonging to another process (its start time differs) is left alone
if [ -f "$pidfile" ]; then
    while read -r pid started; do
        case "$pid" in ''|*[!0-9]*) continue ;; esac
        [ "$pid" = "$ownGroup" ] && continue
        now=$(startedAt "$pid")
        if [ -n "$now" ] && [ "$now" != "$started" ]; then
            echo "IBAutomater.sh: pid $pid is not the process recorded, left running"
            continue
        fi
        echo "IBAutomater.sh: killing process group $pid"
        kill -9 -- "-$pid" 2>/dev/null
    done < "$pidfile"
    rm -f "$pidfile"
fi

sleep 5

rm -f "/tmp/.X$display-lock"
ps -AFH

unset DISPLAY
setsid -w Xvfb ":$display" -screen 0 1024x768x24 2>&1 >/dev/null &
echo "$! $(startedAt $!)" >> "$pidfile"
export DISPLAY=":$display"

ibgatewayExecutable=$1
shift

setsid -w "$ibgatewayExecutable" "$@" <&0 &
gateway=$!
echo "$gateway $(startedAt $gateway)" >> "$pidfile"
wait "$gateway"
