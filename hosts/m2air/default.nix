# MacBook Air M2 — Thomas's personal NixOS host configuration.
{
  lib,
  pkgs,
  ...
}:
{
  imports = [
    ./hardware.nix
  ];

  networking.hostName = "m2air";

  omarchnix = {
    enable = true;
    profile = "asahi";
    hardening.enable = true;
    containers.enable = true;
    secrets.enable = false;
    services.sharing = {
      airdrop.enable = true;
      quickshare.enable = true;
      nearby.enable = true;
    };
    desktop = {
      enable = true;
      user = "thomas";
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
      autostartTty.enable = true;
    };
    hardware.m2air = {
      enable = true;
      chargeControlEndThreshold = 100;
      chargeControlStartThreshold = null;
      touchId = {
        enable = lib.mkDefault false;
        requirePasswordAfterBoot = true;
        fido.enable = true;
      };
    };
  };

  home-manager.users.thomas = import ../../home/thomas.nix;

  # Google Antigravity ships as a binary Electron app; allow only that unfree
  # package rather than the whole unfree set.
  nixpkgs.config.allowUnfreePackages = [ "antigravity-hub" ];

  environment.systemPackages =
    let
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
    in
    with pkgs;
    [
      braveZeroCopy
      firefox
      bitwarden-desktop
      bitwarden-cli
      telegram-desktop
      age
      restic
      rclone
      gh
      libsecret
      turbo-lean
      murmure
      orb-desktop
      notion-calendar
      antigravity-hub
    ];

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

  users.users.thomas = {
    isNormalUser = true;
    uid = 1000;
    extraGroups = [
      "wheel"
      "video"
      "render"
      "audio"
      "input"
      "networkmanager"
    ];
    shell = "/run/current-system/sw/bin/zsh";
    openssh.authorizedKeys.keys = [
      "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBE+u67Yyt9LJzvAPclO77RanVelzbQCFVxBn1JNRbqDUOaxpi2zNjarsektqkqeRgE1f6SjQErlg14+cPahyJdI= thomas-yubikey-admin"
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAtlSyHA8nrrj3Uv+MaylUxp8VvgTxE6PQUOzkG4AnHt thomas-breakglass"
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJc0SQKxLSh5OBhIrOp7+YpmSnGVvkO7l/hOaC5Klinx paloma-macbook-air@10.88.0.100"
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOaAvcqsYHGjxyUIb8odmePvJT26SjXl05seGZVAauhQ spark-admin@m2air-debug"
    ];
  };

  security.sudo.wheelNeedsPassword = true;
}
