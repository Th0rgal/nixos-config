# Fallback Fcitx5 + macOS "U.S. International - PC" compose configuration used
# whenever the locked `omarchnix` input does not yet provide `programs.omarchnix.ime`.
{
  config,
  lib,
  options,
  pkgs,
  ...
}:
let
  cfg = config.programs.omarchnix;
  hasOmarchnixIme = options.programs.omarchnix ? ime;
  fcitx5Pkg = pkgs.qt6Packages.fcitx5-with-addons;
  groupLayout =
    if cfg.keyboard.variant != "" then
      "${cfg.keyboard.layout}-${cfg.keyboard.variant}"
    else
      cfg.keyboard.layout;

  escapeXCompose = builtins.replaceStrings [ "\\" "\"" ] [ "\\\\" "\\\"" ];

  asciiAlphaNum = map (c: {
    char = c;
    sym = c;
  }) (lib.stringToCharacters "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");

  asciiPunct = [
    {
      char = "!";
      sym = "exclam";
    }
    {
      char = "\"";
      sym = "quotedbl";
    }
    {
      char = "#";
      sym = "numbersign";
    }
    {
      char = "$";
      sym = "dollar";
    }
    {
      char = "%";
      sym = "percent";
    }
    {
      char = "&";
      sym = "ampersand";
    }
    {
      char = "'";
      sym = "apostrophe";
    }
    {
      char = "(";
      sym = "parenleft";
    }
    {
      char = ")";
      sym = "parenright";
    }
    {
      char = "*";
      sym = "asterisk";
    }
    {
      char = "+";
      sym = "plus";
    }
    {
      char = ",";
      sym = "comma";
    }
    {
      char = "-";
      sym = "minus";
    }
    {
      char = ".";
      sym = "period";
    }
    {
      char = "/";
      sym = "slash";
    }
    {
      char = ":";
      sym = "colon";
    }
    {
      char = ";";
      sym = "semicolon";
    }
    {
      char = "<";
      sym = "less";
    }
    {
      char = "=";
      sym = "equal";
    }
    {
      char = ">";
      sym = "greater";
    }
    {
      char = "?";
      sym = "question";
    }
    {
      char = "@";
      sym = "at";
    }
    {
      char = "[";
      sym = "bracketleft";
    }
    {
      char = "\\";
      sym = "backslash";
    }
    {
      char = "]";
      sym = "bracketright";
    }
    {
      char = "^";
      sym = "asciicircum";
    }
    {
      char = "_";
      sym = "underscore";
    }
    {
      char = "`";
      sym = "grave";
    }
    {
      char = "{";
      sym = "braceleft";
    }
    {
      char = "|";
      sym = "bar";
    }
    {
      char = "}";
      sym = "braceright";
    }
    {
      char = "~";
      sym = "asciitilde";
    }
  ];

  keypadSyms =
    (map (d: {
      char = d;
      sym = "KP_${d}";
    }) (lib.stringToCharacters "0123456789"))
    ++ [
      {
        char = "/";
        sym = "KP_Divide";
      }
      {
        char = "*";
        sym = "KP_Multiply";
      }
      {
        char = "-";
        sym = "KP_Subtract";
      }
      {
        char = "+";
        sym = "KP_Add";
      }
      {
        char = ".";
        sym = "KP_Decimal";
      }
    ];

  deadKeySpecs = [
    {
      dead = "dead_tilde";
      ascii = "~";
      accents = {
        a = "ã";
        A = "Ã";
        n = "ñ";
        N = "Ñ";
        o = "õ";
        O = "Õ";
      };
    }
    {
      dead = "dead_grave";
      ascii = "`";
      accents = {
        a = "à";
        A = "À";
        e = "è";
        E = "È";
        i = "ì";
        I = "Ì";
        o = "ò";
        O = "Ò";
        u = "ù";
        U = "Ù";
      };
    }
    {
      dead = "dead_acute";
      ascii = "'";
      accents = {
        a = "á";
        A = "Á";
        e = "é";
        E = "É";
        i = "í";
        I = "Í";
        o = "ó";
        O = "Ó";
        u = "ú";
        U = "Ú";
        y = "ý";
        Y = "Ý";
        c = "ç";
        C = "Ç";
      };
    }
    {
      dead = "dead_diaeresis";
      ascii = "\"";
      accents = {
        a = "ä";
        A = "Ä";
        e = "ë";
        E = "Ë";
        i = "ï";
        I = "Ï";
        o = "ö";
        O = "Ö";
        u = "ü";
        U = "Ü";
        y = "ÿ";
        Y = "Ÿ";
      };
    }
    {
      dead = "dead_circumflex";
      ascii = "^";
      accents = {
        a = "â";
        A = "Â";
        e = "ê";
        E = "Ê";
        i = "î";
        I = "Î";
        o = "ô";
        O = "Ô";
        u = "û";
        U = "Û";
      };
    }
  ];

  allSecondKeys =
    asciiAlphaNum
    ++ asciiPunct
    ++ keypadSyms
    ++ (map (s: {
      char = s.ascii;
      sym = s.dead;
    }) deadKeySpecs);

  macosUsIntlXCompose = pkgs.writeText "omarchnix-macos-us-intl-XCompose" (
    "# macOS \"U.S. International - PC\" dead-key compose table (managed by omarchnix)\n"
    + lib.concatMapStrings (
      spec:
      "\n# ${spec.dead} (${spec.ascii})\n"
      + "<${spec.dead}> <space> : \"${escapeXCompose spec.ascii}\"\n"
      + lib.concatMapStrings (
        k:
        let
          out = spec.accents.${k.sym} or "${spec.ascii}${k.char}";
        in
        "<${spec.dead}> <${k.sym}> : \"${escapeXCompose out}\"\n"
      ) allSecondKeys
    ) deadKeySpecs
  );

  fcitx5ProfileText = pkgs.writeText "omarchnix-fcitx5-profile" ''
    [Groups/0]
    Name=Default
    Default Layout=${groupLayout}
    DefaultIM=keyboard-${groupLayout}

    [Groups/0/Items/0]
    Name=keyboard-${groupLayout}
    Layout=

    [GroupOrder]
    0=Default
  '';

  fcitx5ConfigText = pkgs.writeText "omarchnix-fcitx5-config" ''
    # Leave Ctrl+Space to shells/tmux and Super keys to Hyprland/Omarchy.
    [Hotkey/TriggerKeys]
    0=Zenkaku_Hankaku
    1=Hangul

    [Hotkey/ActivateKeys]
    0=Henkan

    [Hotkey/DeactivateKeys]
    0=Muhenkan

    [Hotkey/AltTriggerKeys]

    [Hotkey/EnumerateGroupForwardKeys]

    [Hotkey/EnumerateGroupBackwardKeys]

    [Behavior]
    ActiveByDefault=False
    PreeditEnabledByDefault=True
    ShowInputMethodInformation=False
    ShowFirstInputMethodInformation=False
    showInputMethodInformationWhenFocusIn=False
  '';

  fcitx5XkbConf = pkgs.writeText "omarchnix-fcitx5-xkb.conf" ''
    Allow Overriding System XKB Settings=False
  '';

  fcitx5WaylandImConf = pkgs.writeText "omarchnix-fcitx5-waylandim.conf" ''
    DetectApplication=True
    PreferKeyEvent=True
    PersistentVirtualKeyboard=True
  '';

  fcitx5KeyboardConf = pkgs.writeText "omarchnix-fcitx5-keyboard.conf" ''
    EnableEmoji=False
    EnableQuickPhraseEmoji=False
    EnableHintByDefault=False
    UseNewComposeBehavior=True
    EnableLongPress=False
  '';
