# NVIDIA Grace / DGX Spark — Thomas's ARM64 workstation & server configuration.
{ inventory, ... }:
let
  user = inventory.user;
in
{
  imports = [
    ../common/thomas-user.nix
  ];

  networking.hostName = "spark";

  omarchnix = {
    enable = true;
    profile = "spark";
    defaultTimeZone = user.defaultTimeZone;
    hardening.enable = true;
    containers.enable = true;
    secrets.enable = false;
    desktop = {
      enable = true;
      user = user.username;
      theme = "starship-subtle";
      shellBackend = "omarchy-shell";
      macos = {
        enable = true;
        gestures = false;
        touchIdHud = false;
      };
    };
  };

  home-manager.users.${user.username} = {
    imports = [ ../../home/profiles/desktop.nix ];
    # First-install release of this user's home state; never bump it to "upgrade".
    home.stateVersion = "25.05";
  };

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
