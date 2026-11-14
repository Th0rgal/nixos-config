# Turnkey UEFI / QEMU desktop VM configuration (`aarch64-linux` or `x86_64-linux`)
# with the full Omarchnix macOS desktop suite and `Th0rgal/wallpapers` preloaded.
{
  pkgs,
  inventory,
  ...
}:
let
  inherit (inventory) user;
in
{
  imports = [
    ../common/desktop.nix
  ];

  networking.hostName = "omarchnix-vm";

  omarchnix = {
    # Live systemd job tree on the boot console (also answers the LUKS prompt).
    # Roll back from the GRUB menu, or set this to false, if the console misbehaves.
    boot.bootvine.enable = true;
    containers.enable = false;
    desktop = {
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

  # First-install release of this user's home state; never bump it to "upgrade".
  home-manager.users.${user.username}.home.stateVersion = "25.05";

  # First-install release of this host's state. Never bump it to "upgrade"; it only
  # changes when the machine is reinstalled.
  system.stateVersion = "25.05";

  environment.systemPackages = with pkgs; [
    helium
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
