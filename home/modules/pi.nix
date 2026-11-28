# Declarative Pi coding agent CLI (`pkgs.pi-coding-agent`) and Orb Router / local Bonsai provider sync.
{
  inventory,
  pkgs,
  ...
}:
let
  # Pi commits as the agent identity, not as Thomas.
  piAgent =
    (import ../../lib/agent-git.nix {
      inherit pkgs;
      inherit (inventory.user) agent;
    }).wrap
      pkgs.pi-coding-agent;

  syncScript = pkgs.writeText "pi-orbrouter-sync.py" (builtins.readFile ./pi-orbrouter-sync.py);
in
{
  home.packages = [ piAgent ];

  # Provision the Orb Router proxy key and refresh ~/.pi/agent/models.json from the network
  # in a user service, never during `home-manager` activation: a switch stays offline-safe,
  # and failures are visible in `journalctl --user -u pi-orbrouter-sync`.
  systemd.user.services.pi-orbrouter-sync = {
    Unit = {
      Description = "Sync Pi Orb Router provider key and model catalog";
      After = [ "network-online.target" ];
      Wants = [ "network-online.target" ];
    };
    Service = {
      Type = "oneshot";
      ExecStart = "${pkgs.python3}/bin/python3 ${syncScript}";
    };
    Install.WantedBy = [ "default.target" ];
  };

  systemd.user.timers.pi-orbrouter-sync = {
    Unit.Description = "Daily Pi Orb Router model refresh";
    Timer = {
      OnCalendar = "daily";
      RandomizedDelaySec = "1h";
      Persistent = true;
    };
    Install.WantedBy = [ "timers.target" ];
  };
}
