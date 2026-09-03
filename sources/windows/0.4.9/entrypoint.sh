#!/bin/bash
set -euo pipefail
umask 077
case "${SERVER_AUTH:-password}" in
  none) echo 'Login disabled by explicit SERVER_AUTH=none configuration.' ;;
  password)
    lab_password_value="${SERVER_KEY:-}"
    if [ "${#lab_password_value}" -lt 4 ] || [[ "$lab_password_value" == replace-with-* ]]; then
      echo 'Set SERVER_KEY in docker-compose.yml to your own key of at least 4 characters.' >&2
      exit 1
    fi
    unset lab_password_value
    ;;
  *) echo 'Invalid SERVER_AUTH mode.' >&2; exit 1 ;;
esac
mkdir -p /data/profile
Xvfb :99 -screen 0 1280x900x24 -nolisten tcp &
xpid=$!
cleanup() {
  trap - EXIT TERM INT
  # Keep Xvfb alive until Node has closed the persistent Chromium context.
  # Killing the display and browser together can leave SingletonLock behind.
  if [ -n "${npid:-}" ] && kill -0 "$npid" 2>/dev/null; then
    kill -TERM "$npid" 2>/dev/null || true
    for attempt in {1..100}; do
      kill -0 "$npid" 2>/dev/null || break
      sleep 0.2
    done
    if kill -0 "$npid" 2>/dev/null; then
      echo 'Browser shutdown exceeded 20 seconds; profile may need offline inspection.' >&2
    else
      wait "$npid" 2>/dev/null || true
    fi
  fi
  for helper_pid in "${wpid:-}" "${vpid:-}" "${opid:-}" "$xpid"; do
    if [ -n "$helper_pid" ]; then kill -TERM "$helper_pid" 2>/dev/null || true; fi
  done
}
trap cleanup EXIT
trap 'exit 0' TERM INT
for attempt in {1..50}; do
  if xdpyinfo -display :99 >/dev/null 2>&1; then break; fi
  sleep 0.1
done
xdpyinfo -display :99 >/dev/null
openbox >/dev/null 2>&1 &
opid=$!
# VNC is accessible only through the HTTP/WebSocket gateway (login mode is explicit).
x11vnc -display :99 -localhost -rfbport 5900 -forever -shared -nopw -quiet >/dev/null 2>&1 &
vpid=$!
websockify 127.0.0.1:7901 127.0.0.1:5900 >/dev/null 2>&1 &
wpid=$!
node src/server.mjs &
npid=$!
wait -n "$xpid" "$opid" "$vpid" "$wpid" "$npid"
exit 1
