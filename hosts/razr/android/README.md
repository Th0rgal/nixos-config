# Motorola Razr 60 Ultra (`razr`) — Android Container & Desktop Bridge

Android-side scripts for booting a native `aarch64` NixOS `systemd` container on a rooted **Motorola Razr 60 Ultra** (Android 15) and bridging a fullscreen nested **Hyprland** Wayland desktop to **Motorola Smart Connect (Miracast)** over **Termux:X11** with zero video re-encoding.

## Architecture

```text
Smart Connect Miracast (1080p / 2K @ 60Hz)
  ▲
  │  Android fullscreen window
Termux:X11 (:0, SHM socket at /data/data/com.termux/files/usr/tmp/.X11-unix/X0)
PulseAudio (TCP 127.0.0.1:4713, auth-ip-acl=127.0.0.1 auth-anonymous=1)
  ▲
  │  Bind-mounted /tmp/.X11-unix + shared host NET namespace
NixOS systemd container (/data/local/nixos/rootfs.img -> /data/local/nixos/mnt)
  ├── PID + IPC + UTS + MNT + CGROUP namespaces (Droidspaces-OSS or unshare fallback)
  ├── GPU nodes: /dev/dri, /dev/kgsl-3d0 (Adreno Turnip + Zink or llvmpipe)
  ├── Android storage: /sdcard -> /mnt/android
  └── User `thomas`:
        weston --backend=x11-backend.so --fullscreen --socket=wayland-nested
          └── Hyprland (WAYLAND_DISPLAY=wayland-nested, PULSE_SERVER=tcp:127.0.0.1:4713)
```

## Files

| File | Runs On | Purpose |
| :--- | :--- | :--- |
| `razr-container.sh` | Android root (`su`) | Loop-mounts `/data/local/nixos/rootfs.img` (`rw,exec,dev,suid,noatime` at `/data/local/nixos/mnt`), prepares bind mounts, and manages the NixOS `systemd` PID 1 container (`start \| stop \| status \| shell`). Auto-selects `droidspaces` or the built-in `unshare` fallback. |
| `razr-desktop.sh` | Termux (uses `su`) | Starts Termux `pulseaudio` (TCP `127.0.0.1:4713`) and `termux-x11 :0`, ensures the NixOS container is running, and launches fullscreen borderless `weston` (`wayland-nested`) + `Hyprland` as `thomas`. |
| `razr-gpu-mode` | NixOS (`thomas`) | Toggles between `hw` (Mesa Freedreno Turnip + Zink: `MESA_LOADER_DRIVER_OVERRIDE=zink`, `TU_DEBUG=noconform`) and `sw` (`LIBGL_ALWAYS_SOFTWARE=1` + Hyprland blur disabled), persisted in `~/.config/razr-gpu-mode`. |

---

## One-Time Android Setup

### 1. Root & Kernel Prerequisites
- Rooted Android 15 (Magisk, KernelSU, or APatch) with `su` accessible from Termux.
- Verify cgroup v2 and namespace support (optional once `droidspaces` is installed):
  ```bash
  su -c '/data/local/nixos/bin/droidspaces check'
  ```

### 2. Install Termux & Termux:X11 (Same Signing Key)
Install **both** apps from the **same source** (GitHub Releases **or** F-Droid — do not mix, and do not use the deprecated Play Store build):
- **Termux:** <https://github.com/termux/termux-app/releases>
- **Termux:X11:** <https://github.com/termux/termux-x11/releases>

Inside Termux, install the required packages:
```bash
pkg update && pkg upgrade -y
pkg install -y x11-repo
pkg install -y termux-x11-nightly pulseaudio tsu util-linux procps coreutils curl tar
```

### 3. Disable Android's Phantom Process Killer
Android 12–15 kills background child processes spawned by Termux/containers once they exceed 32 processes unless `max_phantom_processes` is raised.

From a PC via ADB (or directly on-device inside `su`):
```bash
adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
adb shell "settings put global settings_enable_monitor_phantom_procs false"
```

Equivalent on-device (root shell in Termux):
```bash
su -c "/system/bin/device_config set_sync_disabled_for_tests persistent"
su -c "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
su -c "settings put global settings_enable_monitor_phantom_procs false"
```

Verify the setting took effect:
```bash
su -c "/system/bin/device_config get activity_manager max_phantom_processes"
# Expected: 2147483647
```

Also set **Battery usage → Unrestricted** for **Termux** and **Termux:X11** in Android Settings so Doze does not throttle the desktop session.

