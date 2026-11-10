# `Th0rgal/nixos-config`

Personal NixOS & Home Manager configurations for Thomas's machines (`m2air`, `m2air-aurora`, `m2air-stock`, `spark`), built on top of the reusable [`Th0rgal/omarchnix`](https://github.com/Th0rgal/omarchnix) flake.

## Architecture: `omarchnix` vs `nixos-config`

| Repository | Purpose | What Lives There |
| :--- | :--- | :--- |
| **[`Th0rgal/omarchnix`](https://github.com/Th0rgal/omarchnix)** | Reusable public distribution & installer | `packages.installer-bootstrap` ISO, `nixosModules.omarchnix`, `homeManagerModules.omarchnix`, `overlays.default`, `lib.omarchy`, `omarchy-shell`, 23+ themes, `plugins.omarchy.org` builder, Apple Silicon Asahi/Aurora SEP hardware modules, and CI gate tests. |
| **[`Th0rgal/nixos-config`](https://github.com/Th0rgal/nixos-config)** | Personal machine & user configuration | Host definitions (`hosts/m2air`, `hosts/spark`), machine-specific partition UUIDs (`hosts/m2air/hardware.nix`), personal packages (`brave`, `bitwarden-desktop`, `telegram-desktop`, `orb-desktop`, `notion-calendar`, `antigravity-hub`), SSH public keys, and personal Home Manager overrides (`home/thomas.nix`). |

## Hosts

- **`m2air`** (and alias **`m2air-aurora`**): MacBook Air M2 daily driver (`aarch64-linux`, Asahi Linux + removable ARM64 UEFI GRUB on `UUID=C650-1003` + LUKS2 `cryptroot` `UUID=aa0403dd-9917-49e1-85b1-46b21b39f2d9`), with `aurora-silicon/nixos-aurora` Secure Enclave, Touch ID (`fprintd`), FIDO2 (`aurora-fido`), and Orb Touch ID signer enabled.
- **`m2air-stock`**: Fallback MacBook Air M2 target using stock `nixos-apple-silicon` without Stage-2 Aurora SEP.
- **`spark`**: NVIDIA Grace / DGX Spark ARM64 workstation & server (`aarch64-linux`).

## macOS-Style Shortcuts & Ergonomics (`m2air`)

- **Close / Kill Window:** `Cmd + Q` (`SUPER + Q`) or `Cmd + W` (`SUPER + W`), force-kill with `Cmd + Shift + Q` (`SUPER + SHIFT + Q`).
- **Universal Copy / Paste / Cut / Select All / Undo / Redo:** `Cmd + C` / `Cmd + V` / `Cmd + X` / `Cmd + A` / `Cmd + Z` / `Cmd + Shift + Z` (automatically sends `Ctrl+Shift+C/V` in terminals so physical `Ctrl+C` still sends `SIGINT`).
- **Toggle Window Floating:** `Cmd + Shift + F` (`SUPER + SHIFT + F`) or `Cmd + Option + V` (`SUPER + ALT + V`).
- **Mission Control & Spaces Overview:** 3-finger swipe up (or `Ctrl + Up`) to open; 3-finger swipe down (or `Ctrl + Down` / `Esc`) to close; 3-finger swipe left/right to switch workspaces.
- **Screenshot Region to Clipboard:** `Ctrl + Cmd + Shift + 4` (`CTRL + SUPER + SHIFT + 4` / `code:13`) or `Cmd + Shift + 4` (`SUPER + SHIFT + 4`) or `Cmd + Shift + P`.
- **Clipboard History (Maccy equivalent):** Click the clipboard icon (`󰅌`) in the top-right status bar, or press `Cmd + Shift + V` (`SUPER + SHIFT + V`) / `Cmd + Ctrl + V` (`SUPER + CTRL + V`).
- **Disable Auto-Sleep (Theine equivalent):** Click the coffee mug icon (``) in the top-right status bar, press `Cmd + Shift + I` (`SUPER + SHIFT + I`), or run `omarchy-toggle-idle toggle`.
- **Touchpad Scrolling:** Inverted (`natural_scroll = false`, so content goes up when fingers go up) and smoothed (`scroll_factor = 0.5`).
- **Battery Charge Threshold:** Set to `100%` by default (`omarchnix.hardware.m2air.chargeControlEndThreshold = 100;`), adjustable live via `/sys/class/power_supply/macsmc-battery/charge_control_end_threshold`.

## Touch ID Activation (`m2air`)

1. Switch to the `m2air` flake configuration and stage the verified Aurora Stage 2 bootloader (`m1n1/boot.bin`, preserving `m1n1/boot.bin.pre-aurora`):
   ```bash
   sudo nixos-rebuild switch --flake ~/nixos-config#m2air
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
   - **Lock screen (`omarchy-shell`, `SUPER + ESCAPE`)**, **`sudo`**, and **Polkit GUI prompts (`polkit-1`)**
   - **Bitwarden Desktop:** Settings → Security → *Unlock with biometrics* (uses `polkit-1` + `gnome-keyring`) and WebAuthn/FIDO2 passkeys via `aurora-fido`
   - **Orb Desktop:** Native `orb-signer` uses `fprintd-verify` for Touch ID approval & biometric authentication

## Common Commands

```bash
# Test or switch configuration on MacBook Air M2:
sudo nixos-rebuild switch --flake ~/nixos-config#m2air

# Pull latest omarchnix upstream changes (themes, shell, modules):
cd ~/nixos-config && nix flake update omarchnix
```
