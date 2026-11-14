# Git identity for coding agents (Claude Code / Claude Desktop, OpenCode, Antigravity):
# commits they make are authored and committed as the agent, never as Thomas.
#
#   agentGit.env        GIT_AUTHOR_* / GIT_COMMITTER_* for settings files
#   agentGit.wrap pkg   the package with every bin/ program exporting that env, and
#                       its .desktop entries pointed at the wrapped programs
{ pkgs, agent }:
let
  inherit (pkgs) lib;
  env = {
    GIT_AUTHOR_NAME = agent.name;
    GIT_AUTHOR_EMAIL = agent.email;
    GIT_COMMITTER_NAME = agent.name;
    GIT_COMMITTER_EMAIL = agent.email;
    # Never sign agent commits with Thomas's key (signing is on by default for him).
    GIT_CONFIG_COUNT = "2";
    GIT_CONFIG_KEY_0 = "commit.gpgsign";
    GIT_CONFIG_VALUE_0 = "false";
    GIT_CONFIG_KEY_1 = "tag.gpgsign";
    GIT_CONFIG_VALUE_1 = "false";
  };
  setFlags = lib.concatStringsSep " " (
    lib.mapAttrsToList (k: v: "--set ${k} ${lib.escapeShellArg v}") env
  );
in
{
  inherit env;

  wrap =
    pkg:
    pkgs.symlinkJoin {
      # Same pname/version, so allowUnfreePackages and `pname` lookups still match.
      pname = pkg.pname or (lib.getName pkg);
      version = pkg.version or "";
      paths = [ pkg ];
      nativeBuildInputs = [ pkgs.makeWrapper ];
      postBuild = ''
        for bin in "$out"/bin/*; do
          [ -L "$bin" ] || continue
          target="$(readlink -f "$bin")"
          rm "$bin"
          makeWrapper "$target" "$bin" ${setFlags}
        done
        # Desktop entries that launch the original by store path must use the wrapper.
        # symlinkJoin may link whole directories; turn share/ and share/applications/
        # into real directories of links first.
        realize() {
          if [ -L "$1" ]; then
            target="$(readlink -f "$1")"
            rm "$1"
            mkdir "$1"
            for entry in "$target"/*; do ln -s "$entry" "$1/"; done
          fi
        }
        [ -e "$out/share/applications" ] && { realize "$out/share"; realize "$out/share/applications"; }
        for desktop in "$out"/share/applications/*.desktop; do
          [ -e "$desktop" ] || continue
          src="$(readlink -f "$desktop")"
          rm "$desktop"
          cp "$src" "$desktop"
          chmod u+w "$desktop"
          # Any store path to one of our programs (packages may point at an inner,
          # unwrapped derivation) becomes the wrapped program.
          for bin in "$out"/bin/*; do
            name="$(basename "$bin")"
            sed -i -E "s#/nix/store/[a-z0-9]{32}-[^/ ]+/bin/$name([ \"]|$)#$out/bin/$name\1#g" "$desktop"
          done
        done
      '';
      inherit (pkg) meta;
      passthru = (pkg.passthru or { }) // {
        unwrapped = pkg;
      };
    };
}
