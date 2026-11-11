# NVIDIA Grace / DGX Spark — Thomas's ARM64 workstation & server configuration.
{ ... }:
let
  user = import ../../inventory/user.nix;
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

  home-manager.users.${user.username} = import ../../home/profiles/desktop.nix;

  fileSystems."/" = {
    device = "/dev/disk/by-label/omarchnix-root";
    fsType = "ext4";
  };

  fileSystems."/boot" = {
    device = "/dev/disk/by-label/ESP";
    fsType = "vfat";
  };
}
