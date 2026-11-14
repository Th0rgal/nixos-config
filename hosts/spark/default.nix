# NVIDIA Grace / DGX Spark — Thomas's ARM64 workstation & server configuration.
{ inventory, ... }:
let
  inherit (inventory) user;
in
{
  imports = [
    ../common/desktop.nix
  ];

  networking.hostName = "spark";

  omarchnix = {
    containers.enable = true;
    desktop = {
      macos = {
        enable = true;
        gestures = false;
        touchIdHud = false;
      };
    };
  };

  # First-install release of this user's home state; never bump it to "upgrade".
  home-manager.users.${user.username}.home.stateVersion = "25.05";

  # First-install release of this host's state. Never bump it to "upgrade"; it only
  # changes when the machine is reinstalled.
  system.stateVersion = "25.05";

  fileSystems."/" = {
    device = "/dev/disk/by-label/omarchnix-root";
    fsType = "ext4";
  };

  fileSystems."/boot" = {
    device = "/dev/disk/by-label/ESP";
    fsType = "vfat";
  };
}
