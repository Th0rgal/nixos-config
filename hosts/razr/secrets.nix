# sops-nix secrets for razr (`secrets/razr.yaml`).
#
# The file is encrypted to two recipients (see `.sops.yaml`):
#   - this host's key at /var/lib/sops-nix/key.txt, used at activation;
#   - Thomas's offline backup age key (private half in the Bitwarden personal vault),
#     so every secret here can be recovered without this device.
#
# Each secret is wired only once its key exists in the file, so the host builds and
# evaluates cleanly until `secrets/razr.yaml` is provisioned.
{
  config,
  lib,
  pkgs,
  inventory,
  ...
}:
let
  inherit (inventory) user;
  file = ../../secrets/razr.yaml;
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
      `secrets/razr.yaml` defines it, otherwise `fallback` (the hand-provisioned file).
    '';
    default = name: fallback: if has name then config.sops.secrets.${name}.path else fallback;
  };

  config = lib.mkMerge [
    {
      # `sudo bootstrap-secrets` creates or extends secrets/razr.yaml.
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
