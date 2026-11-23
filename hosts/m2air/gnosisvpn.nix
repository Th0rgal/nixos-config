# Gnosis VPN (powered by HOPR) — desktop app, CLI, and root daemon.
{
  lib,
  pkgs,
  ...
}:
let
  gnosisvpn = pkgs.callPackage ../../pkgs/gnosisvpn.nix { };
  network = "piz-palu-prod";
  blokliUrl = "https://blokli.piz-palu.gnosisvpn.io";
in
{
  environment.systemPackages = [ gnosisvpn ];

  environment.etc = {
    "gnosisvpn/config.toml".source = "${gnosisvpn}/etc/gnosisvpn/config-${network}.toml";
    "gnosisvpn/version.txt".source = "${gnosisvpn}/etc/gnosisvpn/version.txt";
  };

  # Upstream recommends fq + BBR for tunnel throughput and latency.
  boot.kernel.sysctl = {
    "net.core.default_qdisc" = lib.mkDefault "fq";
    "net.ipv4.tcp_congestion_control" = lib.mkDefault "bbr";
  };

  systemd.services.gnosisvpn = {
    description = "Gnosis VPN Service";
    documentation = [ "https://docs.vpn.gnosis.eth.limo/" ];
    after = [ "network-online.target" ];
    wants = [ "network-online.target" ];
    wantedBy = [ "multi-user.target" ];

    unitConfig = {
      StartLimitIntervalSec = 200;
      StartLimitBurst = 5;
    };

    path = with pkgs; [
      iptables
      iproute2
    ];

    environment = {
      GNOSISVPN_CONFIG_PATH = "/etc/gnosisvpn/config.toml";
      GNOSISVPN_WORKER_BINARY = "${gnosisvpn}/bin/gnosis_vpn-worker";
      GNOSISVPN_HOPR_BLOKLI_URL = blokliUrl;
      GNOSISVPN_LOG_FILE = "/var/log/gnosisvpn/gnosisvpn.log";
      GNOSISVPN_FORCE_STATIC_ROUTING = "true";
      RUST_LOG = "info";
      RUST_BACKTRACE = "full";
      XDG_DATA_DIRS = "${gnosisvpn}/share:/run/current-system/sw/share";
    };

    serviceConfig = {
      Type = "simple";
      User = "root";
      Group = "root";
      ExecStart = "${gnosisvpn}/bin/gnosis_vpn-root";
      WorkingDirectory = "/var/lib/gnosisvpn";
      StateDirectory = "gnosisvpn";
      StateDirectoryMode = "0755";
      RuntimeDirectory = "gnosisvpn";
      RuntimeDirectoryMode = "0755";
      LogsDirectory = "gnosisvpn";
      LogsDirectoryMode = "0755";
      Restart = "always";
      RestartSec = "5s";
      TimeoutStopSec = 30;
      TimeoutStartSec = 60;
      SyslogIdentifier = "gnosisvpn";
      CapabilityBoundingSet = [
        "CAP_NET_ADMIN"
        "CAP_NET_BIND_SERVICE"
        "CAP_NET_RAW"
        "CAP_SETUID"
        "CAP_SETGID"
        "CAP_DAC_OVERRIDE"
        "CAP_CHOWN"
      ];
      AmbientCapabilities = [
        "CAP_NET_ADMIN"
        "CAP_NET_BIND_SERVICE"
        "CAP_NET_RAW"
        "CAP_SETUID"
        "CAP_SETGID"
        "CAP_DAC_OVERRIDE"
        "CAP_CHOWN"
      ];
      ProtectSystem = "strict";
      ProtectHome = false;
      PrivateTmp = true;
      BindReadOnlyPaths = [
        "${gnosisvpn}/share/gnosisvpn:/usr/share/gnosisvpn"
      ];
      ReadWritePaths = [
        "-/home"
        "-/var/home"
        "/etc"
      ];
      LimitNOFILE = 65536;
    };
  };
}
