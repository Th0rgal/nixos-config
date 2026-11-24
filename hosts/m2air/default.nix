# MacBook Air M2 — Thomas's personal NixOS host configuration.
# Shared workstation apps, policies, keyring, IME, remote builder, and backups live in
# `hosts/common/workstation.nix`; this file holds only Apple Silicon / laptop specifics.
{
  lib,
  pkgs,
  inventory,
  ...
}:
let
  inherit (inventory) user;
  smartconnectLinux = pkgs.callPackage ../../pkgs/smartconnect-linux.nix { };
in
{
  imports = [
    ../common/workstation.nix
    ./hardware.nix
    ./bench.nix
    ./gnosisvpn.nix
    ./usb-tether.nix
  ];

  networking.hostName = "m2air";

  omarchnix = {
    # Live systemd job tree on the boot console (also answers the LUKS prompt).
    # Roll back from the GRUB menu, or set this to false, if the console misbehaves.
    boot.bootvine.enable = true;
    containers.enable = true;
    services.sharing = {
      airdrop.enable = true;
      quickshare.enable = true;
      nearby.enable = true;
      kdeconnect.enable = true;
    };
    mesh = inventory.meshByHost.m2air or inventory.mesh;
    macTether.enable = true;
    desktop = {
      touchpad = {
        naturalScroll = false;
        scrollFactor = 0.5;
        # macOS default: pressing clicks, a light touch never does.
        tapToClick = false;
      };
      macos.gestures = true;
      autostartTty = {
        enable = true;
        unlockKeyringFromLuks = true;
      };
    };
    hardware.m2air = {
      enable = true;
      chargeControlEndThreshold = 90;
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

  # Motorola Smart Connect (native peer for Razr 60 Ultra: clipboard, notifications,
  # screen/app mirroring, Cross Control, file transfer) replaces KDE Connect Android
  # mirroring while keeping KDE Connect enabled for iOS support.
  environment.systemPackages = [ smartconnectLinux ];

  systemd.user.services.smartconnectd = {
    description = "Motorola Smart Connect Linux daemon (smartconnectd)";
    wantedBy = [ "graphical-session.target" ];
    after = [ "graphical-session.target" ];
    serviceConfig = {
      ExecStart = "${smartconnectLinux}/bin/smartconnectd";
      Restart = "on-failure";
      RestartSec = 3;
    };
  };

  home-manager.users.${user.username} = {
    programs.omarchnix.sharing.kdeconnect.androidMirroring.enable = false;
  };

  # YubiKey PIV SSH agent for the Paloma fleet admin key (`thomas-yubikey-admin`).
  services.yubikey-agent.enable = true;

  # Keep Apple M2 ISO physical `~`/`` ` `` key next to Left Shift (<TLDE>) instead of
  # hid_apple's automatic ISO swap, without altering Stage-1 LUKS passphrase entry.
  # Also ensure `/etc/nixos` points to the live `nixos-config` repo if missing.
  systemd.tmpfiles.rules = [
    "L /etc/nixos - - - - /home/${user.username}/work/nixos/nixos-config"
    "w /sys/module/hid_apple/parameters/iso_layout - - - - 0"
  ];

  users.users.${user.username} = {
    extraGroups = [
      "wheel"
      "video"
      "render"
      "accel"
      "audio"
      "input"
      "networkmanager"
    ];
    openssh.authorizedKeys.keys = user.sshKeys.m2airAuthorized;
  };

  # First-install release of this host's state. Never bump it to "upgrade"; it only
  # changes when the machine is reinstalled.
  system.stateVersion = "25.05";
}