in
{
  config = lib.mkIf (!hasOmarchnixIme) {
    home.packages = [ fcitx5Pkg ];

    home.sessionVariables = {
      XMODIFIERS = lib.mkDefault "@im=fcitx";
      SDL_IM_MODULE = lib.mkDefault "fcitx";
    };

    gtk = {
      gtk3.extraConfig.gtk-im-module = lib.mkDefault "fcitx";
      gtk4.extraConfig.gtk-im-module = lib.mkDefault "fcitx";
    };

    home.file.".XCompose" = lib.mkIf (cfg.keyboard.layout == "us" && cfg.keyboard.variant == "intl") {
      source = macosUsIntlXCompose;
    };

    home.activation.omarchnixFcitx5 = config.lib.dag.entryAfter [ "writeBoundary" ] ''
      fcitx_dir="$HOME/.config/fcitx5"
      mkdir -p "$fcitx_dir/conf"
      cp --remove-destination --no-preserve=mode "${fcitx5ProfileText}" "$fcitx_dir/profile"
      cp --remove-destination --no-preserve=mode "${fcitx5ConfigText}" "$fcitx_dir/config"
      cp --remove-destination --no-preserve=mode "${fcitx5XkbConf}" "$fcitx_dir/conf/wayland.conf"
      cp --remove-destination --no-preserve=mode "${fcitx5XkbConf}" "$fcitx_dir/conf/xcb.conf"
      cp --remove-destination --no-preserve=mode "${fcitx5WaylandImConf}" "$fcitx_dir/conf/waylandim.conf"
      cp --remove-destination --no-preserve=mode "${fcitx5KeyboardConf}" "$fcitx_dir/conf/keyboard.conf"
      chmod 0644 \
        "$fcitx_dir/profile" \
        "$fcitx_dir/config" \
        "$fcitx_dir/conf/wayland.conf" \
        "$fcitx_dir/conf/xcb.conf" \
        "$fcitx_dir/conf/waylandim.conf" \
        "$fcitx_dir/conf/keyboard.conf"
    '';

    systemd.user.services.omarchy-fcitx5 = {
      Unit = {
        Description = "Fcitx5 input method and dead-key compose preedit";
        PartOf = [ "graphical-session.target" ];
        After = [ "graphical-session.target" ];
        ConditionEnvironment = "WAYLAND_DISPLAY";
      };
      Service = {
        Type = "dbus";
        BusName = "org.fcitx.Fcitx5";
        ExecStart = "${fcitx5Pkg}/bin/fcitx5 --replace --disable notificationitem";
        Restart = "always";
        RestartSec = 2;
      };
      Install = {
        WantedBy = [ "graphical-session.target" ];
      };
    };
  };
}