### 4. Install `droidspaces` (Recommended) or Use the Built-In `unshare` Fallback
`razr-container.sh` automatically uses [`ravindu644/Droidspaces-OSS`](https://github.com/ravindu644/Droidspaces-OSS) when available and falls back to its built-in self-contained `unshare --pid --ipc --uts --mount --cgroup --fork` launcher otherwise.

To install the static `aarch64` `droidspaces` binary (latest tested release: `v7.2.0`):
```bash
su -c '
  mkdir -p /data/local/nixos/bin /tmp/ds-dl
  curl -fsSL https://github.com/ravindu644/Droidspaces-OSS/releases/download/v7.2.0/droidspaces-v7.2.0-cdadb9eb.tar.gz \
    -o /tmp/ds-dl/droidspaces.tar.gz
  tar -xzf /tmp/ds-dl/droidspaces.tar.gz -C /tmp/ds-dl
  cp /tmp/ds-dl/droidspaces-*/aarch64/droidspaces /data/local/nixos/bin/droidspaces
  chmod 755 /data/local/nixos/bin/droidspaces
  rm -rf /tmp/ds-dl
'
```
*(Alternatively, install the official `Droidspaces-universal-*.apk`, which places the binary at `/data/local/Droidspaces/bin/droidspaces` — also auto-detected by `razr-container.sh`.)*

To force a specific backend at runtime:
```bash
RAZR_BACKEND=droidspaces ./razr-container.sh start
RAZR_BACKEND=unshare     ./razr-container.sh start
```

### 5. Create the 64 GiB Sparse ext4 Rootfs Image
Android's `/data` partition (`f2fs`) does not support all POSIX/NixOS semantics reliably; storing NixOS in a 64 GiB sparse `ext4` image avoids `noexec`/`nosuid`/`f2fs` issues while only consuming disk space as files are written:

```bash
su -c '
  mkdir -p /data/local/nixos/mnt
  truncate -s 64G /data/local/nixos/rootfs.img
  mkfs.ext4 -F -L nixos-razr /data/local/nixos/rootfs.img
  mount -t ext4 -o loop,rw,exec,dev,suid,noatime /data/local/nixos/rootfs.img /data/local/nixos/mnt
  # Unpack your built NixOS razr rootfs tarball into /data/local/nixos/mnt:
  # tar --numeric-owner --xattrs -xpf /sdcard/Download/nixos-razr-rootfs.tar.zst -C /data/local/nixos/mnt
  umount /data/local/nixos/mnt
'
```

### 6. Copy Scripts to Termux
Place the scripts in Termux (for example `~/razr/` or `~/.local/bin/`):
```bash
chmod +x razr-container.sh razr-desktop.sh razr-gpu-mode
```

### 7. Smart Connect Miracast (1080p / 2K @ 60Hz)
1. Open **Motorola Smart Connect** → **Mobile Desktop** (or **Miracast / Wireless Display**) and connect to your 1080p or 2K @ 60Hz display.
2. Open **Termux:X11** on the external Smart Connect display and configure its **Preferences**:
   - **Display resolution mode:** `native` (matches the 1080p / 2K Smart Connect output 1:1)
   - **Fullscreen on device display:** `On`
   - **Hide display cutout:** `On`
   - **Show additional keyboard:** `Off`
   - **Capture external keyboard / mouse pointer:** `On`
   - **Prefer scancodes when possible:** `On` (preserves `Option`/`Cmd` shortcuts for Hyprland)
3. Start the desktop from Termux:
   ```bash
   ./razr-desktop.sh
   ```

---

## Daily Usage

```bash
# Container lifecycle (runs as root via su)
./razr-container.sh start
./razr-container.sh status
./razr-container.sh shell              # root shell inside NixOS
./razr-container.sh shell thomas       # thomas shell inside NixOS
./razr-container.sh stop

# Desktop session (runs from Termux)
./razr-desktop.sh start                # Termux:X11 + PulseAudio + Weston + Hyprland
./razr-desktop.sh status
./razr-desktop.sh stop

# GPU mode toggle (inside NixOS as thomas)
razr-gpu-mode status
razr-gpu-mode hw                       # Adreno Turnip + Zink (default)
razr-gpu-mode sw                       # llvmpipe software fallback + blur off
razr-gpu-mode toggle
```

---

## Troubleshooting

- **Black screen or Vulkan crash on Adreno:** Switch to software mode (`razr-gpu-mode sw`) and restart `./razr-desktop.sh`. Verify `/dev/kgsl-3d0` is visible inside the container and user `thomas` has read/write access (e.g., `video` / `render` / `droidspaces-gpu` group).
- **No audio in Hyprland:** Confirm Termux PulseAudio is listening on `127.0.0.1:4713` (`pactl info` in Termux) and that `PULSE_SERVER=tcp:127.0.0.1:4713` is set inside the container. Because the container shares Android's host `NET` namespace, `127.0.0.1` maps directly between Termux and NixOS.
- **Android 15 hybrid cgroup v1/v2 issues:** If `systemd` fails to boot with the `unshare` fallback, install the `droidspaces` binary (which handles Android 15 hybrid cgroup delegation and SELinux contexts automatically) or inspect `/data/local/nixos/razr-container.log`.
