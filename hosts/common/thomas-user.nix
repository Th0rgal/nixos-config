{
  config,
  lib,
  pkgs,
  inventory,
  ...
}:
let
  inherit (inventory) user;
  workDir = "/home/${user.username}/work/nixos";

  # Single explicit entry point for rebuilding this machine. Stock `nixos-rebuild`
  # and `nh` stay untouched; this script only decides which inputs to pass.
  #
  #   rebuild                 # nh os switch, using ../omarchnix if it is checked out and the latest Orb master
  #   rebuild boot|test|build|build-vm|dry
  #   rebuild --local         # skip remote builders and build locally (`--option builders ""`)
  #   rebuild --locked        # ignore the local checkout and Orb master, build exactly flake.lock
  #   rebuild --bump-orb      # also write Th0rgal/orb master into omarchnix's flake.lock
  #   rebuild -- <args>       # extra arguments forwarded to nix
  #
  # Nothing here ever edits a lock file except `--bump-orb`, and only when asked: the
  # latest Orb comes from a build-time `--override-input omarchnix/orb`, so checkouts
  # shared with other sessions stay untouched.
  rebuild = pkgs.writeShellApplication {
    name = "rebuild";
    runtimeInputs = [
      config.programs.nh.package
      config.nix.package
      pkgs.git
      pkgs.coreutils
    ];
    text = ''
      flake="''${REBUILD_FLAKE:-${workDir}/nixos-config}"
      omarchnix="''${REBUILD_OMARCHNIX:-${workDir}/omarchnix}"
      scribe="''${REBUILD_SCRIBE:-/home/${user.username}/work/scribe}"
      verb=switch
      local_only=0
      locked=0
      bump_orb=0
      host=""
      extra=()

      usage() {
        sed -n 's/^  #   //p' "$0" 2>/dev/null || true
        echo "usage: rebuild [switch|boot|test|build|build-vm|dry] [--host NAME] [--local] [--locked] [--bump-orb] [-- nix-args...]"
      }

      while [ $# -gt 0 ]; do
        case "$1" in
          switch|boot|test|build|build-vm) verb="$1" ;;
          dry) verb=dry ;;
          --host) host="$2"; shift ;;
          --local) local_only=1 ;;
          --locked) locked=1 ;;
          --bump-orb) bump_orb=1 ;;
          -h|--help) usage; exit 0 ;;
          --) shift; extra+=("$@"); break ;;
          *) echo "rebuild: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
        esac
        shift
      done

      if [ "$local_only" = 1 ]; then
        echo "rebuild: building locally (remote builders disabled)" >&2
        extra+=(--option builders "")
      fi

      overrides=()
      if [ "$locked" = 0 ] && [ -f "$omarchnix/flake.nix" ]; then
        if [ "$bump_orb" = 1 ]; then
          echo "rebuild: moving omarchnix's orb input to master" >&2
          nix flake update --refresh orb --flake "$omarchnix"
        fi
        echo "rebuild: using local omarchnix at $omarchnix ($(git -C "$omarchnix" rev-parse --short HEAD 2>/dev/null || echo '?')$(git -C "$omarchnix" diff --quiet 2>/dev/null || echo ', dirty'))" >&2
        overrides=(--override-input omarchnix "path:$omarchnix" --no-write-lock-file)
        if [ -f "$scribe/flake.nix" ]; then
          echo "rebuild: using local scribe at $scribe ($(git -C "$scribe" rev-parse --short HEAD 2>/dev/null || echo '?')$(git -C "$scribe" diff --quiet 2>/dev/null || echo ', dirty'))" >&2
          overrides+=(--override-input omarchnix/scribe "path:$scribe")
        fi
        if [ "$bump_orb" = 0 ]; then
          orb_ref=$(git ls-remote --exit-code https://github.com/Th0rgal/orb.git refs/heads/master)
          read -r orb_rev _ <<<"$orb_ref"
          echo "rebuild: using Orb master at $orb_rev (rebuild --locked builds the pinned one)" >&2
          overrides+=(--override-input omarchnix/orb "github:Th0rgal/orb/$orb_rev")
        fi
      elif [ "$bump_orb" = 1 ]; then
        echo "rebuild: --bump-orb needs a local omarchnix checkout at $omarchnix" >&2
        exit 2
      fi

      if [ "$verb" = dry ]; then
        if [ -z "$host" ]; then read -r host </proc/sys/kernel/hostname; fi
        exec nix build --dry-run --no-link "$flake#nixosConfigurations.$host.config.system.build.toplevel" "''${overrides[@]}" "''${extra[@]}"
      fi
      args=(os "$verb" "$flake")
      [ -n "$host" ] && args+=(--hostname "$host")
      exec nh "''${args[@]}" -- "''${overrides[@]}" "''${extra[@]}"
    '';
  };
in
{
  users.users.${user.username} = {
    isNormalUser = true;
    uid = 1000;
    extraGroups = lib.mkDefault [
      "wheel"
      "video"
      "render"
      "audio"
      "networkmanager"
    ];
    shell = "/run/current-system/sw/bin/zsh";
    openssh.authorizedKeys.keys = lib.mkDefault user.sshKeys.admin;
  };

  security.sudo.wheelNeedsPassword = lib.mkDefault true;

  # Suppress noisy `warning: Git tree '...' is dirty` banners during local flake iteration.
  nix.settings.warn-dirty = lib.mkDefault false;

  environment.systemPackages = [ rebuild ];
}
