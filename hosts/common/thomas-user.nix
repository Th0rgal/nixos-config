{ lib, ... }:
let
  user = import ../../inventory/user.nix;
in
{
  users.users.${user.username} = {
    isNormalUser = true;
    uid = 1000;
    extraGroups = lib.mkDefault [
      "wheel"
      "video"
      "render"
      "audio"
      "networkmanager"
    ];
    shell = "/run/current-system/sw/bin/zsh";
    openssh.authorizedKeys.keys = lib.mkDefault user.sshKeys.admin;
  };

  security.sudo.wheelNeedsPassword = lib.mkDefault true;
}
