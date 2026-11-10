# Thomas's personal Home Manager configuration layered on top of `programs.omarchnix`.
{
  config,
  lib,
  pkgs,
  ...
}:
let
  cfg = config.programs.omarchnix;
  inherit (lib.generators) mkLuaInline;
  mkBind = keys: dsp: {
    _args = [
      keys
      (mkLuaInline dsp)
    ];
  };

  omarchyShellBin = if cfg.shell.package != null then "${cfg.shell.package}/bin" else "";

  missionControlId = "io.github.joaovitor2763.mission-control";
  omarchyShellExe =
    if omarchyShellBin != "" then "${omarchyShellBin}/omarchy-shell" else "omarchy-shell";
  mcSummonCmd = "${omarchyShellExe} shell summon ${missionControlId} '{}'";
  mcHideCmd = "${omarchyShellExe} shell hide ${missionControlId}";
  mcToggleCmd = "${omarchyShellExe} shell toggle ${missionControlId} '{}'";

  upstreamMissionControlSrc = pkgs.fetchFromGitHub {
    owner = "joaovitor2763";
    repo = "omarchy-mission-control";
    rev = "86c3d652408fd0a84ec84e6f0e179647952611db";
    hash = "sha256-a4teoN7+IPaFw1xcYlbO3p2G/GyHghtQcERYT+pwotc=";
  };

  missionControlSrc = pkgs.runCommand "omarchy-mission-control-src" { } ''
    mkdir -p "$out"
    cp -r --no-preserve=mode,ownership "${upstreamMissionControlSrc}/." "$out/"
    cp --no-preserve=mode,ownership "${./mission-control}/"*.qml "$out/"
  '';

  missionControlPlugin =
    if (pkgs ? omarchnix) && (pkgs.omarchnix ? buildPlugin) then
      pkgs.omarchnix.buildPlugin {
        src = missionControlSrc;
      }
    else
      null;

  macosDockId = "th0rgal.macos-dock";

  macosDockPlugin =
    if (pkgs ? omarchnix) && (pkgs.omarchnix ? buildPlugin) then
      pkgs.omarchnix.buildPlugin {
        src = ./macos-dock;
      }
    else
      null;
in
{
  programs.git = {
    enable = true;
    settings = {
      user = {
        name = "Thomas Marchand";
        email = "thomas@lfglabs.dev";
      };
      init.defaultBranch = "main";
      pull.rebase = false;
    };
  };

  home.sessionVariables = {
    BROWSER = "brave";
  };

  programs.fastfetch.enable = true;

  xdg.mimeApps = {
    enable = true;
    defaultApplications = {
      "x-scheme-handler/bitwarden" = [ "bitwarden.desktop" ];
      "x-scheme-handler/tg" = [ "org.telegram.desktop.desktop" ];
      "x-scheme-handler/tonsite" = [ "org.telegram.desktop.desktop" ];
      "x-scheme-handler/ton" = [ "org.telegram.desktop.desktop" ];
    };
  };

  # macOS-style Mission Control overlay with top Spaces strip + auto-hide bottom Dock.
  programs.omarchnix.plugins.locked =
    lib.optionalAttrs (missionControlPlugin != null) {
      ${missionControlId} = {
        package = missionControlPlugin;
        enable = true;
      };
    }
    // lib.optionalAttrs (macosDockPlugin != null) {
      ${macosDockId} = {
        package = macosDockPlugin;
        enable = true;
      };
    };

  programs.omarchnix.shell.extraSettings = lib.mkIf (macosDockPlugin != null) {
    disabledPlugins = [ "omarchy.dock" ];
    dock = {
      position = "bottom";
      autohide = true;
      allWorkspaces = true;
    };
  };

  home.activation.macosDockShellConfig = lib.mkIf (macosDockPlugin != null) (
    config.lib.dag.entryAfter [ "omarchnixStateAndPlugins" ] ''
      if [ -f "$HOME/.config/omarchy/shell.json" ] && [ ! -L "$HOME/.config/omarchy/shell.json" ]; then
        tmp_json="$(mktemp "$HOME/.config/omarchy/shell.json.XXXXXX")"
        ${pkgs.jq}/bin/jq '
          .version = 1
          | .disabledPlugins = ((.disabledPlugins // []) | if index("omarchy.dock") then . else . + ["omarchy.dock"] end)
          | .dock = ((.dock // {}) | .position = (.position // "bottom") | .autohide = (if has("autohide") then .autohide else true end) | .allWorkspaces = (.allWorkspaces // true))
        ' "$HOME/.config/omarchy/shell.json" > "$tmp_json" && mv "$tmp_json" "$HOME/.config/omarchy/shell.json"
        chmod 0644 "$HOME/.config/omarchy/shell.json"
      fi
    ''
  );

  wayland.windowManager.hyprland.settings = {
    config = {
      input.touchpad = {
        # Invert scroll so content goes up when fingers go up, and smooth speed (0.5x).
        natural_scroll = lib.mkForce false;
        scroll_factor = 0.5;
      };
      misc = {
        focus_on_activate = true;
      };
    };

    layer_rule = lib.mkAfter [
      {
        name = "omarchy-mission-control";
        match = {
          namespace = "^omarchy-mission-control$";
        };
        no_anim = true;
        animation = "none";
      }
    ];

    gesture = lib.mkAfter [
      # macOS Mission Control: 3-finger swipe up opens immediately on gesture start,
      # 3-finger swipe down closes immediately on gesture start.
      {
        fingers = 3;
        direction = "up";
        scale = 1.5;
        disable_inhibit = true;
        action = {
          start = mkLuaInline "function() hl.dispatch(hl.dsp.exec_cmd(${builtins.toJSON mcSummonCmd})) end";
        };
      }
      {
        fingers = 3;
        direction = "down";
        scale = 1.5;
        disable_inhibit = true;
        action = {
          start = mkLuaInline "function() hl.dispatch(hl.dsp.exec_cmd(${builtins.toJSON mcHideCmd})) end";
        };
      }
    ];

    bind = lib.mkAfter [
      # macOS Mission Control shortcuts (Ctrl+Up toggles, Ctrl+Down closes)
      (mkBind "CTRL + up" "hl.dsp.exec_cmd(${builtins.toJSON mcToggleCmd})")
      (mkBind "CTRL + down" "hl.dsp.exec_cmd(${builtins.toJSON mcHideCmd})")
    ];
  };
}
