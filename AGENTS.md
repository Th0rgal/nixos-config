# NixOS & Omarchnix Workspace (`/home/thomas/work/nixos`)

Thomas deploys NixOS on the Apple Silicon MacBook Air M2 (`m2air`). The ARM QEMU guest (`vm`) and x86 cloud guest (`vm-x86_64`) exercise the shared desktop for screenshots and agent testing. The DGX Spark runs Ubuntu and serves as a remote Nix builder.

---

## 1. Directory & Repository Layout

```text
/home/thomas/work/nixos/
├── README.md                  # Quick reference & architecture overview
├── AGENTS.md                  # Full context & operational instructions for AI coding agents
├── CLAUDE.md -> AGENTS.md     # Symlink for Claude Code / OpenCode
├── nixos-config/              # Personal NixOS & Home Manager flake (github.com/Th0rgal/nixos-config)
│   ├── flake.nix              # Thin flake entry point via omarchnix.lib.mkHost (m2air variants, vm, vm-x86_64)
│   ├── flake.lock             # Pins omarchnix, folio, and Th0rgal/wallpapers
│   ├── inventory/
│   │   ├── user.nix           # User identity, email, SSH public keys, World Clock cities
│   │   └── wallpapers.nix     # Default & Space 1..8 wallpaper mapping against inputs.wallpapers
│   ├── hosts/
│   │   ├── common/
│   │   │   ├── desktop.nix    # Settings shared by every desktop host (omarchnix, theme, HM profile)
│   │   │   └── thomas-user.nix # User account, groups, authorizedKeys & the `rebuild` command
│   │   ├── m2air/
│   │   │   ├── default.nix    # Personal m2air NixOS config (Aurora Touch ID, 90% charge cap, desktop apps)
│   │   │   ├── hardware.nix   # Partition UUIDs, GRUB (5 generations) & /boot space pre-switch check
│   │   │   └── secrets.nix    # sops-nix: builder key, Nix signing key, restic password
│   │   └── vm/
│   │       └── default.nix    # Turnkey QEMU desktop VM with auto-login & wallpapers
│   └── home/
│       ├── assets/
│       │   └── discord.svg    # Official Discord icon override for Vesktop
│       ├── modules/
│       │   ├── ssh-config.nix # Typed SSH config generator from the private `inventory.fleet`
│       │   ├── agent-shims.nix # Guard shims enforcing bun & turbo-lean
│       │   ├── opencode.nix   # OpenCode v2 CLI & Orb Router provider config
│       │   └── backup.nix     # Daily restic backup to Google Drive (see docs/backup.md)
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
    │   ├── pam-fido.nix       # PAM U2F/FIDO2 biometric module
    │   └── pam-touchid.nix    # PAM fprintd Touch ID module
    ├── home/
    │   ├── default.nix        # Core Home Manager module (`programs.omarchnix.*`, adw-gtk3 & MoreWaita)
    │   ├── macos.nix          # Graduated macOS-feel suite (Mission Control, Dock, Spotlight, Touch ID HUD, Space Wallpapers, Slanted Workspaces)
    │   ├── hyprland.nix       # Hyprland Lua config (`~/.config/hypr/hyprland.lua`), keybindings, gestures, window rules
    │   ├── omarchy-shell.nix  # Quickshell bar & single-pass atomic `shell.json` reconciler
    │   ├── sharing.nix        # Unified Nearby / QuickShare / LocalSend / Ombuddy sharing plugin
    │   ├── ghostty.nix        # Declarative Ghostty terminal config (`~/.config/ghostty/config`)
    │   ├── flea.nix           # Declarative Flea GPUI/Vulkan file manager + Nearby Share plugin
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
| **Personal SSH hosts, lean-mesh peers, remote builder** | [`nixos-inventory`](file:///home/thomas/work/nixos/nixos-inventory) (private repo, `inventory` flake input; then `nix flake update inventory`) |
| **Git/user identity, Space wallpaper mapping** | [`nixos-config/inventory/user.nix`](file:///home/thomas/work/nixos/nixos-config/inventory/user.nix), [`nixos-config/inventory/wallpapers.nix`](file:///home/thomas/work/nixos/nixos-config/inventory/wallpapers.nix) |
| **Personal packages, host mounts/UUIDs, personal desktop apps or VM settings** | [`nixos-config/hosts/m2air/default.nix`](file:///home/thomas/work/nixos/nixos-config/hosts/m2air/default.nix), [`nixos-config/home/profiles/desktop.nix`](file:///home/thomas/work/nixos/nixos-config/home/profiles/desktop.nix), [`nixos-config/hosts/vm/default.nix`](file:///home/thomas/work/nixos/nixos-config/hosts/vm/default.nix) |
| **Shared Omarchy desktop behavior, macOS-feel plugins (Dock, Mission Control, Spotlight, Touch ID HUD, Space Wallpapers), Hyprland bindings, reusable M2 Air hardware module** | [`omarchnix/home/macos.nix`](file:///home/thomas/work/nixos/omarchnix/home/macos.nix), [`omarchnix/pkgs/plugins/`](file:///home/thomas/work/nixos/omarchnix/pkgs/plugins), [`omarchnix/home/hyprland.nix`](file:///home/thomas/work/nixos/omarchnix/home/hyprland.nix), [`omarchnix/modules/hardware-m2air.nix`](file:///home/thomas/work/nixos/omarchnix/modules/hardware-m2air.nix) |
| **Theme palettes & frosted-glass surface tokens (`shell.toml`)** | [`nixos-config/themes/starship-subtle/default.nix`](file:///home/thomas/work/nixos/nixos-config/themes/starship-subtle/default.nix) (Starship Dark/Light overrides), [`omarchnix/pkgs/omarchy-shell.nix`](file:///home/thomas/work/nixos/omarchnix/pkgs/omarchy-shell.nix) (`default/themed/shell.toml.tpl` defaults for all bundled themes) |

---

## 3. Common Workflows

### A. Full System & Home Manager Rebuild
`rebuild` (from [`hosts/common/thomas-user.nix`](file:///home/thomas/work/nixos/nixos-config/hosts/common/thomas-user.nix)) is the one entry point. It runs `nh os <verb>` on `nixos-config` and passes `--override-input omarchnix path:../omarchnix` when that checkout exists. Stock `nixos-rebuild` and `nh` are not wrapped, and no lock file changes unless asked.
```bash
rebuild                # switch, using local ../omarchnix if present
rebuild boot|test|build|build-vm|dry
rebuild --locked       # build exactly what flake.lock pins
rebuild --bump-orb     # first move omarchnix's orb input to Th0rgal/orb master

