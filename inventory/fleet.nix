{
  # Paloma fleet hosts authenticated via YubiKey hardware admin key (`yubikey-agent.sock`)
  # with offline break-glass fallback (`~/.ssh/thomas-breakglass`).
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
    }
    {
      aliases = [ "ashur" ];
      hostName = "188.40.69.160";
      user = "root";
    }
    {
      aliases = [ "babylon" ];
      hostName = "54.36.175.109";
      user = "ubuntu";
    }
    {
      aliases = [ "nippur" ];
      hostName = "37.187.92.183";
      user = "ubuntu";
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
      ];
      hostName = "10.88.0.2";
      user = "th0rgal";
      proxyJump = "agent-core";
    }
  ];

  standaloneHosts = [
    {
      aliases = [ "umbrel" ];
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
