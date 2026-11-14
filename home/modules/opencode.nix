# Declarative OpenCode v2 CLI (`anomalyco/opencode` v2.2.7) and Orb Router provider sync.
{
  inventory,
  pkgs,
  ...
}:
let
  # OpenCode commits as the agent identity, not as Thomas.
  opencodeLatest =
    (import ../../lib/agent-git.nix {
      inherit pkgs;
      inherit (inventory.user) agent;
    }).wrap
      pkgs.opencode-v2;

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
