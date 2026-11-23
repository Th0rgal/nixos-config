#!/usr/bin/env bash
# razr-desktop.sh — Termux-side launcher for the nested Wayland Hyprland desktop on Razr 60 Ultra.
#
# Runs from Termux (unprivileged Termux UID for Termux:X11 & PulseAudio, then bridges
# into the running NixOS container via su / razr-container.sh as user `thomas`).
#
# Display pipeline (zero video encoding, shared-memory X11 -> nested Wayland):
#   Termux:X11 (:0, fullscreen on Smart Connect Miracast)
#     <- weston --backend=x11-backend.so --fullscreen --socket=wayland-nested
#       <- Hyprland (WAYLAND_DISPLAY=wayland-nested, PULSE_SERVER=tcp:127.0.0.1:4713)
#
# Usage:
#   razr-desktop.sh [start|stop|status]
#
# Environment overrides:
#   RAZR_USER=thomas                   (default: thomas)
#   X11_DISPLAY=:0                     (default: :0)
#   WAYLAND_SOCKET=wayland-nested      (default: wayland-nested)
#   PULSE_PORT=4713                    (default: 4713)
#   RAZR_CONTAINER_SCRIPT=<path>       (default: alongside this script)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAZR_CONTAINER_SCRIPT="${RAZR_CONTAINER_SCRIPT:-${SCRIPT_DIR}/razr-container.sh}"
RAZR_USER="${RAZR_USER:-thomas}"
X11_DISPLAY="${X11_DISPLAY:-:0}"
X11_DISPLAY_NUM="${X11_DISPLAY#:}"
WAYLAND_SOCKET="${WAYLAND_SOCKET:-wayland-nested}"
PULSE_PORT="${PULSE_PORT:-4713}"

TERMUX_TMP="${TMPDIR:-/data/data/com.termux/files/usr/tmp}"
X11_SOCK_DIR="${TERMUX_TMP}/.X11-unix"
X11_SOCK="${X11_SOCK_DIR}/X${X11_DISPLAY_NUM}"
X11_LOG="${TERMUX_TMP}/razr-termux-x11.log"

log() { printf '[razr-desktop] %s\n' "$*" >&2; }
die() { printf '[razr-desktop] ERROR: %s\n' "$*" >&2; exit 1; }

