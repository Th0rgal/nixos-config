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

  # Clamp Aquamarine's nested Wayland protocol bind versions to what the parent
  # headless compositor (`sway` / `weston` / `cage`) actually advertises instead
  # of hardcoding `xdg_wm_base` v6 / `wl_seat` v9.
  nixpkgs.overlays = [
    (_final: prev: {
      aquamarine = prev.aquamarine.overrideAttrs (old: {
        postPatch = (old.postPatch or "") + ''
          substituteInPlace src/backend/Wayland.cpp \
            --replace-fail '&wl_seat_interface, 9)' '&wl_seat_interface, std::min<uint32_t>(version, 9))' \
            --replace-fail '&xdg_wm_base_interface, 6)' '&xdg_wm_base_interface, std::min<uint32_t>(version, 6))' \
            --replace-fail '&wl_compositor_interface, 6)' '&wl_compositor_interface, std::min<uint32_t>(version, 6))' \
            --replace-fail '&zwp_linux_dmabuf_v1_interface, 4)' '&zwp_linux_dmabuf_v1_interface, std::min<uint32_t>(version, 4))'
        '';
      });
    })
  ];

  # Map Android supplementary GIDs so user processes have native access to
  # paranoid networking (`aid_inet`) and GPU/input nodes (`/dev/kgsl-3d0`, `/dev/dri`).
  users.groups = {
    droidspaces-gpu.gid = 786;
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
      "droidspaces-gpu"
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
    # Pure-Wayland headless host + remote viewer server (`sway` + `wayvnc`), GPU & audio tooling
    sway
    wayvnc
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

  # Software-renderer (llvmpipe) desktop tuning for Snapdragon 8 Elite until
  # Mesa Turnip supports Adreno 830 (a8xx):
  #  - Use CPU-native `foot` instead of OpenGL `ghostty` (opens in <30ms vs multi-second shader compile)
  #  - Disable 3-pass Kawase blur, drop shadows, and spring animations (drops Hyprland from ~600% CPU to <5%)
  #  - Disable hypridle/auto-lock inside container (Android lockscreen already secures the phone, and SELinux blocks unix_chkpwd)
  home-manager.users.thomas = {
    services.hypridle.enable = lib.mkForce false;
    wayland.windowManager.hyprland = {
      settings.terminal._var = lib.mkForce "${pkgs.foot}/bin/foot";
      extraConfig = lib.mkAfter ''
        -- Razr llvmpipe performance overrides (applied after Omarchy glassy hooks)
        hl.config({
          decoration = {
            blur = { enabled = false, passes = 0 },
            shadow = { enabled = false },
          },
          animations = { enabled = false },
        })
      '';
    };
  };
}
