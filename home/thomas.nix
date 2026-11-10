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

  grim = "${pkgs.grim}/bin/grim";
  slurp = "${pkgs.slurp}/bin/slurp";
  wlCopy = "${pkgs.wl-clipboard}/bin/wl-copy";
  omarchyShellBin = if cfg.shell.package != null then "${cfg.shell.package}/bin" else "";
  clipboardCmd =
    if omarchyShellBin != "" then
      "${omarchyShellBin}/omarchy-shell shell toggle omarchy.clipboard"
    else
      "omarchy-shell shell toggle omarchy.clipboard";
  idleToggleCmd =
    if omarchyShellBin != "" then
      "${omarchyShellBin}/omarchy-shell idle toggle"
    else
      "omarchy-toggle-idle toggle";

  missionControlId = "io.github.joaovitor2763.mission-control";
  omarchyShellExe = if omarchyShellBin != "" then "${omarchyShellBin}/omarchy-shell" else "omarchy-shell";
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

  # Maccy-equivalent clipboard history icon in the top-right status bar.
  programs.omarchnix.plugins.locked = lib.optionalAttrs (clipboardBarPlugin != null) {
    "omarchnix.clipboard" = {
      package = clipboardBarPlugin;
      enable = true;
      section = "right";
    };
  };

  wayland.windowManager.hyprland.settings = {
    config.input.touchpad = {
      # Invert scroll so content goes up when fingers go up, and smooth speed (0.5x).
      natural_scroll = lib.mkForce false;
      scroll_factor = 0.5;
    };

    bind = lib.mkAfter [
      # Window close (Cmd+W and Cmd+Q) and force-kill (Cmd+Shift+Q)
      (mkBind "SUPER + W" "hl.dsp.window.close()")
      (mkBind "SUPER + SHIFT + Q" "hl.dsp.window.kill()")

      # macOS-style region screenshot directly to clipboard (Ctrl+Cmd+Shift+4 and Cmd+Shift+4)
      (mkBind "CTRL + SUPER + SHIFT + 4" ''hl.dsp.exec_cmd("${grim} -g \"$(${slurp})\" - | ${wlCopy}")'')
      (mkBind "CTRL + SUPER + SHIFT + code:13" ''hl.dsp.exec_cmd("${grim} -g \"$(${slurp})\" - | ${wlCopy}")'')
      (mkBind "SUPER + SHIFT + 4" ''hl.dsp.exec_cmd("${grim} -g \"$(${slurp})\" - | ${wlCopy}")'')
      (mkBind "SUPER + SHIFT + code:13" ''hl.dsp.exec_cmd("${grim} -g \"$(${slurp})\" - | ${wlCopy}")'')

      # Clipboard history overlay (Maccy equivalent: Cmd+Shift+V / Cmd+Ctrl+V)
      (mkBind "SUPER + SHIFT + V" "hl.dsp.exec_cmd(${builtins.toJSON clipboardCmd})")
      (mkBind "SUPER + CTRL + V" "hl.dsp.exec_cmd(${builtins.toJSON clipboardCmd})")

      # Stay Awake / Theine toggle (Cmd+Shift+I)
      (mkBind "SUPER + SHIFT + I" "hl.dsp.exec_cmd(${builtins.toJSON idleToggleCmd})")
    ];
  };
}
