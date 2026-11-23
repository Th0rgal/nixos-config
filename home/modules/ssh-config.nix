{
  config,
  lib,
  pkgs,
  inventory,
  ...
}:
let
  inherit (inventory) fleet;

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

  renderStandaloneHost =
    h:
    ''
      Host ${lib.concatStringsSep " " h.aliases}
        HostName ${h.hostName}
        User ${h.user}
        IdentityFile ${h.identityFile}
        IdentitiesOnly yes
    ''
    + lib.optionalString ((h.hostKeyAlias or null) != null) "  HostKeyAlias ${h.hostKeyAlias}\n";

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

    # --- Paloma fleet (Touch ID FIDO2 key, then YubiKey admin key) ---
    # The offline break-glass key is never offered automatically; use it only
    # by hand in an emergency: `ssh -i ~/.ssh/thomas-breakglass <host>`.
    ${lib.concatMapStringsSep "\n" renderFleetHost fleet.palomaFleet}
    Match originalhost "${lib.concatStringsSep "," allPalomaAliases}" exec "test -S /run/user/%i/yubikey-agent/yubikey-agent.sock"
      IdentityAgent /run/user/%i/yubikey-agent/yubikey-agent.sock

    Host ${lib.concatStringsSep " " allPalomaAliases}
      IdentityFile ~/.ssh/thomas-touchid
      IdentityFile ~/.ssh/thomas-yubikey-admin.pub
      IdentitiesOnly yes
      StrictHostKeyChecking accept-new

    # One YubiKey touch / breakglass unlock per agent-core session: every ssh reuses
    # this connection for 8 h, and it survives network drops of up to ~10 min.
    ${lib.concatMapStringsSep "\n" renderControlMaster controlMasterHosts}
    # Every other fleet host: one Touch ID / YubiKey touch covers a 10 min burst of
    # commands (agents run many short `ssh host cmd` calls). OpenSSH keeps the first
    # value per option, so the 8 h hosts above are unaffected. Drop a live connection
    # early with `ssh -O exit <host>`.
    Host ${lib.concatStringsSep " " allPalomaAliases}
      ControlMaster auto
      ControlPath ~/.ssh/cm-%r@%h:%p
      ControlPersist 10m

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
