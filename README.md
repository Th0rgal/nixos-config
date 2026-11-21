# `Th0rgal/nixos-config`

Personal NixOS & Home Manager configurations for Thomas's MacBook Air M2 (`m2air`, `m2air-aurora`, `m2air-stock`) and turnkey desktop VMs (`vm`, `vm-x86_64`), built on top of [`Th0rgal/omarchnix`](https://github.com/Th0rgal/omarchnix) and [`Th0rgal/wallpapers`](https://github.com/Th0rgal/wallpapers).

## Architecture

| Repository | Purpose | What Lives There |
| :--- | :--- | :--- |
| **[`Th0rgal/omarchnix`](https://github.com/Th0rgal/omarchnix)** | Reusable public distribution, installer & macOS-feel suite | `lib.mkHost` host constructor, `overlays/default.nix`, `nixosModules.omarchnix`, `homeManagerModules.omarchnix`, `home/macos.nix` (`mission-control`, `macos-dock`, `spotlight`, `touchid`, `workspace-wallpapers`, `workspaces`), `omarchy-shell`, 23+ themes, Apple Silicon Asahi/Aurora SEP modules, and CI gate tests. |
| **[`Th0rgal/nixos-config`](https://github.com/Th0rgal/nixos-config)** | Personal machine inventory, user identity & profiles | Pure data inventory (`inventory/{user,wallpapers}.nix`), host hardware/roles (`hosts/{common,m2air,vm}`), reusable Home Manager modules (`home/modules/{ssh-config,agent-shims,opencode,cloud-mounts}.nix`), and layered user profiles (`home/profiles/{core,desktop}.nix`). |
| **[`Th0rgal/wallpapers`](https://github.com/Th0rgal/wallpapers)** | Declarative WebP desktop & Space wallpapers | The 8 per-Space WebP wallpapers (`singapore.webp`, `bologna-dome.webp`, etc.) pinned in `flake.lock` so physical hosts and ephemeral VMs boot with identical Space backgrounds out of the box. |

## Hosts

- **`m2air`** (and alias **`m2air-aurora`**): MacBook Air M2 daily driver (`aarch64-linux`, Asahi Linux + removable ARM64 UEFI GRUB on `UUID=C650-1003` + LUKS2 `cryptroot` `UUID=aa0403dd-9917-49e1-85b1-46b21b39f2d9`), with `aurora-silicon/nixos-aurora` Secure Enclave, Touch ID (`fprintd`), FIDO2 (`aurora-fido`), and Orb Touch ID signer enabled.
- **`m2air-stock`**: Fallback MacBook Air M2 target using stock `nixos-apple-silicon` without Stage-2 Aurora SEP.
- **`vm`** / **`vm-x86_64`**: Turnkey QEMU desktop virtual machines (`aarch64-linux` / `x86_64-linux`) running the exact same Omarchy + macOS-feel desktop, `starship-subtle` theme, and Space `1..8` wallpapers from `Th0rgal/wallpapers`.

The DGX Spark runs Ubuntu and builds ARM packages remotely for the MacBook. Both VM targets use the shared desktop for screenshots. On an x86 Linux cloud worker with Nix and flakes enabled, build the QEMU launcher from the root of this checkout using the public CI inventory.

```sh
nix build --max-jobs 2 --cores 3 --no-write-lock-file --override-input inventory "path:$PWD/ci/inventory-stub" \
  '.#nixosConfigurations.vm-x86_64.config.system.build.vm'
./result/bin/run-omarchnix-vm-vm -display none -vga std -vnc 127.0.0.1:1
```

The launcher runs the desktop in a guest and exposes its screen on loopback port 5901. Agents editing a sibling Omarchnix checkout can add `--override-input omarchnix "path:$(realpath ../omarchnix)"` to the build command.

For a quick desktop check, build one generated file. For example, inspect the Hyprland screen-sharing settings as follows.

```sh
nix build --out-link result-xdph --no-write-lock-file \
  --override-input inventory "path:$PWD/ci/inventory-stub" \
  '.#nixosConfigurations.vm-x86_64.config.home-manager.users.thomas.xdg.configFile."hypr/xdph.conf".source'
cat result-xdph
```

## macOS-Style Shortcuts & Ergonomics (`Option` = `ALT` for WM & Spaces, `Cmd` = `SUPER` for Orb & Apps)

- **Close Window / Force-Kill:** `Cmd + Shift + Q` (`SUPER + SHIFT + Q`) or `Option + Shift + Q` (`ALT + SHIFT + Q`) to close the active window / `Ctrl + Option + Shift + Q` (`CTRL + ALT + SHIFT + Q`, force-kill).
- **Task & Performance Manager (Mission Center):** `Ctrl + Shift + Esc` or `Cmd + Option + Esc` — summons/toggles centered floating Mission Center on the active Space.
- **Universal Copy / Paste / Cut / Select All / Undo / Redo / Find:** `Cmd + C` / `Cmd + V` / `Cmd + X` / `Cmd + A` / `Cmd + Z` / `Cmd + Shift + Z` / `Cmd + F` (automatically translates to `Ctrl+Shift+C/V/F` in terminals so physical `Ctrl+C` still sends `SIGINT` and `Cmd+F` opens terminal search).
- **Window Management (`Option` = `ALT`, AeroSpace style):** Focus with `Option + H/J/K/L` (or `Option + Arrows`), move with `Option + Shift + H/J/K/L` (or `Option + Shift + Arrows`), fullscreen with `Option + F`, maximize with `Option + M`, toggle float with `Option + Shift + F` or `Option + V`, centered floating showcase with `Option + C`, scratchpad with `Option + S` or `` Option + ` `` — keeping `Cmd + <key>` 100% free for Orb & apps.
- **Mission Control & Spaces Overview:** `Option + Esc` or 3-finger swipe up to open; `Esc` / `Option + Esc` or 3-finger swipe down to close; 3-finger swipe left/right, `Option + 1..0`, or `Option + Tab` (previous Space) to switch workspaces (`Option + Shift + 1..0` / `Option + Ctrl + 1..0` to move windows).
- **Screenshot & Screen Recording:** `Ctrl + Shift + Cmd + 4` (`CTRL + SUPER + SHIFT + code:13`) for region screenshot to clipboard; `Ctrl + Shift + Cmd + 5` (`CTRL + SUPER + SHIFT + code:14`) for capture & screen-recording menu / stop recording.
- **Clipboard History (Maccy equivalent):** Click the clipboard icon (`󰅌`) in the top-right status bar, or press `Cmd + Shift + V` (`SUPER + SHIFT + V`) / `Cmd + Ctrl + V` (`SUPER + CTRL + V`).
- **Disable Auto-Sleep (Theine equivalent):** Click the coffee mug icon (``) in the top-right status bar, press `Option + Shift + I` / `Cmd + Shift + I`, or run `omarchy-toggle-idle toggle`.
- **Touchpad Scrolling:** Inverted (`natural_scroll = false`, so content goes up when fingers go up) and smoothed (`scroll_factor = 0.5`).
- **Keyboard Backlight (`m2air`):** `50%` default (`omarchnix.hardware.m2air.keyboardBacklight.defaultBrightness = 50;`), adjustable via `Option/Ctrl/Cmd + F1/F2`, status bar Display panel, or `omarchnix kbd-backlight 50%`.
- **Battery Charge Threshold (`m2air`):** Set to `90%` by default (`omarchnix.hardware.m2air.chargeControlEndThreshold = 90;`), adjustable live via `/sys/class/power_supply/macsmc-battery/charge_control_end_threshold`.

## Touch ID Activation (`m2air`)

1. Switch to the `m2air` flake configuration and stage the verified Aurora Stage 2 bootloader (`m1n1/boot.bin`, preserving `m1n1/boot.bin.pre-aurora`):
   ```bash
   rebuild
   sudo omarchnix-install-stage2 /boot
   sudo reboot
   ```
2. After rebooting into `linux-aurora`, enroll your fingerprint:
   ```bash
   omarchnix-touchid-status
   sudo fprintd-enroll thomas
   fprintd-verify
   ```
3. **Where Touch ID works automatically:**
   - **Lock screen (`omarchy-shell` & `hyprlock`)**, **`sudo`**, and **Polkit GUI prompts (`polkit-1`)**
   - **Bitwarden Desktop:** Settings → Security → *Unlock with biometrics* (uses `polkit-1` + `gnome-keyring`) and WebAuthn/FIDO2 passkeys via `aurora-fido`
   - **Orb Desktop:** Native `orb-signer` uses `fprintd-verify` for Touch ID approval & biometric authentication

## Common Commands

```bash
# Switch configuration (uses ../omarchnix when checked out; --locked for flake.lock only):
rebuild

# Build & launch a turnkey QEMU desktop VM with full desktop + wallpapers:
nixos-rebuild build-vm --flake ~/nixos-config#vm
./result/bin/run-omarchnix-vm-vm

# Pull latest omarchnix or wallpapers upstream changes:
cd ~/nixos-config && nix flake update omarchnix wallpapers

# Backups (docs/backup.md) and secrets bootstrap:
backup status
sudo bootstrap-secrets
```

The fleet SSH map, lean-mesh peers and remote builder live in the private
[`nixos-inventory`](https://github.com/Th0rgal/nixos-inventory) repository (the `inventory`
flake input); CI and forks evaluate against [`ci/inventory-stub`](ci/inventory-stub/default.nix).
