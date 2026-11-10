# NixOS & Omarchnix Workspace (`/home/thomas/work/nixos`)

This workspace contains the complete declarative configuration for Thomas's Apple Silicon MacBook Air M2 (`m2air`, `aarch64-linux`) running NixOS + Omarchy (`omarchnix`).

---

## 1. Directory & Repository Layout

```text
/home/thomas/work/nixos/
├── README.md                  # Quick reference & architecture overview
├── AGENTS.md                  # Full context & operational instructions for AI coding agents
├── CLAUDE.md -> AGENTS.md     # Symlink for Claude Code / OpenCode
├── nixos-config/              # Personal NixOS & Home Manager flake (github.com/Th0rgal/nixos-config)
│   ├── flake.nix              # Flake entry point (consumes github:Th0rgal/omarchnix)
│   ├── flake.lock
│   ├── hosts/
│   │   ├── m2air/
│   │   │   ├── default.nix    # Personal m2air NixOS config (user thomas, Touch ID, 100% battery, apps)
│   │   │   └── hardware-configuration.nix
│   │   └── spark/             # Secondary x86_64 workstation host
│   └── home/
│       └── thomas.nix         # Personal Home Manager config (git identity, MIME defaults, Mission Control overlay & gestures)
└── omarchnix/                 # Reusable public Omarchy-on-NixOS & M2 Air stack (github.com/Th0rgal/omarchnix)
    ├── flake.nix              # Exports nixosModules.{default,m2air-hardware}, homeManagerModules.default, templates
    ├── modules/
    │   ├── omarchnix.nix      # System-level NixOS module (`omarchnix.*` options: Hyprland, UWSM, greetd, PipeWire, fonts)
    │   ├── m2air-hardware.nix # Reusable Apple Silicon MacBook Air M2 hardware module (`omarchnix.hardware.m2air.*`)
    │   ├── pam-fido.nix       # PAM U2F/FIDO2 biometric module
    │   └── pam-touchid.nix    # PAM fprintd Touch ID module
    ├── home/
    │   ├── default.nix        # Core Home Manager module (`programs.omarchnix.*`, packages, fastfetch, XDG mimeApps)
    │   ├── hyprland.nix       # Hyprland Lua config (`~/.config/hypr/hyprland.lua`), keybindings, gestures, window rules
    │   ├── omarchy-shell.nix  # Quickshell-based `omarchy-shell` bar, dock, launcher, notifications, plugins (`~/.config/omarchy/shell.json`)
    │   ├── hypridle.nix       # Idle / Stay-Awake / DPMS / auto-lock config (`~/.config/hypr/hypridle.conf`)
    │   ├── hyprlock.nix       # Lockscreen config
    │   ├── foot.nix           # Foot terminal config (`~/.config/foot/foot.ini`)
    │   └── agents.nix         # Coding agent skill & theme integration
    ├── themes/                # Built-in declarative themes (tokyo-night, catppuccin, gruvbox, kanagawa, nord, rose-pine)
    └── pkgs/                  # Packaged components (`omarchnix` CLI, `omarchy-shell`, `aurora-touchid`)
```

> **Compatibility Symlinks:**
> - `/home/thomas/nixos-config` $\rightarrow$ `/home/thomas/work/nixos/nixos-config`
> - `/home/thomas/omarchnix-deploy` $\rightarrow$ `/home/thomas/work/nixos/omarchnix`

---

## 2. Which Repository Should I Edit?

| Change Type | Where to Edit |
| :--- | :--- |
| **Personal packages, git identity, personal host mounts/UUIDs, personal bar plugins** | [`nixos-config/hosts/m2air/default.nix`](file:///home/thomas/work/nixos/nixos-config/hosts/m2air/default.nix) or [`nixos-config/home/thomas.nix`](file:///home/thomas/work/nixos/nixos-config/home/thomas.nix) |
| **Shared Omarchy desktop behavior, Hyprland keybindings/gestures, theme engine, reusable M2 Air hardware module** | [`omarchnix/home/hyprland.nix`](file:///home/thomas/work/nixos/omarchnix/home/hyprland.nix), [`omarchnix/home/default.nix`](file:///home/thomas/work/nixos/omarchnix/home/default.nix), [`omarchnix/modules/m2air-hardware.nix`](file:///home/thomas/work/nixos/omarchnix/modules/m2air-hardware.nix) |

---

## 3. Common Workflows

### A. Full System & Home Manager Rebuild
From [`/home/thomas/work/nixos/nixos-config`](file:///home/thomas/work/nixos/nixos-config):
```bash
cd /home/thomas/work/nixos/nixos-config

# Rebuild using the pushed omarchnix input in flake.lock:
sudo nixos-rebuild switch --flake .#m2air

# Or test local uncommitted/unmerged changes in ../omarchnix directly:
sudo nixos-rebuild switch --flake .#m2air \
  --override-input omarchnix path:/home/thomas/work/nixos/omarchnix
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

### C. Syncing `omarchnix` into `nixos-config`
After committing and pushing changes in [`/home/thomas/work/nixos/omarchnix`](file:///home/thomas/work/nixos/omarchnix):
```bash
cd /home/thomas/work/nixos/omarchnix
OMARCHNIX_REV=$(git rev-parse HEAD)

cd /home/thomas/work/nixos/nixos-config
nix flake update omarchnix --override-input omarchnix "github:Th0rgal/omarchnix/${OMARCHNIX_REV}"
nix fmt
git add flake.lock && git commit -m "chore(flake): update omarchnix input" && git push
```

---

## 4. Hardware & Desktop Reference (`m2air`)

### Keyboard Shortcuts (`Cmd` = `SUPER`)
- **Close / Force-Kill Window:** `Cmd + W` or `Cmd + Q` / `Cmd + Shift + Q`
- **Copy / Paste / Cut / Select All / Undo / Redo:** `Cmd + C` / `Cmd + V` / `Cmd + X` / `Cmd + A` / `Cmd + Z` / `Cmd + Shift + Z` (automatically translates to `Ctrl+Shift+C/V` in terminals so physical `Ctrl+C` still sends `SIGINT`)
- **Clipboard History (Maccy equivalent):** `Cmd + Shift + V` or `Cmd + Ctrl + V`, or click the **`󰅌`** icon in the top-right status bar
- **Region Screenshot to Clipboard:** `Ctrl + Cmd + Shift + 4` (or `Cmd + Shift + 4` / `Cmd + Shift + P`)
- **Mission Control & Spaces Overview:** **3-finger swipe up** (or `Ctrl + Up`) to open immediately on gesture start; **3-finger swipe down** (or `Ctrl + Down` / `Esc`) to close; **3-finger swipe left/right** to switch workspaces (`180px` distance, `0.15` cancel ratio, `min_speed_to_force = 5` for effortless macOS flicks)
- **Stay Awake / Theine Toggle:** Click the coffee cup icon in the top-right status bar or press `Cmd + Shift + I`
- **Toggle Window Floating:** `Cmd + Shift + F` or `Cmd + Option + V`
- **Fullscreen / Maximize:** `Cmd + F` / `Cmd + M`
- **App Launcher / Terminal / Lock:** `Cmd + Space` / `Cmd + Return` / `Cmd + Escape`

### Battery Charge Cap (`macsmc-battery`)
- Default in NixOS config: **`100%`** (`omarchnix.hardware.m2air.chargeControlEndThreshold = 100;`).
- Change live at any time without `sudo`:
  ```bash
  echo 80 > /sys/class/power_supply/macsmc-battery/charge_control_end_threshold
  echo 100 > /sys/class/power_supply/macsmc-battery/charge_control_end_threshold
  ```
