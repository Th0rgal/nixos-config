# Shared personal workstation layer for Thomas's daily-driver hosts (`m2air`, `razr`).
# Excluded from minimal test VMs (`hosts/vm`).
#
# Hardware modules (`hosts/m2air/hardware.nix`, `hosts/razr/hardware.nix`) provide
# platform capabilities (GPU, biometrics/fprintd, FIDO2, UPower/PowerProfiles, container
# runtime flags) while this module owns strict 1:1 app, policy, keyring, IME, builder,
# and fleet parity.
{
  config,
  lib,
  pkgs,
  inventory,
  ...
}:
let
  inherit (inventory) user;
  rencal = pkgs.callPackage ../../pkgs/rencal.nix { };
  secretPath = config.thomas.secrets.path;
  wrapElectron = config.thomas.workstation.wrapElectronForContainer;

  # In Android/droidspaces containers, Chromium/Electron user-namespace sandboxes are
  # blocked by Android SELinux; wrap binaries cleanly with `--no-sandbox` + native Wayland
  # without touching `m2air`.
  maybeWrapElectron =
    pkg: binNames:
    if !wrapElectron then
      pkg
    else
      pkgs.symlinkJoin {
        name = "${pkg.pname or pkg.name}-container";
        paths = [ pkg ];
        nativeBuildInputs = [ pkgs.makeWrapper ];
        postBuild = lib.concatMapStringsSep "\n" (bin: ''
          if [ -e "$out/bin/${bin}" ]; then
            rm -f "$out/bin/${bin}"
            makeWrapper "${pkg}/bin/${bin}" "$out/bin/${bin}" \
              --add-flags "--no-sandbox --disable-gpu-sandbox --ozone-platform=wayland"
          fi
        '') binNames;
      };

  # Cursor writes JSON with comments, including its per-install crash reporter ID.
  jsoncParser = pkgs.fetchzip {
    url = "https://registry.npmjs.org/jsonc-parser/-/jsonc-parser-3.3.1.tgz";
    hash = "sha256-eZb4Epz0UsTTaSstqBl46Sy/KRyKaJ+vBUJ92/6wsZY=";
  };
  configureCursorKeyring = pkgs.writeText "cursor-keyring.cjs" ''
    const fs = require("node:fs");
    const path = require("node:path");
    const { randomUUID } = require("node:crypto");
    const jsonc = require("${jsoncParser}");
    const dir = path.join(process.env.HOME, ".cursor");
    const file = path.join(dir, "argv.json");
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    const exists = fs.existsSync(file);
    const source = exists ? fs.readFileSync(file, "utf8") : "{}\n";
    const errors = [];
    const current = jsonc.parse(source, errors, { allowTrailingComma: true });
    if (errors.length || !current || typeof current !== "object" || Array.isArray(current)) {
      console.error("Cursor keyring setting skipped: argv.json is not a valid JSONC object.");
    } else if (current["password-store"] !== "gnome-libsecret") {
      const edits = jsonc.modify(source, ["password-store"], "gnome-libsecret", {
        formattingOptions: { insertSpaces: false, tabSize: 4, eol: source.includes("\r\n") ? "\r\n" : "\n" }
      });
      const tmp = file + "." + randomUUID();
      try {
        fs.writeFileSync(tmp, jsonc.applyEdits(source, edits), {
          flag: "wx", mode: exists ? fs.statSync(file).mode & 0o777 : 0o600
        });
        fs.renameSync(tmp, file);
      } finally {
        fs.rmSync(tmp, { force: true });
      }
    }
  '';

  # The native Carbon and Neon selectors require a newer CLI than this nixpkgs pin.
  antigravityCli =
    if lib.versionAtLeast pkgs.antigravity-cli.version "1.5.13" then
      pkgs.antigravity-cli
    else
      pkgs.antigravity-cli.overrideAttrs {
        version = "1.5.13";
        src = pkgs.fetchurl {
          url = "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.5.13-5999302598852608/linux-arm/cli_linux_arm64.tar.gz";
          hash = "sha512-3M6i60VQmfUXXwyHzEezj87+TLDLRN6jknRpu0u/aKw8CzD8BK/7JMF+kJWThqk8N4Xo5LixozOMpOTVe4AFew==";
        };
      };

  agentGit = import ../../lib/agent-git.nix {
    inherit pkgs;
    inherit (user) agent;
  };
