{
  description = "Thomas's personal NixOS & Home Manager configurations for the MacBook Air M2 and ARM/x86 QEMU test guests, built on Th0rgal/omarchnix";

  inputs = {
    # Note for Thomas: Nix flakes forbid `if builtins.pathExists ...` inside `inputs`
    # (and pure evaluation sandboxes paths outside `nixos-config/`), so this stays pointed
    # at GitHub for portability across VMs and CI. On machines where
    # `/home/thomas/work/nixos/omarchnix/flake.nix` exists locally, our `nixos-rebuild`
    # and `nh` wrappers (`hosts/common/thomas-user.nix`) automatically inject
    # `--override-input omarchnix path:/home/thomas/work/nixos/omarchnix --no-write-lock-file`.
    omarchnix.url = "github:Th0rgal/omarchnix";

    # Private host inventory (fleet SSH map, lean-mesh topology, remote builder),
    # github.com/Th0rgal/nixos-inventory. CI substitutes ./ci/inventory-stub.
    inventory = {
      url = "git+ssh://git@github.com/Th0rgal/nixos-inventory.git";
      flake = false;
    };

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

      # Everything host and home modules know about Thomas and his machines, passed as
      # the `inventory` module argument: public identity from ./inventory, fleet data
      # from the private `inventory` input.
      inventory = import inputs.inventory // {
        user = import ./inventory/user.nix;
        wallpapers = import ./inventory/wallpapers.nix inputs.wallpapers;
      };

      mkPersonalHost =
        args:
        omarchnix.lib.mkHost (
          {
            extraSpecialArgs = { inherit inputs inventory; };
          }
          // args
          // {
            modules = [
              { home-manager.extraSpecialArgs = { inherit inventory; }; }
            ]
            ++ (args.modules or [ ]);
          }
        );

      mkTestVM =
        system:
        mkPersonalHost {
          inherit system;
          platform = "vm";
          modules = [ ./hosts/vm ];
        };

      m2air = mkPersonalHost {
        platform = "m2air-aurora";
        modules = [
          ./hosts/m2air
          ./hosts/m2air/aurora.nix
        ];
      };
    in
    {
      nixosConfigurations = {
        # MacBook Air M2 daily driver with Aurora SEP, Touch ID (fprintd), FIDO2 (aurora-fido) & Orb Touch ID signer
        inherit m2air;

        # Alias kept for existing `--flake .#m2air-aurora` invocations.
        m2air-aurora = m2air;

        # Stock nixos-apple-silicon fallback (without Stage-2 Aurora SEP)
        m2air-stock = mkPersonalHost {
          platform = "m2air-asahi";
          modules = [
            ./hosts/m2air
          ];
        };

        # The same screenshot desktop on the Mac's architecture and on x86 cloud workers.
        vm = mkTestVM "aarch64-linux";
        vm-x86_64 = mkTestVM "x86_64-linux";

        # Motorola Razr 60 Ultra — full Omarchnix desktop inside an Android Linux container.
        razr = mkPersonalHost {
          platform = "container";
          system = "aarch64-linux";
          modules = [ ./hosts/razr ];
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
