# Full GUI desktop Home Manager profile layered on top of `core.nix` and `programs.omarchnix`.
{
  config,
  inputs,
  lib,
  pkgs,
  ...
}:
let
  user = import ../../inventory/user.nix;
  wallpapers = import ../../inventory/wallpapers.nix inputs.wallpapers;
  starshipSubtle = import ../../themes/starship-subtle { inherit pkgs lib; };
in
{
  imports = [
    ./core.nix
    inputs.folio.homeManagerModules.default
  ];

  programs.folio = {
    enable = true;
    defaultNotesDir = user.hindsight.notesDir;
    hindsight = {
      apiUrl = user.hindsight.apiUrl;
      defaultBank = user.hindsight.bank;
    };
  };

  home.activation.ensureFolioNotesDir = config.lib.dag.entryAfter [ "writeBoundary" ] ''
    mkdir -p "$HOME/Notes"
  '';

  # Seed `~/Pictures/Wallpapers/*.webp` from `inputs.wallpapers` on fresh VMs/workstations
  # without overwriting existing files (preserving `~/Pictures/Wallpapers` git checkout on `m2air`).
  home.activation.seedWallpapersDir = config.lib.dag.entryAfter [ "writeBoundary" ] ''
    mkdir -p "$HOME/Pictures/Wallpapers"
    for wp in "${inputs.wallpapers}"/*.webp; do
      [ -f "$wp" ] || continue
      dest="$HOME/Pictures/Wallpapers/$(basename "$wp")"
      if [ ! -e "$dest" ]; then
        ln -s "$wp" "$dest"
      fi
    done
  '';

  programs.omarchnix = {
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
    BROWSER = "brave";
    QT_QPA_PLATFORMTHEME = "xdgdesktopportal";
  };

  home.activation.personalGtkBookmarks =
    config.lib.dag.entryAfter [ "omarchnixUserDirs" "ensureFolioNotesDir" ]
      ''
        bookmarks_file="$HOME/.config/gtk-3.0/bookmarks"
        mkdir -p "$HOME/.config/gtk-3.0"
        touch "$bookmarks_file"
        for dir in "$HOME/Notes" "$HOME/work"; do
          [ -d "$dir" ] || continue
          entry="file://$dir $(basename "$dir")"
          grep -qxF "$entry" "$bookmarks_file" 2>/dev/null || printf '%s\n' "$entry" >> "$bookmarks_file"
        done
      '';

  xdg.mimeApps = {
    enable = true;
    defaultApplications = {
      "x-scheme-handler/bitwarden" = [ "bitwarden.desktop" ];
      "x-scheme-handler/discord" = [ "vesktop.desktop" ];
      "x-scheme-handler/sgnl" = [ "signal.desktop" ];
      "x-scheme-handler/signalcaptcha" = [ "signal.desktop" ];
      "x-scheme-handler/tg" = [ "org.telegram.desktop.desktop" ];
      "x-scheme-handler/tonsite" = [ "org.telegram.desktop.desktop" ];
      "x-scheme-handler/ton" = [ "org.telegram.desktop.desktop" ];
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

  home.activation.antigravityFontDefaults = config.lib.dag.entryAfter [ "writeBoundary" ] ''
    if [ -d "$HOME/.config/Antigravity IDE" ]; then
      ag_settings_dir="$HOME/.config/Antigravity IDE/User"
      ag_settings="$ag_settings_dir/settings.json"
      mkdir -p "$ag_settings_dir"
      if [ ! -f "$ag_settings" ] || [ -L "$ag_settings" ]; then
        rm -f "$ag_settings"
        printf '{}\n' > "$ag_settings"
      fi
      tmp_ag="$(mktemp "$ag_settings.XXXXXX")"
      if ${pkgs.jq}/bin/jq \
        --arg monoFont ${lib.escapeShellArg "'Lilex Nerd Font', 'Lilex', 'IBM Plex Mono', monospace"} '
        .["editor.fontFamily"] = (.["editor.fontFamily"] // $monoFont)
        | .["editor.fontLigatures"] = (if has("editor.fontLigatures") then .["editor.fontLigatures"] else true end)
        | .["terminal.integrated.fontFamily"] = (.["terminal.integrated.fontFamily"] // $monoFont)
        | .["window.autoDetectColorScheme"] = (if has("window.autoDetectColorScheme") then .["window.autoDetectColorScheme"] else true end)
        | .["window.controlsStyle"] = (.["window.controlsStyle"] // "hidden")
      ' "$ag_settings" > "$tmp_ag" 2>/dev/null; then
        mv "$tmp_ag" "$ag_settings"
        chmod 0644 "$ag_settings"
      else
        rm -f "$tmp_ag"
      fi
    fi

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
