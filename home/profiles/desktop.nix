# Full GUI desktop Home Manager profile layered on top of `core.nix` and `programs.omarchnix`.
{
  config,
  inputs,
  lib,
  options,
  pkgs,
  inventory,
  ...
}:
let
  inherit (inventory) user;
  inherit (inventory) wallpapers;
  starshipSubtle = import ../../themes/starship-subtle { inherit pkgs lib; };
in
{
  imports = [
    ./core.nix
    ../modules/backup.nix
    inputs.folio.homeManagerModules.default
  ];

  programs.folio = {
    enable = true;
    defaultNotesDir = user.hindsight.notesDir;
    hindsight = {
      apiUrl = user.hindsight.apiUrl;
      defaultBank = user.hindsight.bank;
    }
    // lib.optionalAttrs (options.programs.folio.hindsight ? syncBank) {
      syncBank = user.hindsight.syncBank;
    };
  };

  home.activation.ensureFolioNotesDir = config.lib.dag.entryAfter [ "writeBoundary" ] ''
    mkdir -p "$HOME/Notes"
  '';

  # Seed `~/Pictures/Wallpapers/*.webp` from `inputs.wallpapers` on fresh VMs/workstations,
  # refreshing `/nix/store` (or dangling) symlinks when `inputs.wallpapers` updates while
  # preserving regular files in an existing `~/Pictures/Wallpapers` git checkout (`m2air`).
  home.activation.seedWallpapersDir = config.lib.dag.entryAfter [ "writeBoundary" ] ''
    mkdir -p "$HOME/Pictures/Wallpapers"
    for existing in "$HOME/Pictures/Wallpapers"/*.webp; do
      [ -L "$existing" ] || continue
      case "$(readlink "$existing" || true)" in
        /nix/store/*)
          [ -f "${inputs.wallpapers}/$(basename "$existing")" ] || rm -f "$existing"
          ;;
      esac
    done
    for wp in "${inputs.wallpapers}"/*.webp; do
      [ -f "$wp" ] || continue
      dest="$HOME/Pictures/Wallpapers/$(basename "$wp")"
      if [ -L "$dest" ]; then
        case "$(readlink "$dest" || true)" in
          /nix/store/*)
            ln -sfn "$wp" "$dest"
            ;;
          *)
            [ -e "$dest" ] || ln -sfn "$wp" "$dest"
            ;;
        esac
      elif [ ! -e "$dest" ]; then
        ln -sfn "$wp" "$dest"
      fi
    done
  '';

  programs.omarchnix = {
    cloudMounts.enable = true;
    rbw.enable = true;

    agents = {
      # Skills are managed centrally via `th0rgal/orb-library` (`.orb-lib.lock`),
      # and `~/.config/opencode/opencode.json` is reconciled by `home/modules/opencode.nix`.
      installSkills = false;
      manageOpencodeConfig = false;
      targets = [
        "opencode"
        "claude"
        "codex"
      ];
      orbSync = {
        enable = true;
        orbOnly = true;
        minimalHud = true;
        launchInOrb = false;
        manageAccountsInPanel = false;
        pollInterval = "5m";
      };
    };

    theme = {
      name = starshipSubtle.dark.name;
      darkName = starshipSubtle.dark.name;
      lightName = starshipSubtle.light.name;
      attrset = starshipSubtle.dark.attrset;
      customThemes = {
        ${starshipSubtle.dark.name} = starshipSubtle.dark.package;
        ${starshipSubtle.light.name} = starshipSubtle.light.package;
      };
    };

    shell.elsewhenCities = user.elsewhenCities;

    macos = {
      enable = lib.mkDefault true;
      wallpapers = {
        enable = true;
        inherit (wallpapers) default workspaces;
      };
    };
  };

  home.sessionVariables = {
    BROWSER = "helium";
    QT_QPA_PLATFORMTHEME = "xdgdesktopportal";
    FOLIO_SYNC_BANK = lib.mkDefault user.hindsight.syncBank;
  };

  home.activation.personalGtkBookmarks =
    config.lib.dag.entryAfter [ "omarchnixUserDirs" "ensureFolioNotesDir" ]
      ''
        bookmarks_file="$HOME/.config/gtk-3.0/bookmarks"
        mkdir -p "$HOME/.config/gtk-3.0"
        touch "$bookmarks_file"
        for dir in "$HOME/Notes" "$HOME/work"; do
          [ -d "$dir" ] || continue
          label="$(basename "$dir")"
          entry="file://$dir $label"
          grep -qxF "$entry" "$bookmarks_file" 2>/dev/null || printf '%s\n' "$entry" >> "$bookmarks_file"
          if [ -x "${pkgs.flea}/bin/flea" ]; then
            payload="$(${pkgs.jq}/bin/jq -nc --arg l "$label" --arg p "$dir" '{op:"add",record:{label:$l,path:$p}}')"
            "${pkgs.flea}/bin/flea" --favourites "$payload" >/dev/null 2>&1 || true
          fi
        done
      '';

  xdg.mimeApps = {
    enable = true;
    defaultApplications = {
      "text/html" = [ "helium.desktop" ];
      "x-scheme-handler/http" = [ "helium.desktop" ];
      "x-scheme-handler/https" = [ "helium.desktop" ];
      "x-scheme-handler/about" = [ "helium.desktop" ];
      "x-scheme-handler/unknown" = [ "helium.desktop" ];
      "application/xhtml+xml" = [ "helium.desktop" ];
      "x-scheme-handler/antigravity" = [ "antigravity-hub.desktop" ];
      "x-scheme-handler/bitwarden" = [ "bitwarden.desktop" ];
      "x-scheme-handler/claude" = [ "com.anthropic.Claude.desktop" ];
      "x-scheme-handler/discord" = [ "vesktop.desktop" ];
      "x-scheme-handler/sgnl" = [ "signal.desktop" ];
      "x-scheme-handler/signalcaptcha" = [ "signal.desktop" ];
      "x-scheme-handler/tg" = [ "org.telegram.desktop.desktop" ];
      "x-scheme-handler/tonsite" = [ "org.telegram.desktop.desktop" ];
      "x-scheme-handler/ton" = [ "org.telegram.desktop.desktop" ];
      "application/vnd.anthropic.mcpb" = [ "com.anthropic.Claude.desktop" ];
      "application/vnd.anthropic.skill" = [ "com.anthropic.Claude.desktop" ];
    };
  };

  # Present Vesktop as "Discord" with the official Discord icon in Spotlight & Dock.
  xdg.dataFile = {
    "icons/hicolor/scalable/apps/discord.svg".source = ../assets/discord.svg;
    "applications/vesktop.desktop".text = ''
      [Desktop Entry]
      Version=1.5
      Type=Application
      Name=Discord
      GenericName=Internet Messenger
      Comment=Discord client
      Keywords=discord;vesktop;vencord;electron;chat;voice;
      Exec=vesktop %U
      Icon=${../assets/discord.svg}
      Terminal=false
      StartupWMClass=vesktop
      Categories=Network;InstantMessaging;Chat;
      MimeType=x-scheme-handler/discord;
    '';
    # Slack as a Helium web app: no official Linux arm64 desktop build exists, and
    # `--app` shares the running browser instead of a second Electron runtime.
    "applications/slack.desktop".text = ''
      [Desktop Entry]
      Version=1.5
      Type=Application
      Name=Slack
      GenericName=Internet Messenger
      Comment=Slack web app in Helium
      Keywords=slack;chat;work;huddle;
      Exec=helium --app=https://app.slack.com/client
      Icon=slack
      Terminal=false
      StartupWMClass=helium-app.slack.com__client-Default
      Categories=Network;InstantMessaging;Chat;
    '';
  };

  # Prevent Bitwarden Desktop from silently autostarting a ~190 MiB background Electron
  # instance on login (`gnome-keyring-daemon` owns `org.freedesktop.secrets`, and
  # `x-scheme-handler/bitwarden` / Spotlight still launch Bitwarden on demand).
  xdg.configFile."autostart/bitwarden.desktop".text = ''
    [Desktop Entry]
    Type=Application
    Name=Bitwarden
    Hidden=true
  '';

  # Antigravity IDE editor defaults; user changes in its settings UI always win.
  programs.omarchnix.mutableJson.".config/Antigravity IDE/User/settings.json" = {
    onlyIfExists = ".config/Antigravity IDE";
    defaults = {
      "editor.fontFamily" = "'Lilex Nerd Font', 'Lilex', 'IBM Plex Mono', monospace";
      "editor.fontLigatures" = true;
      "terminal.integrated.fontFamily" = "'Lilex Nerd Font', 'Lilex', 'IBM Plex Mono', monospace";
      "window.autoDetectColorScheme" = true;
      "window.controlsStyle" = "hidden";
    };
  };

  # Antigravity Hub: inherit the system light/dark mode unless the user picked one.
  home.activation.antigravityHubThemeMode = config.lib.dag.entryAfter [ "writeBoundary" ] ''
    ag_hub_cfg="$HOME/.gemini/config/config.json"
    if [ -f "$ag_hub_cfg" ] && [ ! -L "$ag_hub_cfg" ]; then
      cur_ag_mode="$(${pkgs.jq}/bin/jq -r '.userSettings.themeMode // ""' "$ag_hub_cfg" 2>/dev/null || true)"
      if [ -z "$cur_ag_mode" ] || [ "$cur_ag_mode" = "null" ] || [ "$cur_ag_mode" = "0" ] || [ "$cur_ag_mode" = "THEME_MODE_UNSPECIFIED" ]; then
        tmp_hub="$(mktemp "$ag_hub_cfg.XXXXXX")"
        if ${pkgs.jq}/bin/jq '.userSettings = ((.userSettings // {}) | .themeMode = "THEME_MODE_INHERIT")' "$ag_hub_cfg" > "$tmp_hub" 2>/dev/null; then
          chmod 0600 "$tmp_hub"
          mv "$tmp_hub" "$ag_hub_cfg"
        else
          rm -f "$tmp_hub"
        fi
      fi
    fi
  '';

  wayland.windowManager.hyprland.settings = {
    env = lib.mkAfter [
      {
        _args = [
          "QT_QPA_PLATFORMTHEME"
          "xdgdesktopportal"
        ];
      }
      {
        _args = [
          "FOLIO_DEFAULT_NOTES_DIR"
          user.hindsight.notesDir
        ];
      }
      {
        _args = [
          "FOLIO_SYNC_BANK"
          user.hindsight.syncBank
        ];
      }
      {
        _args = [
          "HINDSIGHT_API_URL"
          user.hindsight.apiUrl
        ];
      }
      {
        _args = [
          "HINDSIGHT_BANK_ID"
          user.hindsight.bank
        ];
      }
    ];

    config = {
      general = {
        border_size = 1;
        col = {
          active_border = lib.mkForce starshipSubtle.dark.palette.hyprland_active_border;
          inactive_border = lib.mkForce starshipSubtle.dark.palette.hyprland_inactive_border;
        };
      };
      misc.focus_on_activate = true;
    };
  };
}
