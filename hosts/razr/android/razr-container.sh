#!/usr/bin/env bash
# razr-container.sh — Android-side NixOS systemd container manager for Motorola Razr 60 Ultra.
#
# Runs as Android root (su). Supports two backends:
#   - droidspaces : ravindu644/Droidspaces-OSS static aarch64 binary (preferred when installed)
#   - unshare     : self-contained fallback using unshare(1) + nsenter(1) + cgroup v2
#
# Both backends loop-mount the 64 GiB sparse ext4 image at /data/local/nixos/mnt with
# rw,exec,dev,suid,noatime, prepare the Termux:X11 / GPU / Android storage bind mounts,
# and boot /sbin/init (systemd PID 1) in PID+IPC+UTS+MNT+CGROUP namespaces while
# sharing Android's host NET namespace.
#
# Usage:
#   razr-container.sh start
#   razr-container.sh stop
#   razr-container.sh status
#   razr-container.sh shell [user] [-- <command...>]
#
# Environment overrides:
#   RAZR_BACKEND=auto|droidspaces|unshare   (default: auto)
#   RAZR_DIR=/data/local/nixos              (default: /data/local/nixos)
#   RAZR_IMG=/data/local/nixos/rootfs.img   (default: $RAZR_DIR/rootfs.img)
#   RAZR_MNT=/data/local/nixos/mnt          (default: $RAZR_DIR/mnt)
#   RAZR_NAME=razr                          (default: razr)
#   RAZR_HOSTNAME=razr                      (default: razr)
#   RAZR_INIT=/sbin/init                    (default: /sbin/init)
#   DROIDSPACES_BIN=<path>                  (auto-detected if unset)

set -euo pipefail

# Ensure Termux utilities (bash, unshare, nsenter, mountpoint, etc.) are reachable under su.
export PATH="/data/data/com.termux/files/usr/bin:/data/local/Droidspaces/bin:/data/local/nixos/bin:/system/bin:/vendor/bin:/sbin:${PATH:-/usr/bin:/bin}"

RAZR_DIR="${RAZR_DIR:-/data/local/nixos}"
RAZR_ROOTFS_DIR="${RAZR_ROOTFS_DIR:-${RAZR_DIR}/rootfs}"
RAZR_IMG="${RAZR_IMG:-${RAZR_DIR}/rootfs.img}"
RAZR_MNT="${RAZR_MNT:-${RAZR_DIR}/mnt}"
RAZR_NAME="${RAZR_NAME:-razr}"
RAZR_HOSTNAME="${RAZR_HOSTNAME:-razr}"
RAZR_INIT="${RAZR_INIT:-/sbin/init}"
RAZR_PIDFILE="${RAZR_PIDFILE:-${RAZR_DIR}/${RAZR_NAME}.pid}"
RAZR_LOG="${RAZR_LOG:-${RAZR_DIR}/${RAZR_NAME}-container.log}"
RAZR_BACKEND="${RAZR_BACKEND:-auto}"

TERMUX_X11_DIR="${TERMUX_X11_DIR:-/data/data/com.termux/files/usr/tmp/.X11-unix}"
ANDROID_STORAGE="${ANDROID_STORAGE:-/storage/emulated/0}"

log()  { printf '[razr-container] %s\n' "$*" >&2; }
die()  { printf '[razr-container] ERROR: %s\n' "$*" >&2; exit 1; }

require_root() {
    if [[ "$(id -u)" -ne 0 ]]; then
        if command -v su >/dev/null 2>&1; then
            local self
            self="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
            exec su -c "PATH='${PATH}' RAZR_BACKEND='${RAZR_BACKEND}' RAZR_DIR='${RAZR_DIR}' RAZR_IMG='${RAZR_IMG}' RAZR_MNT='${RAZR_MNT}' bash '${self}' $(printf '%q ' "$@")"
        fi
        die "This script must run as Android root (su)."
    fi
}

