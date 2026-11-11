# NixOS & Omarchnix Workspace (`/home/thomas/work/nixos`)

This workspace contains the complete declarative configuration for Thomas's Apple Silicon MacBook Air M2 (`m2air`, `aarch64-linux`), NVIDIA Grace workstation (`spark`), and turnkey QEMU desktop VMs (`vm`, `vm-x86_64`) running NixOS + Omarchy (`omarchnix`).

---

## 1. Directory & Repository Layout

```text
/home/thomas/work/nixos/
├── README.md                  # Quick reference & architecture overview
├── AGENTS.md                  # Full context & operational instructions for AI coding agents
├── CLAUDE.md -> AGENTS.md     # Symlink for Claude Code / OpenCode
├── nixos-config/              # Personal NixOS & Home Manager flake (github.com/Th0rgal/nixos-config)
│   ├── flake.nix              # Thin flake entry point via omarchnix.lib.mkHost (m2air, spark, vm, vm-x86_64)
│   ├── flake.lock             # Pins omarchnix, folio, and Th0rgal/wallpapers
│   ├── inventory/
│   │   ├── user.nix           # User identity, email, SSH public keys, World Clock cities
│   │   ├── fleet.nix          # Declarative SSH/Tailscale host inventory (spark, macbook, hetzner)
│   │   └── wallpapers.nix     # Default & Space 1..8 wallpaper mapping against inputs.wallpapers
│   ├── hosts/
│   │   ├── common/
│   │   │   └── thomas-user.nix # Shared system user account, groups & authorizedKeys
│   │   ├── m2air/
│   │   │   ├── default.nix    # Personal m2air NixOS config (Aurora Touch ID, 100% battery, desktop apps)
│   │   │   └── hardware.nix   # Partition UUIDs (BOOT=C650-1003, LUKS=aa0403dd-...)
│   │   ├── spark/
│   │   │   └── default.nix    # ARM64 workstation & server host (uses home/profiles/desktop.nix)
│   │   └── vm/
│   │       └── default.nix    # Turnkey QEMU desktop VM with auto-login & wallpapers
│   └── home/
│       ├── assets/
│       │   └── discord.svg    # Official Discord icon override for Vesktop
│       ├── modules/
│       │   ├── ssh-config.nix # Typed SSH config generator from inventory/fleet.nix
│       │   ├── agent-shims.nix # Guard shims enforcing bun & turbo-lean
│       │   └── opencode.nix   # OpenCode v2 CLI & Orb Router provider config
│       ├── profiles/
│       │   ├── core.nix       # Headless CLI, Git, SSH, OpenCode, CLI tools (shared across all hosts)
│       │   └── desktop.nix    # Full GUI, macOS suite, Space wallpapers, MIME defaults, Ghostty
│       └── thomas.nix         # Backwards-compatibility alias importing ./profiles/desktop.nix
└── omarchnix/                 # Reusable public Omarchy-on-NixOS & M2 Air stack (github.com/Th0rgal/omarchnix)
    ├── flake.nix              # Exports lib.mkHost, overlays.default, nixosModules.*, homeManagerModules.*
    ├── lib/
    │   ├── default.nix        # Theme/color helpers & buildPlugin
    │   └── mkHost.nix         # Turnkey NixOS + Home Manager host builder (m2air-aurora, spark, vm, ...)
    ├── overlays/
    │   └── default.nix        # Package overlay (omarchy-shell, orb-desktop, murmure, folio, plugins, ...)
    ├── modules/
    │   ├── omarchnix.nix      # System-level NixOS module (`omarchnix.*` + `omarchnix.desktop.macos.*`)
    │   ├── hardware-m2air.nix # Reusable Apple Silicon MacBook Air M2 hardware module
    │   ├── hardware-spark.nix # Reusable NVIDIA Grace / DGX Spark hardware module
    │   ├── pam-fido.nix       # PAM U2F/FIDO2 biometric module
    │   └── pam-touchid.nix    # PAM fprintd Touch ID module
    ├── home/
    │   ├── default.nix        # Core Home Manager module (`programs.omarchnix.*`, adw-gtk3 & MoreWaita)
    │   ├── macos.nix          # Graduated macOS-feel suite (Mission Control, Dock, Spotlight, Touch ID HUD, Space Wallpapers, Slanted Workspaces)
    │   ├── hyprland.nix       # Hyprland Lua config (`~/.config/hypr/hyprland.lua`), keybindings, gestures, window rules
    │   ├── omarchy-shell.nix  # Quickshell bar & single-pass atomic `shell.json` reconciler
    │   ├── sharing.nix        # Unified Nearby / QuickShare / LocalSend / Ombuddy sharing plugin
    │   ├── ghostty.nix        # Declarative Ghostty terminal config (`~/.config/ghostty/config`)
    │   ├── thunar.nix         # Declarative Thunar preferences & bookmarks
    │   ├── hypridle.nix       # Idle / Stay-Awake / DPMS / auto-lock config (`~/.config/hypr/hypridle.conf`)
    │   ├── hyprlock.nix       # Lockscreen config
    │   ├── foot.nix           # Foot terminal config (`~/.config/foot/foot.ini`)
    │   └── agents.nix         # Coding agent skill & Orb agent sync integration
    ├── themes/                # Built-in declarative themes (starship-subtle, tokyo-night, catppuccin, ...)
    └── pkgs/                  # Packaged components & plugins (`pkgs/plugins/{mission-control,macos-dock,spotlight,touchid,workspace-wallpapers,workspaces,omarchnix-sharing,...}`)
```

