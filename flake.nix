{
  description = "Thomas's personal NixOS & Home Manager configurations (m2air, m2air-aurora, spark, vm), built on Th0rgal/omarchnix";

  inputs = {
    omarchnix.url = "github:Th0rgal/omarchnix";

    wallpapers = {
      url = "github:Th0rgal/wallpapers";
      flake = false;
    };

    nixpkgs.follows = "omarchnix/nixpkgs";
    apple-silicon-support.follows = "omarchnix/apple-silicon-support";
    nixos-aurora.follows = "omarchnix/nixos-aurora";
    home-manager.follows = "omarchnix/home-manager";
    sops-nix.follows = "omarchnix/sops-nix";
    orb.follows = "omarchnix/orb";
    folio.follows = "omarchnix/folio";
  };

  outputs =
    {
      nixpkgs,
      omarchnix,
      ...
    }@inputs:
    let
      systems = [
        "aarch64-linux"
        "x86_64-linux"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;

      mkPersonalHost =
        args:
        omarchnix.lib.mkHost (
          {
            extraSpecialArgs = { inherit inputs; };
          }
          // args
        );
    in
    {
      nixosConfigurations = {
        # MacBook Air M2 daily driver with Aurora SEP, Touch ID (fprintd), FIDO2 (aurora-fido) & Orb Touch ID signer
        m2air = mkPersonalHost {
          platform = "m2air-aurora";
          modules = [
            ./hosts/m2air
            ./hosts/m2air/aurora.nix
          ];
        };

        # Explicit alias for Stage-2 Aurora SEP & Touch ID configuration
        m2air-aurora = mkPersonalHost {
          platform = "m2air-aurora";
          modules = [
            ./hosts/m2air
            ./hosts/m2air/aurora.nix
          ];
        };

        # Stock nixos-apple-silicon fallback (without Stage-2 Aurora SEP)
        m2air-stock = mkPersonalHost {
          platform = "m2air-asahi";
          modules = [
            ./hosts/m2air
          ];
        };

        # NVIDIA Grace / DGX Spark ARM64 workstation & server
        spark = mkPersonalHost {
          platform = "spark";
          modules = [
            ./hosts/spark
          ];
        };

        # Turnkey desktop VM (`aarch64-linux` / Apple Silicon QEMU or cloud VM) with preloaded Th0rgal/wallpapers
        vm = mkPersonalHost {
          platform = "vm";
          system = "aarch64-linux";
          modules = [
            ./hosts/vm
          ];
        };

        # Turnkey desktop VM (`x86_64-linux` QEMU or cloud VM) with preloaded Th0rgal/wallpapers
        vm-x86_64 = mkPersonalHost {
          platform = "vm";
          system = "x86_64-linux";
          modules = [
            ./hosts/vm
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