find_droidspaces() {
    if [[ -n "${DROIDSPACES_BIN:-}" && -x "${DROIDSPACES_BIN}" ]]; then
        printf '%s\n' "${DROIDSPACES_BIN}"
        return 0
    fi
    local candidate
    for candidate in \
        "$(command -v droidspaces 2>/dev/null || true)" \
        "/data/local/Droidspaces/bin/droidspaces" \
        "${RAZR_DIR}/bin/droidspaces"; do
        if [[ -n "${candidate}" && -x "${candidate}" ]]; then
            printf '%s\n' "${candidate}"
            return 0
        fi
    done
    return 1
}

resolve_backend() {
    case "${RAZR_BACKEND}" in
        droidspaces)
            DROIDSPACES_BIN="$(find_droidspaces)" || die "RAZR_BACKEND=droidspaces requested, but droidspaces binary was not found."
            printf 'droidspaces\n'
            ;;
        unshare)
            printf 'unshare\n'
            ;;
        auto)
            if DROIDSPACES_BIN="$(find_droidspaces)"; then
                printf 'droidspaces\n'
            else
                printf 'unshare\n'
            fi
            ;;
        *)
            die "Unknown RAZR_BACKEND='${RAZR_BACKEND}' (expected auto, droidspaces, or unshare)."
            ;;
    esac
}

is_mounted() {
    local target="$1"
    if command -v mountpoint >/dev/null 2>&1; then
        mountpoint -q "${target}" 2>/dev/null
    else
        awk -v mnt="${target}" '$2 == mnt { found=1 } END { exit(found ? 0 : 1) }' /proc/mounts
    fi
}

ensure_rootfs_mounted() {
    mkdir -p "${RAZR_DIR}" "${RAZR_MNT}"

    if [[ -d "${RAZR_ROOTFS_DIR}" ]]; then
        if is_mounted "${RAZR_MNT}"; then
            mount -o remount,bind,rw,exec,dev,suid "${RAZR_MNT}" 2>/dev/null || true
        else
            log "Bind-mounting native F2FS rootfs ${RAZR_ROOTFS_DIR} -> ${RAZR_MNT} (rw,exec,dev,suid)"
            mount --bind "${RAZR_ROOTFS_DIR}" "${RAZR_MNT}"
            mount -o remount,bind,rw,exec,dev,suid "${RAZR_MNT}"
        fi
    elif [[ -f "${RAZR_IMG}" || -b "${RAZR_IMG}" ]]; then
        if is_mounted "${RAZR_MNT}"; then
            mount -o remount,rw,exec,dev,suid,noatime "${RAZR_MNT}" 2>/dev/null || true
        else
            log "Loop-mounting ${RAZR_IMG} -> ${RAZR_MNT} (rw,exec,dev,suid,noatime)"
            mount -t ext4 -o loop,rw,exec,dev,suid,noatime "${RAZR_IMG}" "${RAZR_MNT}"
        fi
    else
        die "Neither rootfs directory (${RAZR_ROOTFS_DIR}) nor image (${RAZR_IMG}) found."
    fi

    if [[ ! -e "${RAZR_MNT}${RAZR_INIT}" && ! -L "${RAZR_MNT}${RAZR_INIT}" ]]; then
        die "Init binary ${RAZR_INIT} not found inside ${RAZR_MNT}. Has the NixOS rootfs been extracted?"
    fi
}

unmount_rootfs() {
    if is_mounted "${RAZR_MNT}"; then
        log "Unmounting ${RAZR_MNT}"
        umount -R "${RAZR_MNT}" 2>/dev/null || umount -l "${RAZR_MNT}" 2>/dev/null || true
    fi
}

prepare_host_bind_sources() {
    mkdir -p "${TERMUX_X11_DIR}"
    chmod 1777 "${TERMUX_X11_DIR}" 2>/dev/null || true

    mkdir -p \
        "${RAZR_MNT}/tmp/.X11-unix" \
        "${RAZR_MNT}/mnt/android" \
        "${RAZR_MNT}/dev" \
        "${RAZR_MNT}/proc" \
        "${RAZR_MNT}/sys" \
        "${RAZR_MNT}/run"
}

