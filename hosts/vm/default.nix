# Turnkey UEFI / QEMU desktop VM configuration (`aarch64-linux` or `x86_64-linux`)
# with the full Omarchnix macOS desktop suite and `Th0rgal/wallpapers` preloaded.
{
  pkgs,
  ...
}:
let
  user = import ../../inventory/user.nix;
in
{
  imports = [
    ../common/thomas-user.nix
  ];

  networking.hostName = "omarchnix-vm";

  omarchnix = {
    enable = true;
    profile = "vm";
    defaultTimeZone = user.defaultTimeZone;
    hardening.enable = true;
    containers.enable = false;
    secrets.enable = false;
    desktop = {
      enable = true;
      user = user.username;
      theme = "starship-subtle";
      shellBackend = "omarchy-shell";
      keyboard = {
        layout = "us";
        variant = "intl";
      };
      macos = {
        enable = true;
        gestures = false;
        touchIdHud = false;
      };
      autostartTty = {
        enable = true;
        unlockKeyringFromLuks = false;
      };
    };
  };

  home-manager.users.${user.username} = import ../../home/profiles/desktop.nix;

  environment.systemPackages = with pkgs; [
    brave
    gh
    bun
    turbo-lean
    orb-desktop
    folio-desktop
  ];

  fileSystems."/" = {
    device = "/dev/disk/by-label/nixos";
    fsType = "ext4";
  };

  fileSystems."/boot" = {
    device = "/dev/disk/by-label/ESP";
    fsType = "vfat";
  };

  virtualisation.vmVariant = {
    virtualisation = {
      memorySize = 4096;
      cores = 4;
      graphics = true;
    };
  };
}