in
{
  imports = [
    ./desktop.nix
    ./secrets.nix
  ];

  options.thomas.workstation = {
    wrapElectronForContainer = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = ''
        Wrap Chromium/Electron desktop binaries with `--no-sandbox --disable-gpu-sandbox
        --ozone-platform=wayland` for Android/droidspaces containers.
      '';
    };
  };

  config = {
    environment.sessionVariables.ORB_DEFAULT_UI = "hybrid";

    omarchnix.desktop = {
      keyboard = {
        layout = "us";
        variant = "intl";
      };
      murmure.enable = lib.mkDefault true;
      macos = {
        enable = true;
        touchIdHud = lib.mkDefault true;
      };
    };

    # Preserve every local edit, including repeated Hyprland configuration trials.
    home-manager.backupCommand = lib.mkDefault (
      pkgs.writeShellScript "workstation-home-backup" ''
        exec ${pkgs.coreutils}/bin/mv --backup=numbered --no-target-directory -- "$1" "$1.hm-bak"
      ''
    );

    home-manager.users.${user.username} = {
      xdg.dataFile."applications/cursor.desktop".source =
        let
          cursorRoot = "${config.users.users.${user.username}.home}/work/nixos/cursor";
          extraFlags = lib.optionalString wrapElectron " --no-sandbox --disable-gpu-sandbox --ozone-platform=wayland";
          launchCursor = pkgs.writeShellScript "launch-cursor" ''
            if [ -x "${cursorRoot}/bin/cursor" ]; then
              exec "${cursorRoot}/bin/cursor"${extraFlags} "$@"
            fi
            exec ${pkgs.code-cursor}/bin/cursor${extraFlags} "$@"
          '';
          launcher = pkgs.makeDesktopItem {
            name = "cursor";
            desktopName = "Cursor";
            genericName = "Code Editor";
            exec = "${launchCursor} %F";
            icon = "${pkgs.code-cursor}/share/pixmaps/cursor.png";
            categories = [
              "Development"
              "IDE"
            ];
            startupWMClass = "cursor";
          };
        in
        "${launcher}/share/applications/cursor.desktop";

      home.activation.cursorKeyring =
        config.home-manager.users.${user.username}.lib.dag.entryAfter [ "writeBoundary" ]
          ''
            run ${pkgs.nodejs}/bin/node ${configureCursorKeyring}
          '';

      # First-install release of this user's home state; never bump it to "upgrade".
      home.stateVersion = "25.05";
    };

    # Proprietary desktop binaries (Antigravity, Claude Desktop, ChatGPT/Codex, Widevine CDM);
    # allow only those unfree packages rather than the whole unfree set.
    nixpkgs.config.allowUnfreePackages = [
      "antigravity-hub"
      "antigravity-cli"
      "chatgpt"
      "claude-code"
      "claude-desktop"
      "cursor"
      "widevine-cdm"
    ];

    programs.firefox.enable = true;

    systemd.packages = [ rencal ];
    systemd.user.services.rencal-notifierd.wantedBy = [ "graphical-session.target" ];

    # Helium/Chromium managed policies: keep Omarchy's device color-scheme policy,
    # replace KISS Translator with FluentRead (configured for Orb Router builtin/fast).
    environment.etc."chromium/policies/managed".source =
      pkgs.runCommand "workstation-chromium-managed-policies" { }
        ''
          mkdir -p "$out"
          cat > "$out/10-omarchnix-color-scheme.json" <<'EOF'
          {
            "BrowserColorScheme": "device"
          }
          EOF
          cat > "$out/20-fluentread.json" <<'EOF'
          {
            "ExtensionSettings": {
              "bdiifdefkgmcblbcghdlonllpjhhjgof": {
                "installation_mode": "blocked"
              },
              "djnlaiohfaaifbibleebjggkghlmcpcj": {
                "installation_mode": "force_installed",
                "update_url": "https://clients2.google.com/service/update2/crx"
              }
            }
          }
          EOF
        '';

    environment.systemPackages = with pkgs; [
      android-tools
      (maybeWrapElectron helium [
        "helium"
        "helium-browser"
      ])
      (maybeWrapElectron bitwarden-desktop [
        "bitwarden"
        "bitwarden-desktop"
      ])
      (callPackage ../../pkgs/cursor-system.nix { })
      telegram-desktop
      (maybeWrapElectron signal-desktop-frameless [ "signal-desktop" ])
      (maybeWrapElectron vesktop [ "vesktop" ])
      age
      bun
      restic
      rclone
      gh
      libfido2
      libsecret
      turbo-lean
      murmure
      (maybeWrapElectron orb-desktop [
        "orb"
        "orb-desktop"
      ])
      folio-desktop
      rencal
      (agentGit.wrap (
        maybeWrapElectron antigravity-hub [
          "antigravity"
          "antigravity-hub"
        ]
      ))
      (agentGit.wrap antigravityCli)
      (agentGit.wrap claude-code)
      (agentGit.wrap (maybeWrapElectron claude-desktop [ "claude-desktop" ]))
      # ChatGPT desktop (official Linux build), which includes the Codex app.
      (agentGit.wrap (maybeWrapElectron chatgpt [ "chatgpt" ]))
      (agentGit.wrap codex)
    ];

    # Remote Nix builder offload to dgx-spark (20-core aarch64, 119 GB RAM).
    # Each workstation signs its own inputs with its host-unique signing key and connects
    # as least-privilege `nixbuild`; when off-mesh, Nix transparently falls back locally.
    nix.distributedBuilds = true;
    nix.buildMachines = [
      {
        inherit (inventory.builder) hostName;
        protocol = "ssh-ng";
        sshUser = "nixbuild";
        sshKey = secretPath "nix_builder_spark_key" "/root/.ssh/nix-builder-spark";
        systems = [ "aarch64-linux" ];
        # Cap concurrent remote jobs to 4 so parallel NAR uploads don't saturate
        # high-latency WAN/mesh links and trigger SSH Broken pipe timeouts.
        maxJobs = 4;
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
      # Allow wheel users to pass `--option builders ""` (used by `rebuild --local`).
      trusted-users = [
        "root"
        "@wheel"
      ];
      secret-key-files = [ (secretPath "nix_signing_key" "/etc/nix/signing-key.sec") ];
    };
    programs.ssh = {
      knownHosts.${inventory.builder.hostName}.publicKey = inventory.builder.hostKey;
      extraConfig = ''
        Host ${inventory.builder.hostName}
          ConnectTimeout 5
          Compression yes
          ServerAliveInterval 30
          ServerAliveCountMax 10
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

    programs.git = {
      enable = true;
      config.safe.directory = [
        "/home/${user.username}/work/nixos/nixos-config"
        "/home/${user.username}/work/nixos/omarchnix"
      ];
    };

    # Secret Service (org.freedesktop.secrets) required by Bitwarden Desktop & Cursor.
    services.gnome.gnome-keyring.enable = true;

    # Wayland IME (fcitx5) so dead keys display underlined preedit in Wayland apps.
    i18n.inputMethod = {
      enable = true;
      type = "fcitx5";
      fcitx5.waylandFrontend = true;
    };

    users.users.${user.username}.openssh.authorizedKeys.keys = lib.mkDefault user.sshKeys.admin;
  };
}