# ---------------------------------------------------------------------------
# Backend 1: ravindu644/Droidspaces-OSS
# ---------------------------------------------------------------------------
ds_pid() {
    local pid
    pid="$("${DROIDSPACES_BIN}" pid --name="${RAZR_NAME}" 2>/dev/null || true)"
    if [[ "${pid}" =~ ^[0-9]+$ ]] && [[ -d "/proc/${pid}" ]]; then
        printf '%s\n' "${pid}"
        return 0
    fi
    return 1
}

ds_start() {
    if ds_pid >/dev/null 2>&1; then
        log "Container '${RAZR_NAME}' is already running (droidspaces, PID $(ds_pid))."
        return 0
    fi

    ensure_rootfs_mounted
    prepare_host_bind_sources

    local -a bind_args=(
        "--bind=${TERMUX_X11_DIR}:/tmp/.X11-unix"
    )
    if [[ -d "${ANDROID_STORAGE}" ]]; then
        bind_args+=("--bind=${ANDROID_STORAGE}:/mnt/android")
    fi
    if [[ -d /dev/dri ]]; then
        bind_args+=("--bind=/dev/dri:/dev/dri")
    fi
    if [[ -e /dev/kgsl-3d0 ]]; then
        bind_args+=("--bind=/dev/kgsl-3d0:/dev/kgsl-3d0")
    fi
    if [[ -d /vendor/dsp ]]; then
        mkdir -p "${RAZR_MNT}/run/host-vendor-dsp"
        bind_args+=("--bind=/vendor/dsp:/run/host-vendor-dsp")
    fi
    if [[ -d /vendor/lib64 ]]; then
        mkdir -p "${RAZR_MNT}/run/host-vendor-lib64"
        bind_args+=("--bind=/vendor/lib64:/run/host-vendor-lib64")
    fi
    if [[ -c /dev/fastrpc-cdsp ]]; then
        bind_args+=("--bind=/dev/fastrpc-cdsp:/dev/fastrpc-cdsp")
    fi
    if [[ -c /dev/fastrpc-cdsp-secure ]]; then
        bind_args+=("--bind=/dev/fastrpc-cdsp-secure:/dev/fastrpc-cdsp-secure")
    fi

    log "Booting '${RAZR_NAME}' via droidspaces (${DROIDSPACES_BIN}) with --net=host --gpu"
    "${DROIDSPACES_BIN}" start \
        --name="${RAZR_NAME}" \
        --hostname="${RAZR_HOSTNAME}" \
        --rootfs="${RAZR_MNT}" \
        --net=host \
        --gpu \
        --init="${RAZR_INIT}" \
        "${bind_args[@]}"

    local pid
    pid="$(ds_pid || true)"
    log "Container '${RAZR_NAME}' started (PID ${pid:-unknown})."
}

ds_stop() {
    if ds_pid >/dev/null 2>&1; then
        log "Stopping '${RAZR_NAME}' via droidspaces..."
        "${DROIDSPACES_BIN}" stop --name="${RAZR_NAME}"
    else
        log "Container '${RAZR_NAME}' is not running."
    fi
    unmount_rootfs
}

ds_status() {
    printf 'Backend:   droidspaces (%s)\n' "${DROIDSPACES_BIN}"
    printf 'Rootfs:    %s -> %s (%s)\n' "${RAZR_IMG}" "${RAZR_MNT}" "$(is_mounted "${RAZR_MNT}" && echo mounted || echo unmounted)"
    if ds_pid >/dev/null 2>&1; then
        printf 'State:     running (PID %s)\n' "$(ds_pid)"
        "${DROIDSPACES_BIN}" info --name="${RAZR_NAME}" 2>/dev/null || true
    else
        printf 'State:     stopped\n'
        return 3
    fi
}

