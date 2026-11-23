# Parameterized sops-nix secrets module (`secrets/<hostName>.yaml`).
#
# Shared by every personal workstation host (`m2air`, `razr`). Each file is encrypted to:
#   - the host's age key at /var/lib/sops-nix/key.txt, used at activation;
#   - Thomas's offline backup age key (private half in Bitwarden), so every secret can
#     be recovered independently of the device.
#
# Each secret is wired only once its key exists in `secrets/<hostName>.yaml`, so a fresh
# host evaluates and builds cleanly before `sudo bootstrap-secrets` is run.
{
  config,
  lib,
  pkgs,
  inventory,
  ...
}:
let
  inherit (inventory) user;
  hostName = config.networking.hostName;
  file = ../../secrets + "/${hostName}.yaml";
  present = builtins.pathExists file;
  content = if present then builtins.readFile file else "";
  has = key: lib.hasInfix "\n${key}:" "\n${content}";
in
{
  options.thomas.secrets.path = lib.mkOption {
    type = lib.types.functionTo (lib.types.functionTo lib.types.str);
    readOnly = true;
    description = ''
      `name -> fallback -> path`: the decrypted sops path of `name` when
      `secrets/<hostName>.yaml` defines it, otherwise `fallback`.
    '';
    default = name: fallback: if has name then config.sops.secrets.${name}.path else fallback;
  };

  config = lib.mkMerge [
    {
      # `sudo bootstrap-secrets` creates or extends secrets/<hostName>.yaml.
      environment.systemPackages = [
        (pkgs.writeShellApplication {
          name = "bootstrap-secrets";
          runtimeInputs = [
            pkgs.sops
            pkgs.age
            pkgs.yq-go
            pkgs.coreutils
          ];
          text = builtins.readFile ../../scripts/bootstrap-secrets.sh;
        })
      ];
    }

    (lib.mkIf present {
      sops = {
        defaultSopsFile = file;
        validateSopsFiles = true;
        age = {
          keyFile = "/var/lib/sops-nix/key.txt";
          generateKey = false;
          sshKeyPaths = [ ];
        };
        gnupg.sshKeyPaths = [ ];

        secrets = lib.mkMerge [
          (lib.mkIf (has "nix_builder_spark_key") {
            nix_builder_spark_key = {
              mode = "0400";
            };
          })
          (lib.mkIf (has "nix_signing_key") {
            nix_signing_key = {
              mode = "0400";
            };
          })
          (lib.mkIf (has "restic_password") {
            restic_password = {
              owner = user.username;
              mode = "0400";
            };
          })
        ];
      };
    })
  ];
}
