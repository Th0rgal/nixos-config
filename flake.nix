{
  description = "Thomas's personal NixOS & Home Manager configurations (m2air, m2air-aurora, spark), built on Th0rgal/omarchnix";

  inputs = {
    omarchnix.url = "github:Th0rgal/omarchnix";

    nixpkgs.follows = "omarchnix/nixpkgs";
    apple-silicon-support.follows = "omarchnix/apple-silicon-support";
    nixos-aurora.follows = "omarchnix/nixos-aurora";
    home-manager.follows = "omarchnix/home-manager";
    sops-nix.follows = "omarchnix/sops-nix";
    orb.follows = "omarchnix/orb";
  };

  outputs =
    {
      self,
      nixpkgs,
      omarchnix,
      apple-silicon-support,
      nixos-aurora,
      home-manager,
      sops-nix,
      orb,
      ...
    }@inputs:
    let
      systems = [
        "aarch64-linux"
        "x86_64-linux"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;

      mkHost =
        {
          system ? "aarch64-linux",
          withAsahiOverlay ? true,
          withAuroraOverlay ? false,
          modules,
        }:
        nixpkgs.lib.nixosSystem {
          inherit system;
          specialArgs = {
            inherit inputs;
          };
          modules = [
            {
              nixpkgs.overlays =
                nixpkgs.lib.optionals withAuroraOverlay [
                  nixos-aurora.inputs.apple-silicon.overlays.default
                  nixos-aurora.overlays.default
                ]
                ++ nixpkgs.lib.optionals (withAsahiOverlay && !withAuroraOverlay) [
                  apple-silicon-support.overlays.default
                ]
                ++ [
                  orb.overlays.default
                  omarchnix.overlays.default
                ];
              nix.registry.nixpkgs.flake = nixpkgs;
            }
            omarchnix.nixosModules.omarchnix
            home-manager.nixosModules.home-manager
            sops-nix.nixosModules.sops
          ]
          ++ modules;
        };
    in
    {
      nixosConfigurations = {
        # MacBook Air M2 daily driver (stock nixos-apple-silicon + alx.sh Stage 2)
        m2air = mkHost {
          modules = [
            apple-silicon-support.nixosModules.apple-silicon-support
            ./hosts/m2air
          ];
        };

        # MacBook Air M2 with Stage-2 Aurora SEP, Touch ID (fprintd) & FIDO2 (aurora-fido)
        m2air-aurora = mkHost {
          withAuroraOverlay = true;
          modules = [
            nixos-aurora.inputs.apple-silicon.nixosModules.default
            ./hosts/m2air
            ./hosts/m2air/aurora.nix
          ];
        };

        # NVIDIA Grace / DGX Spark ARM64 workstation & server
        spark = mkHost {
          withAsahiOverlay = false;
          modules = [
            ./hosts/spark
          ];
        };
      };

      formatter = forAllSystems (
        system:
        nixpkgs.legacyPackages.${system}.writeShellScriptBin "nix-fmt" ''
          exec ${nixpkgs.legacyPackages.${system}.nixfmt}/bin/nixfmt \
            $(git ls-files '*.nix' 2>/dev/null || find . -name '*.nix' -not -path './.git/*')
        ''
      );
    };
}