# Spin up a turnkey desktop VM (with full macOS suite & Space wallpapers):
nixos-rebuild build-vm --flake .#vm
./result/bin/run-omarchnix-vm-vm
```
- **Rollbacks:** GRUB keeps **5** generations; a pre-switch check refuses `switch`/`boot` when `/boot` can't fit a new kernel/initrd pair.
- **Private input:** `inventory` is fetched over SSH as `thomas`, so build with `rebuild` (or as your user), not plain `sudo nixos-rebuild`.
- **Lint & CI:** `nix fmt`, `nix run .#lint` (statix + deadnix); CI evaluates every host against `ci/inventory-stub`.

### Secrets & Backups
- **Secrets:** sops-nix, `secrets/m2air.yaml`, encrypted to the host key and the Bitwarden-held backup age key. `sudo bootstrap-secrets` creates or extends it; each secret is wired only once present.
- **Backups:** daily restic to `gdrive:01 — Backups & Infra/m2air-restic` on AC power, monthly verify; `backup status|now|snapshots|restore|mount`. Runbook: [`docs/backup.md`](file:///home/thomas/work/nixos/nixos-config/docs/backup.md).

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
- **Close Window / Force-Kill:** `Cmd + Shift + Q` or `Option + Shift + Q` (close active window) / `Ctrl + Option + Shift + Q` (force-kill)
- **Task & Performance Manager (Mission Center):** `Ctrl + Shift + Esc` (Windows Task Manager shortcut) or `Cmd + Option + Esc` (macOS Force Quit shortcut) — summons/toggles centered floating Mission Center on the active Space
- **Copy / Paste / Cut / Select All / Undo / Redo / Find:** `Cmd + C` / `Cmd + V` / `Cmd + X` / `Cmd + A` / `Cmd + Z` / `Cmd + Shift + Z` / `Cmd + F` (automatically translates to `Ctrl+Shift+C/V/F` in terminals so physical `Ctrl+C` still sends `SIGINT` and `Cmd+F` opens terminal search)
- **Clipboard History (Maccy equivalent):** `Cmd + Shift + V` or `Cmd + Ctrl + V`, or click the **`󰅌`** icon in the top-right status bar
- **Region Screenshot & Screen Recording:** `Ctrl + Shift + Cmd + 4` (region screenshot to clipboard); `Ctrl + Shift + Cmd + 5` (capture & screen-recording menu / stop recording)
- **Mission Control & Spaces Overview:** **`Option + Esc`** or **3-finger swipe up** to open; **`Esc` / `Option + Esc`** or **3-finger swipe down** to close; **3-finger swipe left/right**, **`Option + 1..0`**, or **`Option + Tab`** (previous Space) to switch workspaces (`Option + Shift + 1..0` to move & follow, `Option + Ctrl + 1..0` to move silently)
- **Stay Awake / Theine Toggle:** Click the coffee cup icon in the top-right status bar or press `Option + Shift + I` / `Cmd + Shift + I`
- **Window Focus / Move (Tiling WM):** `Option + H/J/K/L` (or `Option + Arrows`) to focus; `Option + Shift + H/J/K/L` (or `Option + Shift + Arrows`) to move; `Option + Left/Right-Drag` to move/resize with trackpad
- **Toggle Window Floating / Centered Showcase / Split / Pseudo:** `Option + Shift + F` or `Option + V` (float), `Option + C` (centered floating showcase w/ wallpaper margins — press again to re-tile), `Option + E` (split), `Option + P` (pseudo)
- **Fullscreen / Maximize:** `Option + F` (fullscreen) / `Option + M` (maximize)
- **Scratchpad:** `Option + S` or `` Option + ` `` (`Shift` moves window to scratchpad)
- **Spotlight / Orb / Terminal / Browser / Files / Theme / Reload / Lock:** `Option + Space` (`Option + Shift + Space` expanded App Grid) / `Option + O` / `Option + Return` / `Option + B` (Helium) / `Option + W` (Flea file manager) / `Option + Shift + T` (dark/light toggle; `Option + Ctrl + T` picker) / `Option + Shift + R` (reload Hyprland) / **Power / Touch ID button**, `Ctrl + Cmd + Q`, or `Cmd + Escape`
- **Keyboard Backlight:** `Option + F1/F2`, `Ctrl + F1/F2`, or `Cmd + F1/F2` (`Option/Ctrl/Cmd + BrightnessDown/Up`), or open the **`󰍹`** Display & Brightness tray panel (**KEYBOARD BACKLIGHT** slider; right-click toggles `0%` $\leftrightarrow$ last level)

### Keyboard Backlight (`kbd_backlight`)
- Default in NixOS config: **`50%`** (`omarchnix.hardware.m2air.keyboardBacklight.defaultBrightness = 50;`, `enforceOnBoot = false;`; step & idle-blanking via `omarchnix.desktop.keyboard.backlight.{step,dimOnIdle}`).
- Change live from CLI or sysfs without `sudo`:
  ```bash
  omarchnix kbd-backlight 50%
  omarchnix kbd-backlight get --json
  ```

### Battery Charge Cap (`macsmc-battery`)
- Default in NixOS config: **`90%`** (`omarchnix.hardware.m2air.chargeControlEndThreshold = 90;`).
- Change live at any time without `sudo`:
  ```bash
  echo 80 > /sys/class/power_supply/macsmc-battery/charge_control_end_threshold
  echo 100 > /sys/class/power_supply/macsmc-battery/charge_control_end_threshold
  ```

---

## 5. Omarchy Shell Glass, Blur & Theme Architecture

All desktop chrome shares a unified **Balanced Readable Glass** design powered natively by Hyprland's compositor Kawase blur (`passes = 3`, `size = 10`, `new_optimizations = true`, `xray = false`) and Omarchy's live `Commons.Color` (`shell.toml`) token layer — never QML offscreen blur shaders (`MultiEffect` / `FastBlur`).

### Two-Tier Frosted Translucency
- **Ambient Chrome (`alpha = 0.35`):** Top Status Bar (`bar.background = "glassy"` in `shell.json`), macOS Dock ([`DockWindow.qml`](file:///home/thomas/work/nixos/omarchnix/pkgs/plugins/macos-dock/DockWindow.qml)), and Spotlight ([`Spotlight.qml`](file:///home/thomas/work/nixos/omarchnix/pkgs/plugins/spotlight/Spotlight.qml)) clamp `Commons.Color.bar.background` to `0.35` alpha with a 1px `0.08` hairline border.
- **Balanced Readable Cards (`alpha = 0.45–0.48`, tooltips `0.58`):** Builtin Omarchy plugins — Clipboard (`omarchy.clipboard`), Menu (`omarchy.menu`), Emojis (`omarchy.emojis`), status-bar panels (`[popups]`), Notifications (`[notifications]`), Polkit (`[polkit]`), Lock input (`[lock]`), and Touch ID HUD ([`TouchId.qml`](file:///home/thomas/work/nixos/omarchnix/pkgs/plugins/touchid/TouchId.qml)) — use ~`0.45` (dark) / `0.48` (light) surface opacity so dense multi-line text stays crisp over high-contrast windows while preserving vibrant frosted blur across Dark & Light theme switches.
  - Defaults for all 20+ bundled themes live in `default/themed/shell.toml.tpl` (patched in [`omarchnix/pkgs/omarchy-shell.nix`](file:///home/thomas/work/nixos/omarchnix/pkgs/omarchy-shell.nix)); `starship-subtle` Dark/Light overrides live in [`nixos-config/themes/starship-subtle/default.nix`](file:///home/thomas/work/nixos/nixos-config/themes/starship-subtle/default.nix).

### Compositor & Qt Quick Invariants (Do Not Break)
1. **Keep fullscreen overlay backdrops below `ignore_alpha` (`scrim-alpha = 0.0 < ignore_alpha = 0.05`):**
   - `omarchy-glass`, `omarchnix-spotlight`, `omarchnix-touchid`, and `decoration.blur.popups_ignorealpha` use **`ignore_alpha = 0.05`** ([`hyprland.nix`](file:///home/thomas/work/nixos/omarchnix/home/hyprland.nix), [`macos.nix`](file:///home/thomas/work/nixos/omarchnix/home/macos.nix)).
   - Fullscreen `OverlayWindow` plugins (`Clipboard`, `Menu`, `Emojis`, `Polkit`) paint a fullscreen `scrim` `Rectangle` behind their centered card. Keeping `scrim-alpha = 0.0` ensures Hyprland skips blurring the surrounding screen (leaving the desktop sharp like Spotlight and avoiding fullscreen 3-pass blur fill-rate on Retina displays). Card-local modals ([`ConfirmDialog.qml`](file:///home/thomas/work/nixos/omarchnix/pkgs/omarchy-shell.nix)) dim only their parent card (`0.62` alpha) when `scrim-alpha == 0`.
2. **Keep card `border-alpha >= ignore_alpha`:**
   - Qt Quick's `Rectangle.border` (`BorderSurface`) replaces outer pixel alpha directly instead of compositing over `color`. Any blurred card border must have `border-alpha >= 0.05` (e.g., `0.08` specular hairline on Dock/Spotlight, or ~`18%` `foreground`-into-`background` pre-mixed neutral border at `border-alpha = 0.55–0.65` in `shell.toml`) so the 1px outer ring stays inside Hyprland's blur mask without bright-window halo bleed.