run_container() {
    local subcmd="$1"
    shift
    local args=""
    if [[ $# -gt 0 ]]; then
        args="$(printf '%q ' "$@")"
    fi
    if [[ "$(id -u)" -eq 0 ]]; then
        bash "${RAZR_CONTAINER_SCRIPT}" "${subcmd}" "$@"
    else
        command -v su >/dev/null 2>&1 || die "su not found; root access is required to enter the NixOS container."
        su -c "PATH='/data/data/com.termux/files/usr/bin:/data/local/Droidspaces/bin:/data/local/nixos/bin:/system/bin:/sbin' bash '${RAZR_CONTAINER_SCRIPT}' '${subcmd}' ${args}"
    fi
}

start_pulseaudio() {
    if ! command -v pulseaudio >/dev/null 2>&1; then
        log "WARNING: pulseaudio not installed in Termux (pkg install pulseaudio). Audio will be unavailable."
        return 0
    fi

    if pulseaudio --check 2>/dev/null; then
        log "Termux PulseAudio already running; ensuring TCP module on 127.0.0.1:${PULSE_PORT}."
        if command -v pactl >/dev/null 2>&1; then
            if ! pactl list short modules 2>/dev/null | grep -q "module-native-protocol-tcp"; then
                pactl load-module module-native-protocol-tcp "port=${PULSE_PORT}" "auth-ip-acl=127.0.0.1" "auth-anonymous=1" >/dev/null 2>&1 || true
            fi
        fi
        return 0
    fi

    log "Starting Termux PulseAudio (TCP 127.0.0.1:${PULSE_PORT})..."
    pulseaudio --start \
        --load="module-native-protocol-tcp port=${PULSE_PORT} auth-ip-acl=127.0.0.1 auth-anonymous=1" \
        --exit-idle-time=-1
}

start_termux_x11() {
    command -v termux-x11 >/dev/null 2>&1 || die "termux-x11 not installed in Termux (pkg install x11-repo && pkg install termux-x11-nightly)."

    mkdir -p "${X11_SOCK_DIR}"
    chmod 1777 "${X11_SOCK_DIR}" 2>/dev/null || true

    if [[ -S "${X11_SOCK}" ]] && pgrep -f "termux-x11.*${X11_DISPLAY}" >/dev/null 2>&1; then
        log "termux-x11 ${X11_DISPLAY} is already running."
    else
        rm -f "${X11_SOCK}" 2>/dev/null || true
        log "Starting termux-x11 ${X11_DISPLAY} (log: ${X11_LOG})..."
        nohup termux-x11 "${X11_DISPLAY}" -ac >"${X11_LOG}" 2>&1 </dev/null &

        local i
        for i in $(seq 1 50); do
            if [[ -S "${X11_SOCK}" ]]; then
                break
            fi
            sleep 0.2
        done
        [[ -S "${X11_SOCK}" ]] || die "Timed out waiting for ${X11_SOCK}. Check ${X11_LOG}."
    fi

    # Best-effort: bring Termux:X11 activity to foreground (e.g. on Smart Connect Miracast display).
    if command -v am >/dev/null 2>&1; then
        am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1 || true
    fi
}

ensure_container_running() {
    if ! run_container status >/dev/null 2>&1; then
        log "NixOS container is stopped; starting it now..."
        run_container start
        # Give systemd a moment to finish basic.target / user runtime setup.
        sleep 2
    fi
}

launch_nested_hyprland() {
    log "Launching fullscreen Weston (${WAYLAND_SOCKET}) + Hyprland as '${RAZR_USER}' inside NixOS..."

    # Build the container-side desktop session command.
    # Reads ~/.config/razr-gpu-mode (default: hw) so hw (Turnip+Zink) and sw (llvmpipe + no blur) work seamlessly.
    local inner_cmd
    inner_cmd="$(cat <<EOF
set -euo pipefail

export DISPLAY='${X11_DISPLAY}'
export PULSE_SERVER='tcp:127.0.0.1:${PULSE_PORT}'
export XDG_RUNTIME_DIR="/run/user/\$(id -u)"
mkdir -p "\${XDG_RUNTIME_DIR}"
chmod 0700 "\${XDG_RUNTIME_DIR}"

# Apply persisted GPU mode (~/.config/razr-gpu-mode: hw or sw).
GPU_MODE="hw"
if [[ -f "\${HOME}/.config/razr-gpu-mode" ]]; then
    GPU_MODE="\$(tr -d '[:space:]' < "\${HOME}/.config/razr-gpu-mode")"
fi

if [[ "\${GPU_MODE}" == "sw" ]]; then
    export LIBGL_ALWAYS_SOFTWARE=1
    export WLR_RENDERER_ALLOW_SOFTWARE=1
    export RAZR_GPU_MODE=sw
    unset MESA_LOADER_DRIVER_OVERRIDE TU_DEBUG
else
    export MESA_LOADER_DRIVER_OVERRIDE=zink
    export TU_DEBUG=noconform
    export RAZR_GPU_MODE=hw
    unset LIBGL_ALWAYS_SOFTWARE
fi

WAYLAND_SOCK='${WAYLAND_SOCKET}'
rm -f "\${XDG_RUNTIME_DIR}/\${WAYLAND_SOCK}" "\${XDG_RUNTIME_DIR}/\${WAYLAND_SOCK}.lock"

# Start fullscreen borderless Weston over Termux:X11 shared memory (zero video encoding).
weston --backend=x11-backend.so --fullscreen --socket="\${WAYLAND_SOCK}" >"\${XDG_RUNTIME_DIR}/weston.log" 2>&1 &
WESTON_PID=\$!

cleanup() {
    kill "\${WESTON_PID}" 2>/dev/null || true
    wait "\${WESTON_PID}" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

for _ in \$(seq 1 50); do
    if [[ -S "\${XDG_RUNTIME_DIR}/\${WAYLAND_SOCK}" ]]; then
        break
    fi
    sleep 0.2
done

if [[ ! -S "\${XDG_RUNTIME_DIR}/\${WAYLAND_SOCK}" ]]; then
    echo "[razr-desktop] ERROR: Weston socket \${XDG_RUNTIME_DIR}/\${WAYLAND_SOCK} did not appear. Log:" >&2
    cat "\${XDG_RUNTIME_DIR}/weston.log" >&2 || true
    exit 1
fi

export WAYLAND_DISPLAY="\${WAYLAND_SOCK}"
export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=Hyprland

# In software mode, disable Hyprland blur once Hyprland is listening (best-effort for non-Lua configs).
if [[ "\${GPU_MODE}" == "sw" ]]; then
    (
        for _ in \$(seq 1 30); do
            if hyprctl version >/dev/null 2>&1; then
                hyprctl keyword decoration:blur:enabled false >/dev/null 2>&1 || true
                break
            fi
            sleep 0.3
        done
    ) &
fi

exec Hyprland
EOF
)"

    run_container shell "${RAZR_USER}" -- bash -lc "${inner_cmd}"
}

cmd_start() {
    start_pulseaudio
    start_termux_x11
    ensure_container_running
    launch_nested_hyprland
}

cmd_stop() {
    log "Stopping Hyprland and Weston inside NixOS container..."
    run_container shell "${RAZR_USER}" -- bash -lc 'pkill -TERM Hyprland 2>/dev/null || true; pkill -TERM weston 2>/dev/null || true' 2>/dev/null || true

    log "Stopping termux-x11 (${X11_DISPLAY})..."
    pkill -f "termux-x11.*${X11_DISPLAY}" 2>/dev/null || true
    rm -f "${X11_SOCK}" 2>/dev/null || true
}

cmd_status() {
    printf 'Termux:X11 (%s):  %s\n' "${X11_DISPLAY}" "$([[ -S "${X11_SOCK}" ]] && echo "active (${X11_SOCK})" || echo "stopped")"
    if command -v pulseaudio >/dev/null 2>&1 && pulseaudio --check 2>/dev/null; then
        printf 'PulseAudio TCP:   active (127.0.0.1:%s)\n' "${PULSE_PORT}"
    else
        printf 'PulseAudio TCP:   stopped\n'
    fi
    run_container status || true
}

usage() {
    cat <<EOF
Usage: $(basename "$0") [start|stop|status]

Starts Termux:X11 (:0) + PulseAudio TCP (127.0.0.1:4713), ensures the NixOS
container is running, and launches fullscreen Weston + Hyprland as '${RAZR_USER}'.
EOF
}

main() {
    local cmd="${1:-start}"
    case "${cmd}" in
        start)  cmd_start ;;
        stop)   cmd_stop ;;
        status) cmd_status ;;
        -h|--help) usage ;;
        *)
            usage
            die "Unknown subcommand: ${cmd}"
            ;;
    esac
}

main "$@"
