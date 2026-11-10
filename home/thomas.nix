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

  missionControlPlugin =
    if (pkgs ? omarchnix) && (pkgs.omarchnix ? buildPlugin) then
      pkgs.omarchnix.buildPlugin {
        src = pkgs.fetchFromGitHub {
          owner = "joaovitor2763";
          repo = "omarchy-mission-control";
          rev = "86c3d652408fd0a84ec84e6f0e179647952611db";
          hash = "sha256-a4teoN7+IPaFw1xcYlbO3p2G/GyHghtQcERYT+pwotc=";
        };
      }
    else
      null;

  clipboardBarPlugin =
    if (pkgs ? omarchnix) && (pkgs.omarchnix ? buildPlugin) then
      pkgs.omarchnix.buildPlugin {
        src = pkgs.runCommand "omarchnix-clipboard-bar-src" { } ''
          mkdir -p "$out"
          cat > "$out/manifest.json" <<'EOF'
          {
            "schemaVersion": 1,
            "id": "omarchnix.clipboard",
            "name": "Clipboard History",
            "version": "1.0.0",
            "author": "Omarchnix",
            "license": "MIT",
            "description": "Top status bar button for Omarchy clipboard history (Maccy equivalent).",
            "kinds": [
              "bar-widget"
            ],
            "entryPoints": {
              "barWidget": "Widget.qml"
            },
            "barWidget": {
              "displayName": "Clipboard",
              "description": "Open clipboard history (Cmd+Shift+V / Cmd+Ctrl+V).",
              "defaultSection": "right"
            }
          }
          EOF
          cat > "$out/Widget.qml" <<'EOF'
          import QtQuick
          import Quickshell
          import qs.Commons
          import qs.Ui

          BarWidget {
            id: root
            moduleName: "omarchnix.clipboard"

            implicitWidth: button.implicitWidth
            implicitHeight: button.implicitHeight

            BarIconButton {
              id: button
              anchors.fill: parent
              bar: root.bar
              text: "󰅌"
              slotSize: Style.bar.statusSlot
              tooltipText: "Clipboard History (Cmd+Shift+V)"
              onPressed: function() {
                if (root.bar) root.bar.run("omarchy-shell shell toggle omarchy.clipboard")
              }
            }
          }
          EOF
        '';
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

  xdg.mimeApps = {
    enable = true;
    defaultApplications = {
      "x-scheme-handler/bitwarden" = [ "bitwarden.desktop" ];
      "x-scheme-handler/tg" = [ "org.telegram.desktop.desktop" ];
      "x-scheme-handler/tonsite" = [ "org.telegram.desktop.desktop" ];
      "x-scheme-handler/ton" = [ "org.telegram.desktop.desktop" ];
    };
  };

  # Maccy-equivalent clipboard history icon in the top-right status bar
  # + macOS-style Mission Control overlay with top Spaces strip.
  programs.omarchnix.plugins.locked =
    lib.optionalAttrs (clipboardBarPlugin != null) {
      "omarchnix.clipboard" = {
        package = clipboardBarPlugin;
        enable = true;
        section = "right";
      };
    }
    // lib.optionalAttrs (missionControlPlugin != null) {
      ${missionControlId} = {
        package = missionControlPlugin;
        enable = true;
      };
    };

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
      # macOS Mission Control: 3-finger swipe up opens, 3-finger swipe down closes.
      {
        fingers = 3;
        direction = "up";
        disable_inhibit = true;
        action = mkLuaInline "function() hl.dispatch(hl.dsp.exec_cmd(${builtins.toJSON mcSummonCmd})) end";
      }
      {
        fingers = 3;
        direction = "down";
        disable_inhibit = true;
        action = mkLuaInline "function() hl.dispatch(hl.dsp.exec_cmd(${builtins.toJSON mcHideCmd})) end";
      }
    ];

    bind = lib.mkAfter [
      # macOS Mission Control shortcuts (Ctrl+Up toggles, Ctrl+Down closes)
      (mkBind "CTRL + up" "hl.dsp.exec_cmd(${builtins.toJSON mcToggleCmd})")
      (mkBind "CTRL + down" "hl.dsp.exec_cmd(${builtins.toJSON mcHideCmd})")
    ];
  };
}
