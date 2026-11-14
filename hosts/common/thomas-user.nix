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
  #   rebuild                 # nh os switch, using ../omarchnix if it is checked out
  #   rebuild boot|test|build|build-vm|dry
  #   rebuild --locked        # ignore the local checkout, build exactly flake.lock
  #   rebuild --bump-orb      # first move omarchnix's `orb` input to Th0rgal/orb master
  #   rebuild -- <args>       # extra arguments forwarded to nix
  #
  # Nothing here ever edits a lock file except `--bump-orb`, and only when asked.
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
      verb=switch
      locked=0
      bump_orb=0
      host=""
      extra=()
      nh_flags=()

      usage() {
        sed -n 's/^  #   //p' "$0" 2>/dev/null || true
        echo "usage: rebuild [switch|boot|test|build|build-vm|dry] [--host NAME] [--locked] [--bump-orb] [-- nix-args...]"
      }

      while [ $# -gt 0 ]; do
        case "$1" in
          switch|boot|test|build|build-vm) verb="$1" ;;
          dry) verb=build; nh_flags+=(--dry) ;;
          --host) host="$2"; shift ;;
          --locked) locked=1 ;;
          --bump-orb) bump_orb=1 ;;
          -h|--help) usage; exit 0 ;;
          --) shift; extra+=("$@"); break ;;
          *) echo "rebuild: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
        esac
        shift
      done

      overrides=()
      if [ "$locked" = 0 ] && [ -f "$omarchnix/flake.nix" ]; then
        if [ "$bump_orb" = 1 ]; then
          echo "rebuild: moving omarchnix's orb input to master" >&2
          nix flake update orb --flake "$omarchnix"
        fi
        echo "rebuild: using local omarchnix at $omarchnix ($(git -C "$omarchnix" rev-parse --short HEAD 2>/dev/null || echo '?')$(git -C "$omarchnix" diff --quiet 2>/dev/null || echo ', dirty'))" >&2
        overrides=(--override-input omarchnix "path:$omarchnix" --no-write-lock-file)
      elif [ "$bump_orb" = 1 ]; then
        echo "rebuild: --bump-orb needs a local omarchnix checkout at $omarchnix" >&2
        exit 2
      fi

      args=(os "$verb" "$flake" "''${nh_flags[@]}")
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
