# MacBook Air M2 — Thomas's personal NixOS host configuration.
{
  config,
  lib,
  pkgs,
  ...
}:
let
  user = import ../../inventory/user.nix;
  secretPath = config.thomas.secrets.path;

  # Run Signal Desktop natively on Wayland without Electron's CSD titlebar or
  # Linux menu bar, while keeping fcitx5 dead-key IME and gnome-keyring storage.
  signalFramelessHook = pkgs.writeText "signal-frameless-hook.cjs" ''
    const Module = require("node:module");
    const origLoad = Module._load;
    let WrappedBrowserWindow;

    Module._load = function (request, parent, isMain) {
      const loaded = origLoad.apply(this, arguments);
      if (request === "electron" && loaded && typeof loaded === "object") {
        return new Proxy(loaded, {
          get(target, prop) {
            if (prop === "BrowserWindow") {
              if (!WrappedBrowserWindow) {
                const OrigBrowserWindow = Reflect.get(target, prop, target);
                WrappedBrowserWindow = class FramelessBrowserWindow extends OrigBrowserWindow {
                  constructor(opts = {}) {
                    const nextOpts = { ...opts, frame: false, autoHideMenuBar: true };
                    delete nextOpts.titleBarStyle;
                    super(nextOpts);
                    super.setMenuBarVisibility(false);
                  }
                  setMenuBarVisibility(_visible) {
                    return super.setMenuBarVisibility(false);
                  }
                };
              }
              return WrappedBrowserWindow;
            }
            return Reflect.get(target, prop, target);
          },
        });
      }
      return loaded;
    };
  '';

  signalDesktopWayland = pkgs.symlinkJoin {
    name = "signal-desktop-${pkgs.signal-desktop.version}";
    paths = [ pkgs.signal-desktop ];
    nativeBuildInputs = [ pkgs.makeWrapper ];
    postBuild = ''
      cp --remove-destination "$(readlink -f "$out/bin/signal-desktop")" "$out/bin/signal-desktop"
      chmod 755 "$out/bin/signal-desktop"
      substituteInPlace "$out/bin/signal-desktop" \
        --replace-fail "${pkgs.signal-desktop}/share/signal-desktop/app.asar" \
        "-r ${signalFramelessHook} ${pkgs.signal-desktop}/share/signal-desktop/app.asar"
      wrapProgram "$out/bin/signal-desktop" \
        --add-flags "--password-store=gnome-libsecret" \
        --add-flags "\''${NIXOS_OZONE_WL:+\''${WAYLAND_DISPLAY:+--ozone-platform-hint=auto --disable-features=WaylandWindowDecorations --enable-wayland-ime=true --wayland-text-input-version=3}}"
      for desktop in "$out/share/applications/"*.desktop; do
        if [ -L "$desktop" ]; then
          cp --remove-destination "$(readlink -f "$desktop")" "$desktop"
          substituteInPlace "$desktop" --replace-quiet "Exec=signal-desktop" "Exec=$out/bin/signal-desktop"
        fi
      done
    '';
    inherit (pkgs.signal-desktop) meta;
  };
in
{
  imports = [
    ../common/thomas-user.nix
    ./hardware.nix
    ./secrets.nix
  ];

  networking.hostName = "m2air";

  omarchnix = {
    enable = true;
    profile = "asahi";
    defaultTimeZone = user.defaultTimeZone;
    hardening.enable = true;
    containers.enable = true;
    secrets.enable = false;
    services.sharing = {
      airdrop.enable = true;
      quickshare.enable = true;
      nearby.enable = true;
      kdeconnect.enable = true;
    };
    mesh = {
      enable = true;
      instances = {
        paloma = {
          interface = "wg-leanmesh0";
          nftTable = "lean_mesh";
          listenPort = 51888;
          nodeName = "macbook-air";
          nodeId = 100;
          overlayAddress = "10.88.0.100/32";
          stateDir = "/etc/lean-mesh";
        };
        personal = {
          interface = "wg-homemesh0";
          nftTable = "home_mesh";
          listenPort = 51891;
          openFirewallUdp = true;
          nodeName = "m2air";
          nodeId = 10;
          overlayAddress = "10.88.50.10/32";
          stateDir = "/etc/lean-mesh-personal";
          roamingPeers = [
            {
              peerName = "umbrel";
              peerId = 20;
              lanHost = "umbrel.local";
              lanFallbackHost = "umbrel.home";
              lanPort = 51891;
              relayEndpoint = "65.109.98.246:51890";
            }
            {
              peerName = "dgx-spark";
              peerId = 30;
              lanHost = "spark-de79.local";
              lanFallbackHost = "spark-de79.home";
              lanPort = 51891;
              relayEndpoint = "65.109.98.246:51890";
            }
          ];
        };
      };
    };
    macTether = {
      enable = true;
    };
    desktop = {
      enable = true;
      user = user.username;
      theme = "starship-subtle";
      shellBackend = "omarchy-shell";
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

  home-manager.users.${user.username} = {
    imports = [ ../../home/profiles/desktop.nix ];
    # First-install release of this user's home state; never bump it to "upgrade".
    home.stateVersion = "25.05";
  };

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
    signalDesktopWayland
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
      hostName = "10.88.50.30";
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
    knownHosts."10.88.50.30".publicKey =
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOQjxSATucT7hVuqjCb+0K7iJKpMsWDmyTjsUKcKBoQ9";
    # Detect half-open `ssh-ng` builder sockets within ~45s if the laptop sleeps or roams
    # instead of blocking `nix-daemon` indefinitely on `read()`.
    extraConfig = ''
      Host 10.88.50.30
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
