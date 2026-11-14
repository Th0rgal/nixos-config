# Stand-in for the private `inventory` input (github.com/Th0rgal/nixos-inventory) so CI
# and forks can evaluate every host. Same shape, documentation-range addresses only.
#   nix eval --override-input inventory path:./ci/inventory-stub ...
{
  fleet = {
    palomaFleet = [
      {
        aliases = [ "example-core" ];
        hostName = "192.0.2.1";
        user = "root";
        controlMaster = true;
      }
      {
        aliases = [ "example-worker" ];
        hostName = "192.0.2.2";
        user = "root";
        proxyJump = "example-core";
      }
    ];
    standaloneHosts = [
      {
        aliases = [ "example-home" ];
        hostName = "192.0.2.10";
        user = "example";
        identityFile = "~/.ssh/id_ed25519";
      }
    ];
  };

  mesh = {
    enable = false;
    instances = { };
  };

  builder = {
    hostName = "192.0.2.30";
    hostKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBOq3cP8E7kVZV9x9p4Bq3P0vAa2s3jT8Qd6xW8hX3nK ci-stub";
  };
}
