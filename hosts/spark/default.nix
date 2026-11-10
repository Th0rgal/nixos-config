# NVIDIA Grace / DGX Spark — Thomas's ARM64 workstation & server configuration.
{ ... }:
{
  networking.hostName = "spark";

  omarchnix = {
    enable = true;
    profile = "spark";
    hardening.enable = true;
    containers.enable = true;
    secrets.enable = false;
    desktop = {
      enable = true;
      user = "thomas";
      theme = "starship";
      shellBackend = "omarchy-shell";
    };
  };

  home-manager.users.thomas = import ../../home/thomas.nix;

  fileSystems."/" = {
    device = "/dev/disk/by-label/omarchnix-root";
    fsType = "ext4";
  };

  fileSystems."/boot" = {
    device = "/dev/disk/by-label/ESP";
    fsType = "vfat";
  };

  users.users.thomas = {
    isNormalUser = true;
    uid = 1000;
    extraGroups = [
      "wheel"
      "video"
      "render"
      "audio"
      "networkmanager"
    ];
    shell = "/run/current-system/sw/bin/zsh";
    openssh.authorizedKeys.keys = [
      "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBE+u67Yyt9LJzvAPclO77RanVelzbQCFVxBn1JNRbqDUOaxpi2zNjarsektqkqeRgE1f6SjQErlg14+cPahyJdI= thomas-yubikey-admin"
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAtlSyHA8nrrj3Uv+MaylUxp8VvgTxE6PQUOzkG4AnHt thomas-breakglass"
    ];
  };

  security.sudo.wheelNeedsPassword = true;
}
