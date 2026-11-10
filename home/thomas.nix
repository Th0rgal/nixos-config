# Thomas's personal Home Manager configuration layered on top of `programs.omarchnix`.
{
  lib,
  ...
}:
let
  inherit (lib.generators) mkLuaInline;
  mkBind = keys: dsp: {
    _args = [
      keys
      (mkLuaInline dsp)
    ];
  };
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

  wayland.windowManager.hyprland.settings.bind = lib.mkAfter [
    # Support both Cmd+W (Omarchy default) and Cmd+Q to close active window,
    # plus Cmd+Shift+Q to force-kill an unresponsive window.
    (mkBind "SUPER + W" "hl.dsp.window.close()")
    (mkBind "SUPER + SHIFT + Q" "hl.dsp.window.kill()")
  ];
}
