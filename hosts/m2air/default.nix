# MacBook Air M2 — Thomas's personal NixOS host configuration.
{
  lib,
  pkgs,
  ...
}:
let
  user = import ../../inventory/user.nix;

  # Enable Chromium zero-copy tile rasterizer on Apple Silicon unified memory
  # without re-unpacking the binary-cached Brave derivation.
  braveZeroCopy = pkgs.symlinkJoin {
    name = "brave-${pkgs.brave.version}";
    paths = [ pkgs.brave ];
    nativeBuildInputs = [ pkgs.makeWrapper ];
    postBuild = ''
      wrapProgram "$out/bin/brave" --add-flags "--enable-zero-copy"
      for desktop in "$out/share/applications/"*.desktop; do
        if [ -L "$desktop" ]; then
          cp --remove-destination "$(readlink -f "$desktop")" "$desktop"
          substituteInPlace "$desktop" --replace-quiet "${pkgs.brave}/bin/brave" "$out/bin/brave"
        fi
      done
    '';
    inherit (pkgs.brave) meta;
  };

  # Run Signal Desktop natively on Wayland without Electron's CSD titlebar or
  # Linux menu bar, while keeping fcitx5 dead-key IME and gnome-keyring storage.
  signalFramelessHook = pkgs.writeText "signal-frameless-hook.cjs" ''
    const Module = require("node:module");
    const origLoad = Module._load;
    let WrappedBrowserWindow;

    Module._load = function (request, parent, isMain) {
      const loaded = origLoad.apply(this, arguments);
      if (request === "electron" && loaded && typeof loaded === "object") {
        return new Proxy(loaded, {
          get(target, prop) {
            if (prop === "BrowserWindow") {
              if (!WrappedBrowserWindow) {
                const OrigBrowserWindow = Reflect.get(target, prop, target);
                WrappedBrowserWindow = class FramelessBrowserWindow extends OrigBrowserWindow {
                  constructor(opts = {}) {
                    const nextOpts = { ...opts, frame: false, autoHideMenuBar: true };
                    delete nextOpts.titleBarStyle;
                    super(nextOpts);
                    super.setMenuBarVisibility(false);
                  }
                  setMenuBarVisibility(_visible) {
                    return super.setMenuBarVisibility(false);
                  }
                };
              }
              return WrappedBrowserWindow;
            }
            return Reflect.get(target, prop, target);
          },
        });
      }
      return loaded;
    };
  '';

  signalDesktopWayland = pkgs.symlinkJoin {
    name = "signal-desktop-${pkgs.signal-desktop.version}";
    paths = [ pkgs.signal-desktop ];
    nativeBuildInputs = [ pkgs.makeWrapper ];
    postBuild = ''
      cp --remove-destination "$(readlink -f "$out/bin/signal-desktop")" "$out/bin/signal-desktop"
      chmod 755 "$out/bin/signal-desktop"
      substituteInPlace "$out/bin/signal-desktop" \
        --replace-fail "${pkgs.signal-desktop}/share/signal-desktop/app.asar" \
        "-r ${signalFramelessHook} ${pkgs.signal-desktop}/share/signal-desktop/app.asar"
      wrapProgram "$out/bin/signal-desktop" \
        --add-flags "--password-store=gnome-libsecret" \
        --add-flags "\''${NIXOS_OZONE_WL:+\''${WAYLAND_DISPLAY:+--ozone-platform-hint=auto --disable-features=WaylandWindowDecorations --enable-wayland-ime=true --wayland-text-input-version=3}}"
      for desktop in "$out/share/applications/"*.desktop; do
        if [ -L "$desktop" ]; then
          cp --remove-destination "$(readlink -f "$desktop")" "$desktop"
          substituteInPlace "$desktop" --replace-quiet "Exec=signal-desktop" "Exec=$out/bin/signal-desktop"
        fi
      done
    '';
    inherit (pkgs.signal-desktop) meta;
  };
in
{
  imports = [
    ../common/thomas-user.nix
    ./hardware.nix
  ];

  networking.hostName = "m2air";

  omarchnix = {
    enable = true;
    profile = "asahi";
    defaultTimeZone = user.defaultTimeZone;
    hardening.enable = true;
    containers.enable = true;
    secrets.enable = false;
    services.sharing = {
      airdrop.enable = true;
      quickshare.enable = true;
      nearby.enable = true;
      kdeconnect.enable = true;
    };
    desktop = {
      enable = true;
      user = user.username;
      theme = "starship-subtle";
      shellBackend = "omarchy-shell";
      keyboard = {
        layout = "us";
        variant = "intl";
      };
      touchpad = {
        naturalScroll = false;
        scrollFactor = 0.5;
      };
      murmure.enable = true;
      macos = {
        enable = true;
        gestures = true;
      };
      autostartTty = {
        enable = true;
        unlockKeyringFromLuks = true;
      };
    };
    hardware.m2air = {
      enable = true;
      chargeControlEndThreshold = 100;
      chargeControlStartThreshold = null;
      keyboardBacklight = {
        defaultBrightness = 50;
        enforceOnBoot = false;
      };
      touchId = {
        enable = lib.mkDefault false;
        requirePasswordAfterBoot = true;
        fido.enable = true;
      };
    };
  };

  home-manager.users.${user.username} = import ../../home/profiles/desktop.nix;

  # Google Antigravity ships as a binary Electron app; allow only that unfree
  # package rather than the whole unfree set.
  nixpkgs.config.allowUnfreePackages = [ "antigravity-hub" ];

  environment.systemPackages = with pkgs; [
    braveZeroCopy
    firefox
    bitwarden-desktop
    bitwarden-cli
    telegram-desktop
    signalDesktopWayland
    vesktop
    age
    bun
    restic
    rclone
    gh
    libsecret
    turbo-lean
    murmure
    orb-desktop
    folio-desktop
    notion-calendar
    antigravity-hub
  ];

  # Keep /nix/store and the 476 MiB /boot EFI partition lean automatically.
  nix.gc = {
    automatic = true;
    dates = "weekly";
    options = "--delete-older-than 7d";
  };

  # Secret Service (org.freedesktop.secrets) required by Bitwarden Desktop for
  # biometric unlock alongside Polkit (`polkit-1` PAM -> `pam_fprintd.so`).
  services.gnome.gnome-keyring.enable = true;

  # Keep Apple M2 ISO physical `~`/`` ` `` key next to Left Shift (<TLDE>) instead of
  # hid_apple's automatic ISO swap, without altering Stage-1 LUKS passphrase entry.
  systemd.tmpfiles.rules = [
    "w /sys/module/hid_apple/parameters/iso_layout - - - - 0"
  ];

  # Wayland IME (fcitx5) so dead keys (`~`, `` ` ``, `'`, `"`, `^`) display underlined
  # preedit in foot and Wayland apps, matching macOS U.S. International - PC.
  i18n.inputMethod = {
    enable = true;
    type = "fcitx5";
    fcitx5.waylandFrontend = true;
  };

  users.users.${user.username} = {
    extraGroups = [
      "wheel"
      "video"
      "render"
      "audio"
      "input"
      "networkmanager"
    ];
    openssh.authorizedKeys.keys = user.sshKeys.m2airAuthorized;
  };
}
