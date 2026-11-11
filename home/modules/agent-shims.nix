{ pkgs, ... }:
let
  # Guard shims on $PATH so coding agents and muscle-memory commands immediately
  # see a clear message to use `bun` / `bunx` instead of npm/pnpm/yarn.
  useBunInsteadShims = pkgs.runCommand "use-bun-instead-shims" { } ''
    mkdir -p "$out/bin"
    for cmd in npm pnpm yarn; do
      cat > "$out/bin/$cmd" <<EOF
    #!${pkgs.runtimeShell}
    echo "warning: '$cmd' is disabled on this machine — you should use 'bun' instead (e.g. 'bun install', 'bun add <pkg>', 'bun run <script>', 'bunx <tool>')." >&2
    exit 1
    EOF
      chmod 0755 "$out/bin/$cmd"
    done
    for cmd in npx pnpx; do
      cat > "$out/bin/$cmd" <<EOF
    #!${pkgs.runtimeShell}
    echo "warning: '$cmd' is disabled on this machine — you should use 'bunx' instead (e.g. 'bunx <tool> ...')." >&2
    exit 1
    EOF
      chmod 0755 "$out/bin/$cmd"
    done
  '';

  # Guard shims on $PATH so coding agents and local shells use `turbo-lean`
  # instead of spawning unshared multi-GiB `lake` builds or downloading `~/.elan` toolchains.
  useTurboLeanInsteadShims = pkgs.runCommand "use-turbo-lean-instead-shims" { } ''
    mkdir -p "$out/bin"
    for cmd in lake leanc; do
      {
        echo "#!${pkgs.runtimeShell}"
        cat <<'SHIM'
    set -u
    cmd="$(basename "$0")"
    self_real="$(readlink -f "$0" 2>/dev/null || printf '%s' "$0")"

    find_next_bin() {
      for cand in "''${LEAN_BIN_DIR:-}/$cmd" "''${ELAN_HOME:-}/bin/$cmd" "''${HOME:-}/.elan/bin/$cmd"; do
        [ -n "$cand" ] && [ -x "$cand" ] || continue
        cand_real="$(readlink -f "$cand" 2>/dev/null || printf '%s' "$cand")"
        [ "$cand_real" = "$self_real" ] && continue
        printf '%s\n' "$cand"
        return 0
      done
      old_ifs=$IFS; IFS=:
      for dir in ''${PATH:-}; do
        [ -n "$dir" ] || continue
        cand="$dir/$cmd"
        [ -x "$cand" ] || continue
        cand_real="$(readlink -f "$cand" 2>/dev/null || printf '%s' "$cand")"
        [ "$cand_real" = "$self_real" ] && continue
        IFS=$old_ifs
        printf '%s\n' "$cand"
        return 0
      done
      IFS=$old_ifs
      return 1
    }

    if [ "''${SANDBOXED_ALLOW_RAW_LAKE:-0}" = "1" ] || [ "''${SANDBOXED_REMOTE_EXECUTION:-0}" = "1" ]; then
      if real_bin="$(find_next_bin)"; then
        exec "$real_bin" "$@"
      fi
      echo "error: SANDBOXED_ALLOW_RAW_LAKE=1 was set, but no underlying '$cmd' binary was found on PATH (use 'turbo-lean' directly, or enter 'nix develop' if raw Lake is strictly required)." >&2
      exit 127
    fi

    cat >&2 <<EOF
    warning: '$cmd''${1:+ $1}' is disabled by default on this machine — use 'turbo-lean' instead (shared CAS + native clang codegen without multi-GiB .lake duplication):
      - build targets:     turbo-lean build --project-dir <dir> [targets...]
      - check single file: turbo-lean check <path/to/File.lean> [--decl <Full.DeclName>]
      - audit formal spec: turbo-lean audit-spec <path/to/Spec.lean>
      - Orb binaries:      ./scripts/build_orb_bins.sh --only <bin>
    (Set SANDBOXED_ALLOW_RAW_LAKE=1 only if raw Lake is strictly required, e.g. 'lake update'.)
    EOF
    exit 1
    SHIM
      } > "$out/bin/$cmd"
      chmod 0755 "$out/bin/$cmd"
    done

    {
      echo "#!${pkgs.runtimeShell}"
      cat <<'SHIM'
    set -u
    self_real="$(readlink -f "$0" 2>/dev/null || printf '%s' "$0")"

    if [ "''${SANDBOXED_ALLOW_RAW_ELAN:-0}" = "1" ]; then
      old_ifs=$IFS; IFS=:
      for dir in ''${PATH:-}; do
        [ -n "$dir" ] && [ -x "$dir/elan" ] || continue
        cand_real="$(readlink -f "$dir/elan" 2>/dev/null || printf '%s' "$dir/elan")"
        [ "$cand_real" = "$self_real" ] && continue
        IFS=$old_ifs
        exec "$dir/elan" "$@"
      done
      IFS=$old_ifs
      echo "error: SANDBOXED_ALLOW_RAW_ELAN=1 was set, but no underlying 'elan' binary was found on PATH." >&2
      exit 127
    fi

    cat >&2 <<EOF
    warning: 'elan''${1:+ $1}' is disabled on this machine — Lean toolchains are managed declaratively via Nix and 'turbo-lean' (never install private ~/.elan toolchains):
      - build targets:     turbo-lean build --project-dir <dir> [targets...]
      - check single file: turbo-lean check <path/to/File.lean> [--decl <Full.DeclName>]
      - audit formal spec: turbo-lean audit-spec <path/to/Spec.lean>
    (Set SANDBOXED_ALLOW_RAW_ELAN=1 only if raw elan is strictly required.)
    EOF
    exit 1
    SHIM
    } > "$out/bin/elan"
    chmod 0755 "$out/bin/elan"
  '';
in
{
  home.packages = [
    pkgs.bun
    useBunInsteadShims
    useTurboLeanInsteadShims
  ];
}
