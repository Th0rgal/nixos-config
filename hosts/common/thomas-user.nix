{
  config,
  lib,
  pkgs,
  ...
}:
let
  user = import ../../inventory/user.nix;
  localOmarchnixDir = "/home/${user.username}/work/nixos/omarchnix";
  defaultFlakeDir = "/home/${user.username}/work/nixos/nixos-config";

  # Internal `nix` wrapper used only inside `wrappedNixosRebuild` & `wrappedNh`:
  # When `OMARCHNIX_REF_LOCK` points to a pre-computed ephemeral lockfile matching
  # `--override-input omarchnix path:/home/thomas/work/nixos/omarchnix`, inject
  # `--reference-lock-file "$OMARCHNIX_REF_LOCK"` alongside `--no-write-lock-file`
  # so Nix sees zero lockfile delta and suppresses the multi-line
  # `warning: not writing modified lock file of flake ...` banner without touching
  # the repo's tracked `flake.lock`.
  refLockNix = pkgs.symlinkJoin {
    name = "nix-reflock-${config.nix.package.version}";
    paths = [ config.nix.package ];
    postBuild = ''
      rm -f "$out/bin/nix"
      cat > "$out/bin/nix" <<'EOF'
      #!${pkgs.runtimeShell}
      real_nix="${config.nix.package}/bin/nix"
      if [ -n "''${OMARCHNIX_REF_LOCK:-}" ] && [ -f "''${OMARCHNIX_REF_LOCK}" ]; then
        has_no_write=0
        has_ref_lock=0
        for arg in "$@"; do
          case "$arg" in
            --no-write-lock-file) has_no_write=1 ;;
            --reference-lock-file|--reference-lock-file=*) has_ref_lock=1 ;;
          esac
        done
        if [ "$has_no_write" = "1" ] && [ "$has_ref_lock" = "0" ]; then
          new_args=()
          injected=0
          for arg in "$@"; do
            if [ "$arg" = "--no-write-lock-file" ] && [ "$injected" = "0" ]; then
              new_args+=(--reference-lock-file "$OMARCHNIX_REF_LOCK" --option warn-dirty false)
              injected=1
            fi
            new_args+=("$arg")
          done
          exec "$real_nix" "''${new_args[@]}"
        fi
      fi
      exec "$real_nix" "$@"
      EOF
      chmod 0755 "$out/bin/nix"
    '';
    inherit (config.nix.package) meta;
  };

  # Before every local-omarchnix rebuild, move omarchnix's `orb` input to the
  # latest Th0rgal/orb master so the Alt+O app always ships the newest commit.
  # Runs as the owner of omarchnix/flake.lock (also under sudo), never fails the
  # rebuild (offline keeps the current pin). Opt out with ORB_AUTO_UPDATE=0.
  orbAutoUpdate = pkgs.writeShellScript "orb-auto-update" ''
    [ "''${ORB_AUTO_UPDATE:-1}" = "0" ] && exit 0
    dir="${localOmarchnixDir}"
    [ -f "$dir/flake.lock" ] || exit 0
    owner="$(${pkgs.coreutils}/bin/stat -c %U "$dir/flake.lock")"
    run=()
    [ "$(${pkgs.coreutils}/bin/id -un)" = "$owner" ] || run=(${pkgs.util-linux}/bin/runuser -u "$owner" --)
    # Ask GitHub for master first (well under a second), so a slow tarball fetch can no
    # longer leave the pin silently behind: fetch for as long as it takes, then say loudly
    # whether the pin matches master.
    master="$(${pkgs.coreutils}/bin/timeout 20 ${pkgs.git}/bin/git ls-remote https://github.com/Th0rgal/orb refs/heads/master 2>/dev/null | ${pkgs.coreutils}/bin/cut -c1-40)"
    pinned() { ${pkgs.jq}/bin/jq -r '.nodes.orb.locked.rev // empty' "$dir/flake.lock" 2>/dev/null; }
    if [ -z "$master" ]; then
      echo "orb: cannot reach GitHub; building the pinned $(pinned | ${pkgs.coreutils}/bin/cut -c1-9)" >&2
      exit 0
    fi
    if [ "$(pinned)" != "$master" ]; then
      echo "orb: moving omarchnix's orb pin to master ''${master:0:9} (ORB_AUTO_UPDATE=0 to skip)" >&2
      "''${run[@]}" ${pkgs.coreutils}/bin/timeout 1800 ${config.nix.package}/bin/nix flake update orb \
        --flake "$dir" --option warn-dirty false 2>&1 | ${pkgs.gnugrep}/bin/grep -E "→|error" >&2 || true
    fi
    if [ "$(pinned)" = "$master" ]; then
      echo "orb: building master ''${master:0:9}" >&2
    else
      echo "orb: WARNING: the orb pin is $(pinned | ${pkgs.coreutils}/bin/cut -c1-9), master is ''${master:0:9}; this build keeps the old Orb" >&2
    fi
  '';

  # Transparent system-level `nixos-rebuild` & `nh` wrappers for Thomas:
  # When `/home/thomas/work/nixos/omarchnix/flake.nix` exists on disk, automatically
  # pass `--override-input omarchnix path:/home/thomas/work/nixos/omarchnix --no-write-lock-file`
  # (backed by an ephemeral `--reference-lock-file`) so plain `sudo nixos-rebuild switch`
  # and `nh os switch` evaluate live local `omarchnix` changes cleanly with zero lockfile
  # or dirty-tree warnings and without modifying `flake.lock`.
  # Set `OMARCHNIX_NO_LOCAL_OVERRIDE=1` (or pass `--override-input omarchnix ...` /
  # `--update-input omarchnix` explicitly) to bypass and use `flake.lock` as-is.
  realNixosRebuild = pkgs.nixos-rebuild-ng.override {
    nix = refLockNix;
  };

  wrappedNixosRebuild = pkgs.symlinkJoin {
    name = "nixos-rebuild-local-omarchnix-${realNixosRebuild.version or "ng"}";
    paths = [ realNixosRebuild ];
    postBuild = ''
      rm -f "$out/bin/nixos-rebuild"
      cat > "$out/bin/nixos-rebuild" <<'EOF'
      #!${pkgs.runtimeShell}
      set -u
      real_bin="${realNixosRebuild}/bin/nixos-rebuild"
      real_nix="${config.nix.package}/bin/nix"
      local_dir="${localOmarchnixDir}"

      inject=0
      flake_ref=""
      if [ "''${OMARCHNIX_NO_LOCAL_OVERRIDE:-0}" != "1" ] && [ -f "$local_dir/flake.nix" ]; then
        inject=1
        prev=""
        for arg in "$@"; do
          case "$arg" in
            --help|-h|--version|--no-flake|--rollback|list-generations|edit|--file|-f)
              inject=0
              break
              ;;
            --override-input=*|--update-input=*|--recreate-lock-file|--commit-lock-file)
              inject=0
              break
              ;;
            --flake=*)
              flake_ref="''${arg#--flake=}"
              ;;
          esac
          if [ "$prev" = "--flake" ]; then
            case "$arg" in
              -*) flake_ref="/etc/nixos" ;;
              *) flake_ref="$arg" ;;
            esac
          fi
          if [ "$prev" = "--override-input" ] || [ "$prev" = "--update-input" ] || [ "$prev" = "--store-path" ]; then
            if [ "$prev" = "--store-path" ] || [ "$arg" = "omarchnix" ]; then
              inject=0
              break
            fi
          fi
          prev="$arg"
        done
      fi

      if [ "$inject" = "1" ]; then
        ${orbAutoUpdate}
        [ -n "$flake_ref" ] || flake_ref="/etc/nixos"
        flake_dir="''${flake_ref%%#*}"
        [ -n "$flake_dir" ] || flake_dir="."
        flake_dir="''${flake_dir#git+file://}"
        flake_dir="''${flake_dir#file://}"
        flake_dir="''${flake_dir#path:}"
        flake_dir="''${flake_dir%%\?*}"
        if [ -f "$flake_dir/flake.nix" ]; then
          tmp_lock="$(mktemp "''${TMPDIR:-/tmp}/omarchnix-nrb-flake-XXXXXX.lock")"
          trap 'rm -f "$tmp_lock"' EXIT INT TERM
          if "$real_nix" flake lock "$flake_dir" \
            --override-input omarchnix "path:$local_dir" \
            --output-lock-file "$tmp_lock" \
            --option warn-dirty false >/dev/null 2>&1; then
            chmod 0644 "$tmp_lock" 2>/dev/null || true
            export OMARCHNIX_REF_LOCK="$tmp_lock"
          fi
          "$real_bin" --override-input omarchnix "path:$local_dir" --no-write-lock-file --option warn-dirty false "$@"
          exit $?
        fi
        exec "$real_bin" --override-input omarchnix "path:$local_dir" --no-write-lock-file --option warn-dirty false "$@"
      fi
      exec "$real_bin" "$@"
      EOF
      chmod 0755 "$out/bin/nixos-rebuild"
    '';
    inherit (realNixosRebuild) meta;
  };

  wrappedNh = pkgs.symlinkJoin {
    name = "nh-local-omarchnix-${pkgs.nh.version}";
    paths = [ pkgs.nh ];
    postBuild = ''
      rm -f "$out/bin/nh"
      cat > "$out/bin/nh" <<'EOF'
      #!${pkgs.runtimeShell}
      set -u
      real_bin="${pkgs.nh}/bin/nh"
      real_nix="${config.nix.package}/bin/nix"
      reflock_bin="${refLockNix}/bin"
      local_dir="${localOmarchnixDir}"
      default_flake="${defaultFlakeDir}"

      inject=0
      installable=""
      if [ "''${OMARCHNIX_NO_LOCAL_OVERRIDE:-0}" != "1" ] && [ -f "$local_dir/flake.nix" ]; then
        case "''${1:-}:''${2:-}" in
          os:switch|os:boot|os:test|os:build|os:build-vm|os:build-image|home:switch|home:build)
            inject=1
            skip=0
            idx=0
            prev=""
            for arg in "$@"; do
              idx=$((idx + 1))
              [ "$idx" -le 2 ] && continue
              if [ "$skip" -gt 0 ]; then
                if [ "$prev" = "--override-input" ] || [ "$prev" = "-U" ] || [ "$prev" = "--update-input" ]; then
                  if [ "$arg" = "omarchnix" ]; then
                    inject=0
                    break
                  fi
                fi
                skip=$((skip - 1))
                prev="$arg"
                continue
              fi
              case "$arg" in
                --)
                  break
                  ;;
                --help|-h|--version|-u|--update|--recreate-lock-file|--commit-lock-file|--override-input=*|--update-input=*|-f|--file|--expr)
                  inject=0
                  break
                  ;;
                --option|--override-input)
                  skip=2
                  ;;
                -e|--elevation-strategy|-o|--out-link|-d|--diff|-j|--max-jobs|--cores|--log-format|--builders|-I|--include|-U|--update-input|-H|--hostname|-s|--specialisation|--target-host|--build-host|--image-variant)
                  skip=1
                  ;;
                -*)
                  ;;
                *)
                  [ -n "$installable" ] || installable="$arg"
                  ;;
              esac
              prev="$arg"
            done
            ;;
        esac
      fi

      if [ "$inject" = "1" ]; then
        ${orbAutoUpdate}
        sub1="$1"
        sub2="$2"
        shift 2
        if [ -n "$installable" ]; then
          flake_ref="''${installable%%#*}"
          [ -n "$flake_ref" ] || flake_ref="."
        elif [ "$sub1" = "home" ]; then
          flake_ref="''${NH_HOME_FLAKE:-''${NH_FLAKE:-$default_flake}}"
        else
          flake_ref="''${NH_OS_FLAKE:-''${NH_FLAKE:-$default_flake}}"
        fi
        flake_dir="''${flake_ref%%#*}"
        flake_dir="''${flake_dir#git+file://}"
        flake_dir="''${flake_dir#file://}"
        flake_dir="''${flake_dir#path:}"
        flake_dir="''${flake_dir%%\?*}"
        export PATH="$reflock_bin:$PATH"
        if [ -f "$flake_dir/flake.nix" ]; then
          tmp_lock="$(mktemp "''${TMPDIR:-/tmp}/omarchnix-nh-flake-XXXXXX.lock")"
          trap 'rm -f "$tmp_lock"' EXIT INT TERM
          if "$real_nix" flake lock "$flake_dir" \
            --override-input omarchnix "path:$local_dir" \
            --output-lock-file "$tmp_lock" \
            --option warn-dirty false >/dev/null 2>&1; then
            chmod 0644 "$tmp_lock" 2>/dev/null || true
            export OMARCHNIX_REF_LOCK="$tmp_lock"
          fi
          "$real_bin" "$sub1" "$sub2" --override-input omarchnix "path:$local_dir" --no-write-lock-file --option warn-dirty false "$@"
          exit $?
        fi
        exec "$real_bin" "$sub1" "$sub2" --override-input omarchnix "path:$local_dir" --no-write-lock-file --option warn-dirty false "$@"
      fi
      exec "$real_bin" "$@"
      EOF
      chmod 0755 "$out/bin/nh"
    '';
    inherit (pkgs.nh) meta;
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

  # Replace stock `nixos-rebuild` & `nh` in `/run/current-system/sw/bin` with the
  # local-`omarchnix`-aware wrappers above across all personal hosts (`m2air`, `spark`, `vm`).
  system.tools.nixos-rebuild.enable = false;
  environment.systemPackages = lib.mkIf (config.nix.enable && !config.system.disableInstallerTools) [
    wrappedNixosRebuild
  ];
  programs.nh.package = wrappedNh;

  # Also wrap `sudo nixos-rebuild ...` in interactive zsh even before the very first
  # switch (when `/run/current-system/sw/bin/nixos-rebuild` might still be the old generation).
  programs.zsh.interactiveShellInit = ''
    sudo() {
      if [ "''${1:-}" = "nixos-rebuild" ] && [ "''${OMARCHNIX_NO_LOCAL_OVERRIDE:-0}" != "1" ] && [ -f "${localOmarchnixDir}/flake.nix" ]; then
        shift
        command sudo "${wrappedNixosRebuild}/bin/nixos-rebuild" "$@"
      else
        command sudo "$@"
      fi
    }
  '';
}
