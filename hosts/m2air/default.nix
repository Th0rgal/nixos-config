# MacBook Air M2 — Thomas's personal NixOS host configuration.
{
  config,
  lib,
  pkgs,
  inventory,
  ...
}:
let
  inherit (inventory) user;
  secretPath = config.thomas.secrets.path;

in
{
  imports = [
    ../common/desktop.nix
    ./hardware.nix
    ./secrets.nix
  ];

  networking.hostName = "m2air";

  omarchnix = {
    # Live systemd job tree on the boot console (also answers the LUKS prompt).
    # Roll back from the GRUB menu, or set this to false, if the console misbehaves.
    boot.bootvine.enable = true;
    containers.enable = true;
    services.sharing = {
      airdrop.enable = true;
      quickshare.enable = true;
      nearby.enable = true;
      kdeconnect.enable = true;
    };
    inherit (inventory) mesh;
    macTether = {
      enable = true;
    };
    desktop = {
      keyboard = {
        layout = "us";
        variant = "intl";
      };
      touchpad = {
        naturalScroll = false;
        scrollFactor = 0.5;
      };
      murmure.enable = true;
      macos = {
        enable = true;
        gestures = true;
      };
      autostartTty = {
        enable = true;
        unlockKeyringFromLuks = true;
      };
    };
    hardware.m2air = {
      enable = true;
      chargeControlEndThreshold = 90;
      chargeControlStartThreshold = null;
      keyboardBacklight = {
        defaultBrightness = 50;
        enforceOnBoot = false;
      };
      touchId = {
        enable = lib.mkDefault false;
        requirePasswordAfterBoot = true;
        fido.enable = true;
      };
    };
  };

  # First-install release of this user's home state; never bump it to "upgrade".
  home-manager.users.${user.username}.home.stateVersion = "25.05";

  # First-install release of this host's state. Never bump it to "upgrade"; it only
  # changes when the machine is reinstalled.
  system.stateVersion = "25.05";

  # Proprietary desktop binaries (Antigravity, Claude Desktop, Widevine CDM);
  # allow only those unfree packages rather than the whole unfree set.
  nixpkgs.config.allowUnfreePackages = [
    "antigravity-hub"
    "claude-desktop"
    "widevine-cdm"
  ];

  programs.firefox.enable = true;

  environment.systemPackages = with pkgs; [
    android-tools
    helium
    bitwarden-desktop
    telegram-desktop
    signal-desktop-frameless
    vesktop
    age
    bun
    restic
    rclone
    gh
    libsecret
    turbo-lean
    murmure
    orb-desktop
    folio-desktop
    notion-calendar
    antigravity-hub
    claude-desktop
  ];

  # Keep /nix/store, user profiles, and the 476 MiB /boot EFI partition lean automatically
  # while always retaining at least 5 rollbacks.
  # Build on the dgx-spark (20-core aarch64, 119 GB) instead of this 16 GB laptop.
  # The Nix daemon offloads every derivation it cannot substitute, so `nh os switch`,
  # `nixos-rebuild` and plain `nix build` all use it without extra flags; when spark is
  # unreachable (off the mesh) the daemon falls back to building locally.
  #
  # Least privilege on spark: the key logs in as `nixbuild`, an account with no sudo and a
  # locked password, and spark's authorized_keys pins it to
  # `restrict,command="nix-daemon --stdio"`, so it can speak the Nix protocol and nothing
  # else. `nixbuild` is NOT a trusted Nix user there: spark accepts build inputs from this
  # laptop only because they are signed with /etc/nix/signing-key.sec, whose public half is
  # in spark's trusted-public-keys (/etc/nix/nix.custom.conf).
  # One-time setup, as root on this laptop:
  #   sudo ssh-keygen -t ed25519 -N "" -C nix-builder@m2air -f /root/.ssh/nix-builder-spark
  #   sudo nix-store --generate-binary-cache-key m2air-builder-1 /etc/nix/signing-key.sec /etc/nix/signing-key.pub
  #   sudo nix store sign --all --key-file /etc/nix/signing-key.sec
  nix.distributedBuilds = true;
  nix.buildMachines = [
    {
      inherit (inventory.builder) hostName;
      protocol = "ssh-ng";
      sshUser = "nixbuild";
      sshKey = secretPath "nix_builder_spark_key" "/root/.ssh/nix-builder-spark";
      systems = [ "aarch64-linux" ];
      maxJobs = 8;
      speedFactor = 8;
      supportedFeatures = [
        "big-parallel"
        "nixos-test"
        "benchmark"
      ];
    }
  ];
  nix.settings = {
    builders-use-substitutes = true;
    connect-timeout = 5;
    fallback = true;
    # Sign everything built here so the untrusted builder account can receive it.
    secret-key-files = [ (secretPath "nix_signing_key" "/etc/nix/signing-key.sec") ];
  };
  programs.ssh = {
    knownHosts.${inventory.builder.hostName}.publicKey = inventory.builder.hostKey;
    # Detect half-open `ssh-ng` builder sockets within ~45s if the laptop sleeps or roams
    # instead of blocking `nix-daemon` indefinitely on `read()`.
    extraConfig = ''
      Host ${inventory.builder.hostName}
        ConnectTimeout 5
        ServerAliveInterval 15
        ServerAliveCountMax 3
        TCPKeepAlive yes
    '';
  };

  programs.nh = {
    enable = true;
    flake = "/home/${user.username}/work/nixos/nixos-config";
    clean = {
      enable = true;
      dates = "weekly";
      extraArgs = "--keep 5 --keep-since 7d";
    };
  };

  # Allow `sudo nixos-rebuild {boot,switch}` to evaluate the user-owned flake cleanly
  # via `/etc/nixos -> /home/thomas/work/nixos/nixos-config` without git ownership errors.
  programs.git = {
    enable = true;
    config.safe.directory = [
      "/home/${user.username}/work/nixos/nixos-config"
      "/home/${user.username}/work/nixos/omarchnix"
    ];
  };

  # Secret Service (org.freedesktop.secrets) required by Bitwarden Desktop for
  # biometric unlock alongside Polkit (`polkit-1` PAM -> `pam_fprintd.so`).
  services.gnome.gnome-keyring.enable = true;

  # YubiKey PIV SSH agent for the Paloma fleet admin key (`thomas-yubikey-admin`).
  # Runs as a user service at /run/user/<uid>/yubikey-agent/yubikey-agent.sock,
  # which `home/modules/ssh-config.nix` selects so fleet SSH never falls back to
  # the offline break-glass key. The module also enables pcscd.
  services.yubikey-agent.enable = true;

  # Keep Apple M2 ISO physical `~`/`` ` `` key next to Left Shift (<TLDE>) instead of
  # hid_apple's automatic ISO swap, without altering Stage-1 LUKS passphrase entry.
  # Also ensure `/etc/nixos` points to the live `nixos-config` repo if missing.
  systemd.tmpfiles.rules = [
    "L /etc/nixos - - - - /home/${user.username}/work/nixos/nixos-config"
    "w /sys/module/hid_apple/parameters/iso_layout - - - - 0"
  ];

  # Wayland IME (fcitx5) so dead keys (`~`, `` ` ``, `'`, `"`, `^`) display underlined
  # preedit in foot and Wayland apps, matching macOS U.S. International - PC.
  i18n.inputMethod = {
    enable = true;
    type = "fcitx5";
    fcitx5.waylandFrontend = true;
  };

  users.users.${user.username} = {
    extraGroups = [
      "wheel"
      "video"
      "render"
      "audio"
      "input"
      "networkmanager"
    ];
    openssh.authorizedKeys.keys = user.sshKeys.m2airAuthorized;
  };
}
