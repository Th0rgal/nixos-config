# Coding agents commit as `inventory.user.agent` (agent@thomas.md) with no tool
# attribution trailers. Claude Code and Claude Desktop's Code tab read
# ~/.claude/settings.json; OpenCode, Antigravity and Claude Desktop are also wrapped
# with the same GIT_AUTHOR_* / GIT_COMMITTER_* variables (lib/agent-git.nix).
{ inventory, pkgs, ... }:
let
  agentGit = import ../../lib/agent-git.nix {
    inherit pkgs;
    inherit (inventory.user) agent;
  };
in
{
  # Shallow merge: these keys are owned here, the rest of the file stays Claude's.
  programs.omarchnix.mutableJson.".claude/settings.json".force = {
    inherit (agentGit) env;
    # No "Co-Authored-By: Claude" commit trailer and no "Generated with" PR footer.
    includeCoAuthoredBy = false;
    attribution = {
      commit = "";
      pr = "";
    };
  };
}
