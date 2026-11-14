{
  # Paloma fleet hosts authenticated with the Touch ID FIDO2 key (`~/.ssh/thomas-touchid`),
  # then the YubiKey admin key (`yubikey-agent.sock`). Break-glass is manual-only.
  # Workers with `:22` closed to the WAN (lean-mesh + agent-core only) jump via agent-core.
  palomaFleet = [
    {
      aliases = [
        "agent-core"
        "orb-core"
      ];
      hostName = "65.109.98.246";
      user = "root";
      controlMaster = true;
    }
    {
      aliases = [
        "uruk"
        "old-agent"
      ];
      hostName = "95.216.112.253";
      user = "root";
      proxyJump = "agent-core";
    }
    {
      aliases = [ "ashur" ];
      hostName = "188.40.69.160";
      user = "root";
      proxyJump = "agent-core";
    }
    {
      aliases = [ "babylon" ];
      hostName = "54.36.175.109";
      user = "ubuntu";
      proxyJump = "agent-core";
    }
    {
      aliases = [ "nippur" ];
      hostName = "37.187.92.183";
      user = "ubuntu";
      proxyJump = "agent-core";
    }
    {
      aliases = [
        "lagash"
        "watchtower"
        "media-exit"
      ];
      hostName = "147.135.137.133";
      user = "ubuntu";
    }
    {
      aliases = [ "nimrud" ];
      hostName = "51.195.100.33";
      user = "ubuntu";
      proxyJump = "agent-core";
    }
    {
      aliases = [ "thomas-md" ];
      hostName = "95.216.244.60";
      user = "root";
    }
    {
      aliases = [ "polymath" ];
      hostName = "5.135.152.216";
      user = "ubuntu";
    }
    {
      aliases = [ "maven-repo" ];
      hostName = "141.94.78.32";
      user = "ubuntu";
    }
    {
      aliases = [
        "dgx-spark"
        "spark-de79"
        "spark"
      ];
      hostName = "10.88.50.30";
      user = "th0rgal";
      # Also the remote Nix builder: one Touch ID per 8 h instead of one per connection.
      controlMaster = true;
    }
    {
      aliases = [ "dgx-spark-paloma" ];
      hostName = "10.88.0.2";
      user = "th0rgal";
      proxyJump = "agent-core";
    }
  ];

  standaloneHosts = [
    {
      aliases = [
        "umbrel"
        "umbrel.mesh"
      ];
      hostName = "10.88.50.20";
      hostKeyAlias = "umbrel.local";
      user = "umbrel";
      identityFile = "~/.ssh/id_ecdsa";
    }
    {
      aliases = [ "umbrel-lan" ];
      hostName = "umbrel.local";
      user = "umbrel";
      identityFile = "~/.ssh/id_ecdsa";
    }
    {
      aliases = [ "ben" ];
      hostName = "100.76.231.7";
      user = "benjaminflores";
      identityFile = "~/.ssh/paloma";
    }
    {
      aliases = [ "ben-server" ];
      hostName = "100.117.216.26";
      user = "root";
      identityFile = "~/.ssh/paloma";
    }
  ];
}
