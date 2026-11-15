#!/bin/bash
# macos-bench: macOS counterpart of m2air-bench (Linux) for a MacBook Air M2.
#
# Samples the battery controller's power telemetry (the same SMC system-load
# rail Linux exposes as "Total System Power") plus CPU load, and writes a CSV
# and a JSON summary per scenario. Uses only tools macOS ships; no root needed
# unless you pass --powermetrics, which adds per-cluster CPU residency.
#
#   ./macos-bench.sh sample NAME [SECONDS] [INTERVAL]   sample while you do NAME
#   ./macos-bench.sh exec NAME -- CMD...                sample while CMD runs
#   ./macos-bench.sh sleep                              mark, sleep, report drain
#   ./macos-bench.sh report                             markdown table of all runs
#
# Add --powermetrics before the subcommand to also log `sudo powermetrics`.
# Results: ~/m2air-bench-macos/
set -u

OUT="${M2AIR_BENCH_DIR:-$HOME/m2air-bench-macos}"
POWERMETRICS=0
if [ "${1:-}" = "--powermetrics" ]; then
  POWERMETRICS=1
  shift
fi

die() {
  echo "macos-bench: $*" >&2
  exit 1
}

# One ioreg snapshot of the battery controller as an XML plist.
battery_plist() {
  ioreg -a -r -n AppleSmartBattery
}

# plist_get FILE KEYPATH -> raw value, or empty when the key is absent.
plist_get() {
  plutil -extract "0.$2" raw -o - "$1" 2>/dev/null
}

# ioreg prints negative currents as unsigned 64-bit; fold them back.
signed() {
  echo "if ($1 > 9223372036854775807) $1 - 18446744073709551616 else $1" | bc
}

# Emits: ac sys_w bat_w capacity_mah voltage_mv percent
battery_sample() {
  local tmp ac sys ma mv mah pct
  tmp=$(mktemp)
  battery_plist >"$tmp"
  ac=$(plist_get "$tmp" ExternalConnected)
  [ "$ac" = "true" ] && ac=1 || ac=0
  sys=$(plist_get "$tmp" PowerTelemetryData.SystemLoad)
  ma=$(signed "$(plist_get "$tmp" InstantAmperage || echo 0)")
  mv=$(plist_get "$tmp" Voltage)
  mah=$(plist_get "$tmp" AppleRawCurrentCapacity)
  pct=$(plist_get "$tmp" CurrentCapacity)
  rm -f "$tmp"
  awk -v ac="$ac" -v sys="${sys:-0}" -v ma="${ma:-0}" -v mv="${mv:-0}" \
    -v mah="${mah:-0}" -v pct="${pct:-0}" 'BEGIN {
      printf "%d %.2f %.2f %d %d %d\n", ac, sys / 1000, ma * mv / 1e6, mah, mv, pct
    }'
}

full_wh() {
  local tmp mah
  tmp=$(mktemp)
  battery_plist >"$tmp"
  mah=$(plist_get "$tmp" AppleRawMaxCapacity)
  rm -f "$tmp"
  # 11.4 V nominal pack voltage (52.6 Wh / 4.6 Ah design).
  awk -v mah="${mah:-0}" 'BEGIN { printf "%.1f", mah * 11.4 / 1000 }'
}

# CPU busy percentage over the last interval, from the second `top` sample.
cpu_busy() {
  top -l 2 -n 0 -s "$1" | awk '/^CPU usage/ { idle = $7 } END {
    sub("%", "", idle); printf "%.1f", 100 - idle
  }'
}

