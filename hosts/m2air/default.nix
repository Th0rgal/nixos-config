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
    desktop = {
      enable = true;
      user = "thomas";
      theme = "starship";
      shellBackend = "omarchy-shell";
      keyboard = {
        layout = "us";
        variant = "intl";
      };
      autostartTty.enable = true;
    };
    hardware.m2air = {
      enable = true;
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

  environment.systemPackages = with pkgs; [
    brave
    firefox
    bitwarden-desktop
    bitwarden-cli
    telegram-desktop
    age
    restic
    rclone
    gh
    orb-desktop
    notion-calendar
    antigravity-hub
  ];

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
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJc0SQKxLSh5OBhIrOp7+YpmSnGVvkO7l/hOaC5Klinx paloma-macbook-air@10.88.0.100"
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOaAvcqsYHGjxyUIb8odmePvJT26SjXl05seGZVAauhQ spark-admin@m2air-debug"
    ];
  };

  security.sudo.wheelNeedsPassword = true;
}
