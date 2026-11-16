# Settings every one of Thomas's desktop hosts shares (m2air, vm). Host files
# only add what differs: hardware, input devices, services and packages.
# `omarchnix.profile` comes from the `platform` passed to `mkHost`.
{ inventory, lib, ... }:
let
  inherit (inventory) user;
in
{
  imports = [ ./thomas-user.nix ];

  omarchnix = {
    enable = true;
    inherit (user) defaultTimeZone;
    hardening.enable = true;
    # Secrets are handled by this flake's own sops setup (hosts/m2air/secrets.nix).
    secrets.enable = false;
    desktop = {
      enable = true;
      user = user.username;
      theme = "starship-subtle";
      shellBackend = "omarchy-shell";
      macos.enable = lib.mkDefault true;
    };
  };

  home-manager.users.${user.username} = {
    imports = [ ../../home/profiles/desktop.nix ];
  };
}