> **Compatibility Symlinks & Companion Repos:**
> - `/home/thomas/nixos-config` $\rightarrow$ `/home/thomas/work/nixos/nixos-config`
> - `/home/thomas/omarchnix-deploy` $\rightarrow$ `/home/thomas/work/nixos/omarchnix`
> - `/home/thomas/Pictures/Wallpapers` $\rightarrow$ [`github.com/Th0rgal/wallpapers`](https://github.com/Th0rgal/wallpapers)

---

## 2. Which Repository Should I Edit?

| Change Type | Where to Edit |
| :--- | :--- |
| **Personal SSH hosts, git/user identity, Space wallpaper mapping** | [`nixos-config/inventory/fleet.nix`](file:///home/thomas/work/nixos/nixos-config/inventory/fleet.nix), [`nixos-config/inventory/user.nix`](file:///home/thomas/work/nixos/nixos-config/inventory/user.nix), [`nixos-config/inventory/wallpapers.nix`](file:///home/thomas/work/nixos/nixos-config/inventory/wallpapers.nix) |
| **Personal packages, host mounts/UUIDs, personal desktop apps or VM settings** | [`nixos-config/hosts/m2air/default.nix`](file:///home/thomas/work/nixos/nixos-config/hosts/m2air/default.nix), [`nixos-config/home/profiles/desktop.nix`](file:///home/thomas/work/nixos/nixos-config/home/profiles/desktop.nix), [`nixos-config/hosts/vm/default.nix`](file:///home/thomas/work/nixos/nixos-config/hosts/vm/default.nix) |
| **Shared Omarchy desktop behavior, macOS-feel plugins (Dock, Mission Control, Spotlight, Touch ID HUD, Space Wallpapers), Hyprland bindings, reusable M2 Air hardware module** | [`omarchnix/home/macos.nix`](file:///home/thomas/work/nixos/omarchnix/home/macos.nix), [`omarchnix/pkgs/plugins/`](file:///home/thomas/work/nixos/omarchnix/pkgs/plugins), [`omarchnix/home/hyprland.nix`](file:///home/thomas/work/nixos/omarchnix/home/hyprland.nix), [`omarchnix/modules/hardware-m2air.nix`](file:///home/thomas/work/nixos/omarchnix/modules/hardware-m2air.nix) |

---

## 3. Common Workflows

### A. Full System & Home Manager Rebuild
From [`/home/thomas/work/nixos/nixos-config`](file:///home/thomas/work/nixos/nixos-config):
```bash
cd /home/thomas/work/nixos/nixos-config

# Rebuild using the pushed omarchnix & wallpapers inputs in flake.lock:
sudo nixos-rebuild switch --flake .#m2air

# Or test local uncommitted/unmerged changes in ../omarchnix directly:
sudo nixos-rebuild switch --flake .#m2air \
  --override-input omarchnix path:/home/thomas/work/nixos/omarchnix

# Spin up a turnkey desktop VM (with full macOS suite & Space wallpapers):
nixos-rebuild build-vm --flake .#vm
./result/bin/run-omarchnix-vm-vm
```

### B. Applying Home Manager / Hyprland Changes Live (Without `sudo` or Rebooting)
Hyprland runs in **Lua configuration mode** (`~/.config/hypr/hyprland.lua`). Note that `hyprctl keyword` is not used in Lua mode; instead, build the generated config file from the flake, copy it into `~/.config`, and run `hyprctl reload`:
```bash
cd /home/thomas/work/nixos/nixos-config
export XDG_RUNTIME_DIR=/run/user/1000
export HYPRLAND_INSTANCE_SIGNATURE=$(hyprctl instances -j | jq -r '.[0].instance')

# 1. Rebuild & reload Hyprland Lua config live:
HYPR_LUA=$(nix build --no-link --print-out-paths \
  --override-input omarchnix path:/home/thomas/work/nixos/omarchnix \
  '.#nixosConfigurations.m2air.config.home-manager.users.thomas.xdg.configFile."hypr/hyprland.lua".source')
cp --remove-destination "$HYPR_LUA" ~/.config/hypr/hyprland.lua
chmod 644 ~/.config/hypr/hyprland.lua
hyprctl reload
hyprctl configerrors -j

# 2. Rescan & enable Omarchy Shell plugins (`~/.config/omarchy/shell.json` is a regular writable file):
omarchy-shell shell rescanPlugins
omarchnix plugin enable <plugin-id>
```

### C. Syncing `omarchnix` or `wallpapers` into `nixos-config`
After committing and pushing changes in [`/home/thomas/work/nixos/omarchnix`](file:///home/thomas/work/nixos/omarchnix) or [`/home/thomas/Pictures/Wallpapers`](file:///home/thomas/Pictures/Wallpapers):
```bash
cd /home/thomas/work/nixos/nixos-config
nix flake update omarchnix wallpapers
nix fmt
git add flake.lock && git commit -m "chore(flake): update inputs" && git push
```

---

## 4. Hardware & Desktop Reference (`m2air`)

### Keyboard Shortcuts (`Option` = `ALT` for WM & Spaces, `Cmd` = `SUPER` for Orb & Apps)
- **Close Tab / Window / Force-Kill:** `Cmd + W` (closes active tab in browsers, editors & Ghostty, or single-window apps; ignored in Orb) / `Option + Shift + Q` (close whole window) / `Ctrl + Option + Shift + Q` (force-kill)
- **Task & Performance Manager (Mission Center):** `Ctrl + Shift + Esc` (Windows Task Manager shortcut) or `Cmd + Option + Esc` (macOS Force Quit shortcut) — summons/toggles centered floating Mission Center on the active Space
- **Copy / Paste / Cut / Select All / Undo / Redo / Find:** `Cmd + C` / `Cmd + V` / `Cmd + X` / `Cmd + A` / `Cmd + Z` / `Cmd + Shift + Z` / `Cmd + F` (automatically translates to `Ctrl+Shift+C/V/F` in terminals so physical `Ctrl+C` still sends `SIGINT` and `Cmd+F` opens terminal search)
- **Clipboard History (Maccy equivalent):** `Cmd + Shift + V` or `Cmd + Ctrl + V`, or click the **`󰅌`** icon in the top-right status bar
- **Region Screenshot to Clipboard:** `Ctrl + Cmd + Shift + 4`, `Cmd + Shift + 4`, or `Option + Shift + 4` (`Option/Cmd + Shift + P`); capture & screen-recording menu on `Ctrl + Cmd + Shift + 5`, `Cmd + Shift + 5`, or `Option + Shift + 5`
- **Mission Control & Spaces Overview:** **`Option + Esc`** or **3-finger swipe up** to open; **`Esc` / `Option + Esc`** or **3-finger swipe down** to close; **3-finger swipe left/right**, **`Option + 1..0`**, or **`Option + Tab`** (previous Space) to switch workspaces (`Option + Shift + 1..0` to move & follow, `Option + Ctrl + 1..0` to move silently)
- **Stay Awake / Theine Toggle:** Click the coffee cup icon in the top-right status bar or press `Option + Shift + I` / `Cmd + Shift + I`
- **Window Focus / Move (Tiling WM):** `Option + H/J/K/L` (or `Option + Arrows`) to focus; `Option + Shift + H/J/K/L` (or `Option + Shift + Arrows`) to move; `Option + Left/Right-Drag` to move/resize with trackpad
- **Toggle Window Floating / Centered Showcase / Split / Pseudo:** `Option + Shift + F` or `Option + V` (float), `Option + C` (centered floating showcase w/ wallpaper margins — press again to re-tile), `Option + E` (split), `Option + P` (pseudo)
- **Fullscreen / Maximize:** `Option + F` (fullscreen) / `Option + M` (maximize)
- **Scratchpad:** `Option + S` or `` Option + ` `` (`Shift` moves window to scratchpad)
- **Spotlight / Orb / Terminal / Theme / Reload / Lock:** `Option + Space` (`Option + Shift + Space` expanded App Grid) / `Option + O` / `Option + Return` / `Option + Shift + T` (dark/light toggle; `Option + Ctrl + T` picker) / `Option + Shift + R` (reload Hyprland) / **Power / Touch ID button**, `Ctrl + Cmd + Q`, or `Cmd + Escape`
- **Keyboard Backlight:** `Option + F1/F2`, `Ctrl + F1/F2`, or `Cmd + F1/F2` (`Option/Ctrl/Cmd + BrightnessDown/Up`), or open the **`󰍹`** Display & Brightness tray panel (**KEYBOARD BACKLIGHT** slider; right-click toggles `0%` $\leftrightarrow$ last level)

### Keyboard Backlight (`kbd_backlight`)
- Default in NixOS config: **`50%`** (`omarchnix.hardware.m2air.keyboardBacklight.defaultBrightness = 50;`, `enforceOnBoot = false;`; step & idle-blanking via `omarchnix.desktop.keyboard.backlight.{step,dimOnIdle}`).
- Change live from CLI or sysfs without `sudo`:
  ```bash
  omarchnix kbd-backlight 50%
  omarchnix kbd-backlight get --json
  ```

### Battery Charge Cap (`macsmc-battery`)
- Default in NixOS config: **`100%`** (`omarchnix.hardware.m2air.chargeControlEndThreshold = 100;`).
- Change live at any time without `sudo`:
  ```bash
  echo 80 > /sys/class/power_supply/macsmc-battery/charge_control_end_threshold
  echo 100 > /sys/class/power_supply/macsmc-battery/charge_control_end_threshold
  ```
