# Declarative OpenCode v2 CLI (`anomalyco/opencode` v2.2.7) and Orb Router provider sync.
{
  lib,
  pkgs,
  ...
}:
let
  opencodeLatest =
    if pkgs.stdenv.hostPlatform.system == "aarch64-linux" then
      let
        opencodeVersion = "2.2.7";
        opencodeSrc = pkgs.fetchFromGitHub {
          owner = "anomalyco";
          repo = "opencode";
          tag = "v${opencodeVersion}";
          hash = "sha256-JPCR867ZoMbYNrCvAa3OnilsxVcktD3mHCGpR6o/nlw=";
        };
        opencodeNodeModules = pkgs.callPackage "${opencodeSrc}/nix/node_modules.nix" {
          rev = "v${opencodeVersion}";
          hash = "sha256-L0rDvr0j/+R8bSErWXO1xTPac5rVamUny4clIThzSUk=";
        };
      in
      (pkgs.callPackage "${opencodeSrc}/nix/opencode.nix" {
        node_modules = opencodeNodeModules;
      }).overrideAttrs
        {
          version = opencodeVersion;
          __intentionallyOverridingVersion = true;
          installPhase = ''
            runHook preInstall

            install -Dm755 dist/cli-*/bin/opencode "$out/bin/opencode"

            wrapProgram "$out/bin/opencode" \
              --set OPENCODE_DISABLE_AUTOUPDATE true \
              --suffix PATH : ${lib.makeBinPath [ pkgs.ripgrep ]} \
              --prefix LD_LIBRARY_PATH : ${
                lib.makeLibraryPath [
                  pkgs.stdenv.cc.cc
                  pkgs.wayland
                ]
              }

            ln -s opencode "$out/bin/opencode2"

            runHook postInstall
          '';
        }
    else
      pkgs.opencode;

  syncScript = pkgs.writeText "opencode-orbrouter-sync.py" (
    builtins.readFile ./opencode-orbrouter-sync.py
  );
in
{
  home.packages = [ opencodeLatest ];

  # Provision the Orb Router proxy key and refresh the model list from the network
  # in a user service, never during `home-manager` activation: a switch stays
  # offline-safe, and failures are visible in `journalctl --user -u opencode-orbrouter-sync`.
  systemd.user.services.opencode-orbrouter-sync = {
    Unit = {
      Description = "Sync OpenCode Orb Router provider key and model list";
      After = [ "network-online.target" ];
      Wants = [ "network-online.target" ];
    };
    Service = {
      Type = "oneshot";
      ExecStart = "${pkgs.python3}/bin/python3 ${syncScript}";
    };
    Install.WantedBy = [ "default.target" ];
  };

  systemd.user.timers.opencode-orbrouter-sync = {
    Unit.Description = "Daily OpenCode Orb Router model refresh";
    Timer = {
      OnCalendar = "daily";
      RandomizedDelaySec = "1h";
      Persistent = true;
    };
    Install.WantedBy = [ "timers.target" ];
  };
}
