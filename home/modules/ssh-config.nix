{
  config,
  lib,
  pkgs,
  ...
}:
let
  fleet = import ../../inventory/fleet.nix;

  allPalomaAliases = lib.concatMap (h: h.aliases) fleet.palomaFleet;
  controlMasterHosts = lib.filter (h: h.controlMaster or false) fleet.palomaFleet;

  renderFleetHost =
    h:
    ''
      Host ${lib.concatStringsSep " " h.aliases}
        HostName ${h.hostName}
        User ${h.user}
    ''
    + lib.optionalString ((h.proxyJump or null) != null) "  ProxyJump ${h.proxyJump}\n";

  renderControlMaster = h: ''
    Host ${lib.concatStringsSep " " h.aliases}
      ControlMaster auto
      ControlPath ~/.ssh/cm-%r@%h:%p
      ControlPersist 8h
      ServerAliveInterval 30
      ServerAliveCountMax 20
      TCPKeepAlive no
  '';

  renderStandaloneHost = h: ''
    Host ${lib.concatStringsSep " " h.aliases}
      HostName ${h.hostName}
      User ${h.user}
      IdentityFile ${h.identityFile}
      IdentitiesOnly yes
  '';

  sshConfigFile = pkgs.writeText "thomas-ssh-config" ''
    Host github.com
      HostName github.com
      User git
      IdentityFile ~/.ssh/github_read
      IdentitiesOnly yes
      PreferredAuthentications publickey

    Host aur.archlinux.org
      IdentityFile ~/.ssh/aur_key
      User aur

    # --- Paloma fleet (YubiKey hardware admin key + offline break-glass) ---
    ${lib.concatMapStringsSep "\n" renderFleetHost fleet.palomaFleet}
    Match host "${lib.concatStringsSep "," allPalomaAliases}" exec "test -S /run/user/%i/yubikey-agent/yubikey-agent.sock"
      IdentityAgent /run/user/%i/yubikey-agent/yubikey-agent.sock
      IdentityFile ~/.ssh/thomas-yubikey-admin.pub

    Host ${lib.concatStringsSep " " allPalomaAliases}
      IdentityFile ~/.ssh/thomas-breakglass
      IdentitiesOnly yes
      StrictHostKeyChecking accept-new

    # One YubiKey touch / breakglass unlock per agent-core session: every ssh reuses
    # this connection for 8 h, and it survives network drops of up to ~10 min.
    ${lib.concatMapStringsSep "\n" renderControlMaster controlMasterHosts}
    # --- Personal & collaborator hosts ---
    ${lib.concatMapStringsSep "\n" renderStandaloneHost fleet.standaloneHosts}
  '';
in
{
  # OpenSSH rejects symlinks into world-readable `/nix/store` on strict configurations;
  # materialize `~/.ssh/config` as a `0600` regular file owned by the user.
  home.activation.declarativeSshConfig = config.lib.dag.entryAfter [ "writeBoundary" ] ''
    mkdir -p -m 0700 "$HOME/.ssh"
    rm -f "$HOME/.ssh/config"
    install -m 0600 "${sshConfigFile}" "$HOME/.ssh/config"
  '';
}
