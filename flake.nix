{
  description = "Thomas's personal NixOS & Home Manager configurations (m2air, m2air-aurora, spark, vm), built on Th0rgal/omarchnix";

  inputs = {
    # Note for Thomas: Nix flakes forbid `if builtins.pathExists ...` inside `inputs`
    # (and pure evaluation sandboxes paths outside `nixos-config/`), so this stays pointed
    # at GitHub for portability across `spark`, VMs, and CI. On machines where
    # `/home/thomas/work/nixos/omarchnix/flake.nix` exists locally, our `nixos-rebuild`
    # and `nh` wrappers (`hosts/common/thomas-user.nix`) automatically inject
    # `--override-input omarchnix path:/home/thomas/work/nixos/omarchnix --no-write-lock-file`.
    omarchnix.url = "github:Th0rgal/omarchnix";

    wallpapers = {
      url = "github:Th0rgal/wallpapers";
      flake = false;
    };

    # Prebuilt weekly `nix-index` database (`nix-locate`, `comma`, command-not-found)
    # so the multi-GiB index never has to be generated on the laptop.
    nix-index-database = {
      url = "github:nix-community/nix-index-database";
      inputs.nixpkgs.follows = "nixpkgs";
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

      # Report-only linters (`statix` anti-patterns, `deadnix` unused bindings):
      # `nix run .#lint`. Neither rewrites files; `nix fmt` stays the only formatter.
      apps = forAllSystems (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          lint = pkgs.writeShellApplication {
            name = "nix-lint";
            runtimeInputs = [
              pkgs.statix
              pkgs.deadnix
            ];
            text = ''
              status=0
              statix check . || status=1
              deadnix --fail . || status=1
              exit "$status"
            '';
          };
        in
        {
          lint = {
            type = "app";
            program = "${lint}/bin/nix-lint";
            meta.description = "Run statix and deadnix over the flake (report-only)";
          };
        }
      );

      devShells = forAllSystems (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
        in
        {
          default = pkgs.mkShellNoCC {
            packages = [
              pkgs.nixfmt
              pkgs.nixd
              pkgs.statix
              pkgs.deadnix
            ];
          };
        }
      );
    };
}
