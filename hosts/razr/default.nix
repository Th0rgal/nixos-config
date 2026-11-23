# Motorola Razr 60 Ultra — full Omarchnix desktop running inside an Android
# Linux container (`aarch64-linux`) sharing the Android host network namespace.
{
  lib,
  modulesPath,
  pkgs,
  inventory,
  ...
}:
let
  inherit (inventory) user;
in
{
  imports = [
    (modulesPath + "/virtualisation/lxc-container.nix")
    ../common/desktop.nix
    ./secrets.nix
  ];

  networking = {
    hostName = "razr";
    # Android owns the shared host network namespace and interfaces; it does not
    # provide `/etc/resolv.conf`, so configure static fallback DNS directly.
    useHostResolvConf = false;
    resolvconf.enable = false;
    nameservers = [
      "1.1.1.1"
      "9.9.9.9"
    ];
    useDHCP = false;
    firewall.enable = false;
  };

  services.openssh = {
    enable = true;
    startWhenNeeded = false;
    ports = [ 2222 ];
    settings = {
      PasswordAuthentication = false;
      PermitRootLogin = "no";
      # Pubkey-only SSH inside an Android container avoids pam_unix/unix_chkpwd
      # failures under Android SELinux.
      UsePAM = false;
    };
  };

  # Android SELinux denies `associate` on unlabeled `ramfs`; use `tmpfs` for sops-nix.
  sops.useTmpfs = true;

  omarchnix = {
    # Android owns the host firewall, AppArmor/SELinux policy, and kernel sysctls.
    hardening.enable = lib.mkForce false;
    containers.enable = false;
    desktop = {
      keyboard = {
        layout = "us";
        variant = "intl";
      };
      macos = {
        enable = true;
        touchIdHud = false;
        gestures = false;
      };
    };
  };

  # Map Android supplementary GIDs so user processes have native access to
  # paranoid networking (`aid_inet`) and GPU/input nodes (`/dev/kgsl-3d0`, `/dev/dri`).
  users.groups = {
    aid_graphics.gid = 1003;
    aid_input.gid = 1004;
    aid_inet.gid = 3003;
  };

  users.users.${user.username} = {
    # Non-loginable password hash (`*`) satisfies PAM account checks for SSH pubkey
    # logins without setting a plaintext password (`!` causes pam_unix to reject).
    hashedPassword = "*";
    extraGroups = [
      "wheel"
      "video"
      "render"
      "audio"
      "input"
      "aid_inet"
      "aid_graphics"
      "aid_input"
    ];
  };

  home-manager.users.${user.username} = {
    programs.omarchnix.macos.externalDisplayKeepalive.enable = false;
    # First-install release of this user's home state; never bump it to "upgrade".
    home.stateVersion = "25.05";
  };

  # Android's tmpfs does not support `security.capability` xattrs, which causes
  # `suid-sgid-wrappers.service` to abort before creating `unix_chkpwd`/`sudo`.
  # Fall back to SUID for capability-bearing wrappers so every wrapper works cleanly.
  security.wrappers = {
    nethogs = {
      capabilities = lib.mkForce "";
      setuid = lib.mkForce true;
    };
    newgidmap = {
      capabilities = lib.mkForce "";
      setuid = lib.mkForce true;
    };
    newuidmap = {
      capabilities = lib.mkForce "";
      setuid = lib.mkForce true;
    };
  };

  # First-install release of this host's state. Never bump it to "upgrade"; it only
  # changes when the machine is reinstalled.
  system.stateVersion = "25.05";

  environment.systemPackages = with pkgs; [
    # Nested-Wayland, GPU diagnostics, audio & Android bridge tooling
    weston
    cage
    mesa
    vulkan-tools
    mesa-demos
    pulseaudio
    android-tools
    # Shared personal desktop & CLI parity
    helium
    gh
    bun
    restic
    rclone
    orb-desktop
    folio-desktop
  ];
}