ds_shell() {
    ds_pid >/dev/null 2>&1 || die "Container '${RAZR_NAME}' is not running. Start it with: $0 start"

    local user="root"
    if [[ $# -gt 0 && "$1" != "--" ]]; then
        user="$1"
        shift
    fi
    if [[ $# -gt 0 && "$1" == "--" ]]; then
        shift
    fi

    if [[ $# -gt 0 ]]; then
        if [[ "${user}" == "root" ]]; then
            exec "${DROIDSPACES_BIN}" --name="${RAZR_NAME}" run "$@"
        else
            exec "${DROIDSPACES_BIN}" --name="${RAZR_NAME}" run /run/current-system/sw/bin/su - "${user}" -c "$(printf '%q ' "$@")"
        fi
    else
        exec "${DROIDSPACES_BIN}" --name="${RAZR_NAME}" enter "${user}"
    fi
}

# ---------------------------------------------------------------------------
# Backend 2: Self-contained unshare(1) + nsenter(1) fallback
# ---------------------------------------------------------------------------
unshare_pid() {
    if [[ -f "${RAZR_PIDFILE}" ]]; then
        local pid
        pid="$(cat "${RAZR_PIDFILE}" 2>/dev/null || true)"
        if [[ "${pid}" =~ ^[0-9]+$ ]] && [[ -d "/proc/${pid}" ]]; then
            printf '%s\n' "${pid}"
            return 0
        fi
    fi
    return 1
}

unshare_start() {
    if unshare_pid >/dev/null 2>&1; then
        log "Container '${RAZR_NAME}' is already running (unshare, PID $(unshare_pid))."
        return 0
    fi

    command -v unshare >/dev/null 2>&1 || die "unshare(1) not found in PATH."
    command -v nsenter >/dev/null 2>&1 || die "nsenter(1) not found in PATH."

    ensure_rootfs_mounted
    prepare_host_bind_sources

    # Create a dedicated cgroup v2 leaf for the container when Android mounts cgroup2.
    local cg2_root=""
    cg2_root="$(awk '$3 == "cgroup2" { print $2; exit }' /proc/mounts || true)"
    if [[ -n "${cg2_root}" && -d "${cg2_root}" ]]; then
        mkdir -p "${cg2_root}/razr-nixos" 2>/dev/null || true
    fi

    local boot_script="${RAZR_DIR}/.unshare-init.sh"
    cat > "${boot_script}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

RAZR_MNT="$1"
RAZR_HOSTNAME="$2"
RAZR_INIT="$3"
TERMUX_X11_DIR="$4"
ANDROID_STORAGE="$5"

# Isolate mount propagation from Android's host mount namespace.
mount --make-rprivate /

# Ensure hostname is set in the new UTS namespace.
hostname "${RAZR_HOSTNAME}" 2>/dev/null || printf '%s\n' "${RAZR_HOSTNAME}" > /proc/sys/kernel/hostname

# Mount virtual filesystems inside the container rootfs.
mount -t proc -o nosuid,noexec,nodev proc "${RAZR_MNT}/proc"
mount -t sysfs -o nosuid,noexec,nodev sysfs "${RAZR_MNT}/sys"
mount -t tmpfs -o mode=755,nosuid,nodev tmpfs "${RAZR_MNT}/run"
mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "${RAZR_MNT}/tmp"

# Device nodes: devtmpfs if supported, otherwise minimal tmpfs + core nodes.
if ! mount -t devtmpfs -o mode=755,nosuid devtmpfs "${RAZR_MNT}/dev" 2>/dev/null; then
    mount -t tmpfs -o mode=755,nosuid tmpfs "${RAZR_MNT}/dev"
    for node in null zero full random urandom tty ptmx; do
        if [[ -e "/dev/${node}" ]]; then
            touch "${RAZR_MNT}/dev/${node}"
            mount --bind "/dev/${node}" "${RAZR_MNT}/dev/${node}"
        fi
    done
    ln -snf /proc/self/fd "${RAZR_MNT}/dev/fd"
    ln -snf /proc/self/fd/0 "${RAZR_MNT}/dev/stdin"
    ln -snf /proc/self/fd/1 "${RAZR_MNT}/dev/stdout"
    ln -snf /proc/self/fd/2 "${RAZR_MNT}/dev/stderr"
fi

mkdir -p "${RAZR_MNT}/dev/pts" "${RAZR_MNT}/dev/shm"
mount -t devpts -o gid=5,mode=620,ptmxmode=666,newinstance devpts "${RAZR_MNT}/dev/pts" 2>/dev/null || \
    mount -t devpts -o mode=620,ptmxmode=666 devpts "${RAZR_MNT}/dev/pts"
mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "${RAZR_MNT}/dev/shm"

# Cgroup v2 inside the container (Android 15 hybrid cgroup v1/v2 compatible).
mkdir -p "${RAZR_MNT}/sys/fs/cgroup"
if ! mount -t cgroup2 -o nsdelegate,memory_recursiveprot cgroup2 "${RAZR_MNT}/sys/fs/cgroup" 2>/dev/null; then
    mount -t cgroup2 cgroup2 "${RAZR_MNT}/sys/fs/cgroup" 2>/dev/null || true
fi

# Required bind mounts: Termux:X11, GPU (/dev/dri, /dev/kgsl-3d0), Android storage.
mkdir -p "${RAZR_MNT}/tmp/.X11-unix"
mount --bind "${TERMUX_X11_DIR}" "${RAZR_MNT}/tmp/.X11-unix"

if [[ -d /dev/dri ]]; then
    mkdir -p "${RAZR_MNT}/dev/dri"
    mount --bind /dev/dri "${RAZR_MNT}/dev/dri"
fi

if [[ -e /dev/kgsl-3d0 ]]; then
    touch "${RAZR_MNT}/dev/kgsl-3d0"
    mount --bind /dev/kgsl-3d0 "${RAZR_MNT}/dev/kgsl-3d0"
fi

if [[ -d "${ANDROID_STORAGE}" ]]; then
    mkdir -p "${RAZR_MNT}/mnt/android"
    mount --rbind "${ANDROID_STORAGE}" "${RAZR_MNT}/mnt/android"
    mount --make-rslave "${RAZR_MNT}/mnt/android" 2>/dev/null || true
fi

export container=lxc
export TERM=xterm-256color
export PATH="/run/current-system/sw/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

# Hand off to systemd PID 1 inside the NixOS rootfs.
exec chroot "${RAZR_MNT}" "${RAZR_INIT}"
EOF
    chmod 700 "${boot_script}"

    log "Booting '${RAZR_NAME}' via unshare (PID+IPC+UTS+MNT+CGROUP, shared host NET)"
    : > "${RAZR_LOG}"

    # Launch unshare without --net so Android's host network namespace is shared directly.
    nohup unshare --pid --ipc --uts --mount --cgroup --fork \
        bash "${boot_script}" \
            "${RAZR_MNT}" \
            "${RAZR_HOSTNAME}" \
            "${RAZR_INIT}" \
            "${TERMUX_X11_DIR}" \
            "${ANDROID_STORAGE}" \
        >>"${RAZR_LOG}" 2>&1 </dev/null &
    local wrapper_pid=$!

    # Wait briefly for the forked PID 1 child to appear and record its host PID.
    local init_pid=""
    local i
    for i in $(seq 1 30); do
        init_pid="$(awk -v ppid="${wrapper_pid}" '$4 == ppid { print $1; exit }' /proc/[0-9]*/stat 2>/dev/null || true)"
        if [[ -n "${init_pid}" && -d "/proc/${init_pid}" ]]; then
            break
        fi
        sleep 0.1
    done

    if [[ -z "${init_pid}" ]]; then
        die "Container failed to boot; check ${RAZR_LOG}"
    fi

    printf '%s\n' "${init_pid}" > "${RAZR_PIDFILE}"
    log "Container '${RAZR_NAME}' started (host PID ${init_pid}; log: ${RAZR_LOG})."
}

unshare_stop() {
    local pid
    if pid="$(unshare_pid)"; then
        log "Sending SIGRTMIN+3 (systemd poweroff) to PID ${pid}..."
        kill -s RTMIN+3 "${pid}" 2>/dev/null || kill -TERM "${pid}" 2>/dev/null || true

        local i
        for i in $(seq 1 15); do
            if [[ ! -d "/proc/${pid}" ]]; then
                break
            fi
            sleep 1
        done

        if [[ -d "/proc/${pid}" ]]; then
            log "Container still running; sending SIGKILL to PID ${pid}..."
            kill -KILL "${pid}" 2>/dev/null || true
            sleep 0.5
        fi
    else
        log "Container '${RAZR_NAME}' is not running."
    fi

    rm -f "${RAZR_PIDFILE}"
    unmount_rootfs
}

unshare_status() {
    printf 'Backend:   unshare (self-contained fallback)\n'
    printf 'Rootfs:    %s -> %s (%s)\n' "${RAZR_IMG}" "${RAZR_MNT}" "$(is_mounted "${RAZR_MNT}" && echo mounted || echo unmounted)"
    local pid
    if pid="$(unshare_pid)"; then
        printf 'State:     running (host PID %s)\n' "${pid}"
        local sys_state
        sys_state="$(nsenter --target "${pid}" --pid --ipc --uts --mount --cgroup -- \
            chroot "${RAZR_MNT}" /run/current-system/sw/bin/systemctl is-system-running 2>/dev/null || echo unknown)"
        printf 'Systemd:   %s\n' "${sys_state}"
    else
        printf 'State:     stopped\n'
        return 3
    fi
}

unshare_shell() {
    local pid
    pid="$(unshare_pid)" || die "Container '${RAZR_NAME}' is not running. Start it with: $0 start"

    local user="root"
    if [[ $# -gt 0 && "$1" != "--" ]]; then
        user="$1"
        shift
    fi
    if [[ $# -gt 0 && "$1" == "--" ]]; then
        shift
    fi

    local -a ns_cmd=(
        nsenter
        --target "${pid}"
        --pid --ipc --uts --mount --cgroup
        --
        env -i
        container=lxc
        TERM="${TERM:-xterm-256color}"
        PATH="/run/current-system/sw/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        chroot "${RAZR_MNT}"
    )

    if [[ $# -gt 0 ]]; then
        if [[ "${user}" == "root" ]]; then
            exec "${ns_cmd[@]}" "$@"
        else
            local cmd_str
            cmd_str="$(printf '%q ' "$@")"
            exec "${ns_cmd[@]}" /run/current-system/sw/bin/su - "${user}" -c "${cmd_str}"
        fi
    else
        if [[ "${user}" == "root" ]]; then
            exec "${ns_cmd[@]}" /run/current-system/sw/bin/bash -l
        else
            exec "${ns_cmd[@]}" /run/current-system/sw/bin/su - "${user}"
        fi
    fi
}

usage() {
    cat <<EOF
Usage: $(basename "$0") <start|stop|status|shell> [args...]

Subcommands:
  start                    Loop-mount rootfs.img and boot NixOS systemd PID 1
  stop                     Cleanly power off the container and unmount rootfs.img
  status                   Show container backend, mount state, and PID/systemd status
  shell [user] [-- cmd...] Enter container shell (default: root) or run a command
EOF
}

main() {
    local cmd="${1:-}"
    if [[ -z "${cmd}" || "${cmd}" == "-h" || "${cmd}" == "--help" ]]; then
        usage
        exit 0
    fi

    require_root "$@"
    shift

    local backend
    backend="$(resolve_backend)"

    case "${cmd}" in
        start)
            if [[ "${backend}" == "droidspaces" ]]; then ds_start; else unshare_start; fi
            ;;
        stop)
            if [[ "${backend}" == "droidspaces" ]]; then ds_stop; else unshare_stop; fi
            ;;
        status)
            if [[ "${backend}" == "droidspaces" ]]; then ds_status; else unshare_status; fi
            ;;
        shell|exec)
            if [[ "${backend}" == "droidspaces" ]]; then ds_shell "$@"; else unshare_shell "$@"; fi
            ;;
        *)
            usage
            die "Unknown subcommand: ${cmd}"
            ;;
    esac
}

main "$@"
