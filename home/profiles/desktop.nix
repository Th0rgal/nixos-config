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
    inputs.scribe.homeManagerModules.default
  ];

  programs.scribe = {
    enable = true;
    defaultNotesDir = user.hindsight.notesDir;
    hindsight = {
      apiUrl = user.hindsight.apiUrl;
      bank = user.hindsight.bank;
      notesFolder = user.hindsight.notesFolder;
    };
  };

  home.activation.ensureScribeNotesDir = config.lib.dag.entryAfter [ "writeBoundary" ] ''
    mkdir -p "$HOME/Notes"
    # Clean up legacy Folio symlinks/desktop entries if present.
    rm -f "$HOME/.local/share/folio-app" "$HOME/.local/share/applications/folio.desktop"
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

  # Helium's native new-tab page reads the user's uploaded photo from this file.
  systemd.user.services.helium-wallpaper = {
    Unit.Description = "Match the Helium new-tab wallpaper to the Omarchy theme";
    Service = {
      Type = "oneshot";
      ExecStart = pkgs.writeShellScript "helium-wallpaper" ''
        set -eu
        current="''${XDG_STATE_HOME:-$HOME/.local/state}/omarchy/current/theme/colors.toml"
        profile="''${XDG_CONFIG_HOME:-$HOME/.config}/net.imput.helium/Default"
        [ -f "$current" ] && [ -f "$profile/background.jpg" ] || exit 0
        if ${pkgs.gnugrep}/bin/grep -Eq '^mode[[:space:]]*=[[:space:]]*"light"' "$current"; then
          photo=kazakhstan-charyn-canyon-peaks.webp
        else
          photo=bologna-dome.webp
        fi
        src="$HOME/Pictures/Wallpapers/$photo"
        [ -f "$src" ] || exit 0
        ${pkgs.diffutils}/bin/cmp -s "$src" "$profile/background.jpg" && exit 0
        tmp=$(${pkgs.coreutils}/bin/mktemp "$profile/.background.XXXXXX")
        trap '${pkgs.coreutils}/bin/rm -f "$tmp"' EXIT
        ${pkgs.coreutils}/bin/cp "$src" "$tmp"
        ${pkgs.coreutils}/bin/mv -fT "$tmp" "$profile/background.jpg"
      '';
    };
    Install.WantedBy = [ "default.target" ];
  };

  systemd.user.paths.helium-wallpaper = {
    Unit.Description = "Watch Omarchy theme changes for Helium";
    Path.PathChanged = "%h/.local/state/omarchy/current/theme.generation";
    Install.WantedBy = [ "default.target" ];
  };

  # LaTeX for the Verity report template (pdflatex + latexmk). A hand-picked set on
  # top of scheme-small: about 0.7 GiB of closure against 5 GiB+ for texliveFull.
  # Add a package here when a build stops on "File `x.sty' not found".
  home.packages = [
    (pkgs.texliveSmall.withPackages (ps: [
      ps.latexmk
      ps.cleveref
      ps.tcolorbox
      ps.environ
      ps.tikzfill
      ps.pdfcol
      ps.charter
      ps.titlesec
      ps.xltabular
      ps.ltablex
      ps.enumitem
      ps.cm-super
    ]))
    # PDF, image, and video viewers matching Omarchy's defaults.
    pkgs.evince
    pkgs.imv
    pkgs.mpv
  ];

  programs.omarchnix = {
    cloudMounts = {
      enable = true;
      # ~/Cloud/reMarkable: pair once with `remarkable setup`.
      remarkable.enable = true;
    };
    rbw.enable = true;
    # The LG's EDID reports a wide gamut, so Omarchy colour-converts to it; at
    # 8 bits that warns (and can band). The Asahi DCP drives it at 10-bit fine.
    monitorBitdepth."desc:LG Electronics LG HDR 4K" = 10;

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
      orbSync.enable = false;
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

    sharing.barSection = "tray";

    shell = {
      inherit (user) elsewhenCities;
      disabledPlugins = [ "omarchy.agents" ];
      purgedPluginIds = [ "omarchy.agents" ];
      trayWidgetIds = [
        "omarchy.monitor"
        "omarchy.bluetooth"
      ];
      hiddenTrayIds = [
        "Antigravity_status_icon_1"
        "TelegramDesktop"
      ];
    };

    macos = {
      enable = lib.mkDefault true;
      wallpapers = {
        enable = true;
        inherit (wallpapers) default workspaces variants;
      };
    };
  };

  home.sessionVariables = {
    BROWSER = "helium";
    QT_QPA_PLATFORMTHEME = "xdgdesktopportal";
    SCRIBE_HINDSIGHT_FOLDER = lib.mkDefault user.hindsight.notesFolder;
  };

  home.activation.personalGtkBookmarks =
    config.lib.dag.entryAfter [ "omarchnixUserDirs" "ensureScribeNotesDir" ]
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
      "application/pdf" = [ "org.gnome.Evince.desktop" ];
      "application/epub+zip" = [ "org.gnome.Evince.desktop" ];
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
    }
    // lib.genAttrs [
      "image/avif"
      "image/bmp"
      "image/gif"
      "image/heic"
      "image/heif"
      "image/jpeg"
      "image/jxl"
      "image/png"
      "image/qoi"
      "image/svg+xml"
      "image/tiff"
      "image/tiff-fx"
      "image/webp"
      "image/x-farbfeld"
    ] (_: [ "imv.desktop" ])
    // lib.genAttrs [
      "video/3gpp"
      "video/3gpp2"
      "video/avi"
      "video/mp2t"
      "video/mp4"
      "video/mpeg"
      "video/ogg"
      "video/quicktime"
      "video/webm"
      "video/x-flv"
      "video/x-m4v"
      "video/x-matroska"
      "video/x-ms-wmv"
      "video/x-msvideo"
      "audio/aac"
      "audio/flac"
      "audio/m4a"
      "audio/mp4"
      "audio/mpeg"
      "audio/ogg"
      "audio/opus"
      "audio/wav"
      "audio/webm"
      "audio/x-m4a"
      "audio/x-matroska"
      "audio/x-wav"
    ] (_: [ "mpv.desktop" ]);
  };

  # Present Vesktop as "Discord" with the official Discord icon in Spotlight & Dock,
  # and share the unified SideSystem Orb monolith icon with the Android companion app.
  xdg.dataFile = {
    "icons/hicolor/scalable/apps/discord.svg".source = ../assets/discord.svg;
    "icons/hicolor/scalable/apps/sidesystem.svg".source = ../assets/sidesystem.svg;
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
    # Float Helium's modal extension dialogs and Rabby's transient toplevel
    # popups while letting all Helium windows keep the default desktop
    # rounding and subtle 1px borders.
    window_rule = lib.mkAfter [
      {
        name = "helium-extension-dialogs";
        match = {
          class = "^helium$";
          modal = true;
        };
        float = true;
        center = true;
        stay_focused = true;
      }
      {
        name = "rabby-extension-dialog";
        match.class = "^chrome-acmacodkjbdgmoleebolmdjonilkdbch-.*$";
        float = true;
        move = [
          "(monitor_w-window_w-24)"
          "72"
        ];
        size = "400 660";
        stay_focused = true;
        no_blur = true;
      }
    ];

    env = lib.mkAfter [
      {
        _args = [
          "QT_QPA_PLATFORMTHEME"
          "xdgdesktopportal"
        ];
      }
      {
        _args = [
          "SCRIBE_DEFAULT_NOTES_DIR"
          user.hindsight.notesDir
        ];
      }
      {
        _args = [
          "SCRIBE_HINDSIGHT_FOLDER"
          user.hindsight.notesFolder
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