sample_loop() {
  local name=$1 duration=$2 interval=$3 watch_pid=${4:-}
  local dir="$OUT/$name" stamp base start now pm_pid=""
  stamp=$(date +%Y%m%d-%H%M%S)
  mkdir -p "$dir"
  base="$dir/$stamp"
  start=$(date +%s)

  if [ "$POWERMETRICS" = 1 ]; then
    sudo -v || die "--powermetrics needs sudo"
    sudo powermetrics --samplers cpu_power,gpu_power,tasks \
      -i $((interval * 1000)) >"$base.powermetrics.txt" 2>&1 &
    pm_pid=$!
  fi

  echo "t,ac,sys_w,bat_w,capacity_mah,voltage_mv,percent,cpu_busy_pct" >"$base.csv"
  while :; do
    local busy
    busy=$(cpu_busy "$interval") # blocks for one interval
    now=$(date +%s)
    # shellcheck disable=SC2046
    set -- $(battery_sample)
    echo "$((now - start)),$1,$2,$3,$4,$5,$6,$busy" >>"$base.csv"
    printf '\r%s %5ds  sys %6s W  battery %6s W  cpu %5s%%' \
      "$name" "$((now - start))" "$2" "$3" "$busy" >&2
    if [ -n "$watch_pid" ]; then
      kill -0 "$watch_pid" 2>/dev/null || break
    elif [ $((now - start)) -ge "$duration" ]; then
      break
    fi
  done
  echo >&2
  [ -n "$pm_pid" ] && sudo kill "$pm_pid" 2>/dev/null

  awk -F, -v name="$name" -v stamp="$stamp" -v full="$(full_wh)" \
    -v osver="$(sw_vers -productVersion)" '
    NR == 1 { next }
    {
      n++; sys += $3; bat += $4; cpu += $8; if ($2 == 1) ac = 1
      if (n == 1 || $3 < min) min = $3
      if ($3 > max) max = $3
      last = $1
    }
    END {
      if (n == 0) exit 1
      mean = sys / n
      printf "{\n"
      printf "  \"scenario\": \"%s\",\n  \"os\": \"macos\",\n", name
      printf "  \"kernel\": \"%s\",\n  \"started\": \"%s\",\n", osver, stamp
      printf "  \"seconds\": %d,\n  \"on_ac\": %s,\n", last, ac ? "true" : "false"
      printf "  \"sys_w_mean\": %.2f,\n  \"sys_w_min\": %.2f,\n", mean, min
      printf "  \"sys_w_max\": %.2f,\n  \"bat_w_mean\": %.2f,\n", max, bat / n
      printf "  \"cpu_busy_pct\": %.1f,\n  \"energy_full_wh\": %.1f,\n", cpu / n, full
      printf "  \"hours_at_this_load\": %.1f\n}\n", mean > 0 ? full / mean : 0
    }' "$base.csv" >"$base.json" || die "no samples collected"

  echo
  cat "$base.json"
  echo "saved $base.csv / .json"
}

cmd_sleep() {
  local dir="$OUT/sleep" mark="$OUT/sleep/.mark"
  mkdir -p "$dir"
  # shellcheck disable=SC2046
  set -- $(battery_sample)
  if [ ! -f "$mark" ]; then
    echo "$(date +%s) $4 $5" >"$mark"
    echo "Marked $4 mAh. Close the lid now; run this command again after waking."
    return
  fi
  local t0 mah0 mv0 hours wh
  read -r t0 mah0 mv0 <"$mark"
  rm -f "$mark"
  hours=$(awk -v a="$t0" -v b="$(date +%s)" 'BEGIN { printf "%.2f", (b - a) / 3600 }')
  wh=$(awk -v a="$mah0" -v b="$4" -v v="$mv0" 'BEGIN { printf "%.2f", (a - b) * v / 1e6 }')
  awk -v h="$hours" -v wh="$wh" -v full="$(full_wh)" -v stamp="$(date +%Y%m%d-%H%M%S)" \
    -v ac="$1" 'BEGIN {
      printf "{\n  \"scenario\": \"sleep\",\n  \"os\": \"macos\",\n"
      printf "  \"started\": \"%s\",\n  \"seconds\": %d,\n", stamp, h * 3600
      printf "  \"on_ac\": %s,\n", ac ? "true" : "false"
      printf "  \"sys_w_mean\": %.3f,\n", h > 0 ? wh / h : 0
      printf "  \"pct_per_hour\": %.2f\n}\n", h > 0 ? 100 * wh / full / h : 0
    }' | tee "$dir/$(date +%Y%m%d-%H%M%S).json"
  echo "slept about $hours h (includes any awake time between the two runs), $wh Wh used"
}

cmd_report() {
  echo "| Scenario | OS | When | Power | Secs | W mean | CPU busy % | Hours |"
  echo "| :-- | :-- | :-- | :-- | --: | --: | --: | --: |"
  local f
  for f in "$OUT"/*/*.json; do
    [ -f "$f" ] || continue
    awk -F'[:,]' '
      { gsub(/[" ]/, "", $1); gsub(/^ *"?|"? *$/, "", $2); v[$1] = $2 }
      END {
        printf "| %s | %s | %s | %s | %s | %s | %s | %s |\n", v["scenario"],
          v["os"], v["started"], v["on_ac"] == "true" ? "AC" : "battery",
          v["seconds"], v["sys_w_mean"],
          ("cpu_busy_pct" in v) ? v["cpu_busy_pct"] : "-",
          ("hours_at_this_load" in v) ? v["hours_at_this_load"] : "-"
      }' "$f"
  done
}

[ "$(uname -s)" = "Darwin" ] || die "this script is for macOS; use m2air-bench on Linux"

case "${1:-}" in
  sample)
    [ -n "${2:-}" ] || die "usage: sample NAME [SECONDS] [INTERVAL]"
    sample_loop "$2" "${3:-600}" "${4:-5}"
    ;;
  exec)
    name=${2:-}
    [ -n "$name" ] && [ "${3:-}" = "--" ] || die "usage: exec NAME -- CMD..."
    shift 3
    "$@" &
    cmd_pid=$!
    SECONDS=0
    sample_loop "$name" 0 2 "$cmd_pid"
    wait "$cmd_pid"
    echo "command exit $?, ${SECONDS} s"
    ;;
  sleep) cmd_sleep ;;
  report) cmd_report ;;
  *)
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
