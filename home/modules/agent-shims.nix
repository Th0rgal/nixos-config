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

in
{
  home.packages = [
    pkgs.bun
    useBunInsteadShims
    pkgs.turbo-lean-guard-shims
  ];
}
