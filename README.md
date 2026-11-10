# `Th0rgal/nixos-config`

Personal NixOS & Home Manager configurations for Thomas's machines (`m2air`, `m2air-aurora`, `spark`), built on top of the reusable [`Th0rgal/omarchnix`](https://github.com/Th0rgal/omarchnix) flake.

## Architecture: `omarchnix` vs `nixos-config`

| Repository | Purpose | What Lives There |
| :--- | :--- | :--- |
| **[`Th0rgal/omarchnix`](https://github.com/Th0rgal/omarchnix)** | Reusable public distribution & installer | `packages.installer-bootstrap` ISO, `nixosModules.omarchnix`, `homeManagerModules.omarchnix`, `overlays.default`, `lib.omarchy`, `omarchy-shell`, 23+ themes, `plugins.omarchy.org` builder, Apple Silicon Asahi/Aurora SEP hardware modules, and CI gate tests. |
| **[`Th0rgal/nixos-config`](https://github.com/Th0rgal/nixos-config)** | Personal machine & user configuration | Host definitions (`hosts/m2air`, `hosts/spark`), machine-specific partition UUIDs (`hosts/m2air/hardware.nix`), personal packages (`brave`, `bitwarden-desktop`, `telegram-desktop`, `orb-desktop`, `notion-calendar`, `antigravity-hub`), SSH public keys, and personal Home Manager overrides (`home/thomas.nix`). |

## Hosts

- **`m2air`**: MacBook Air M2 daily driver (`aarch64-linux`, Asahi Linux + removable ARM64 UEFI GRUB on `UUID=C650-1003` + LUKS2 `cryptroot` `UUID=aa0403dd-9917-49e1-85b1-46b21b39f2d9`).
- **`m2air-aurora`**: Stage-2 MacBook Air M2 configuration enabling `aurora-silicon/nixos-aurora` Secure Enclave, Touch ID (`fprintd`), and FIDO2 (`aurora-fido`).
- **`spark`**: NVIDIA Grace / DGX Spark ARM64 workstation & server (`aarch64-linux`).

## Common Commands

```bash
# Test or switch configuration on MacBook Air M2:
sudo nixos-rebuild switch --flake ~/nixos-config#m2air

# Pull latest omarchnix upstream changes (themes, shell, modules):
cd ~/nixos-config && nix flake update omarchnix

# Point temporarily to a local omarchnix checkout while hacking on omarchnix:
sudo nixos-rebuild test --flake ~/nixos-config#m2air --override-input omarchnix path:/home/thomas/omarchnix-deploy
```
