# Core CLI & developer Home Manager profile for Thomas (safe on headless servers and GUI desktops alike).
{
  config,
  inputs,
  pkgs,
  inventory,
  ...
}:
let
  inherit (inventory) user;
in
{
  imports = [
    ../modules/ssh-config.nix
    ../modules/agent-shims.nix
    ../modules/opencode.nix
    ../modules/agent-identity.nix
    inputs.nix-index-database.homeModules.nix-index
  ];

  # `nix-locate <file>` and `, <command>` (run any nixpkgs program without installing it),
  # backed by the prebuilt database from `inputs.nix-index-database`.
  programs.nix-index.enable = true;
  programs.nix-index-database.comma.enable = true;

  # Syntax-highlighted, side-aware diffs for `git diff` / `git show` / `git log -p`.
  programs.delta = {
    enable = true;
    enableGitIntegration = true;
    options = {
      navigate = true;
      line-numbers = true;
    };
  };

  home.packages = [
    pkgs.nix-tree # interactive closure-size browser: `nix-tree /run/current-system`
    pkgs.nixd # Nix language server for editors
  ];

  programs.git = {
    enable = true;
    # Every commit and tag is signed with the SSH key; `git log --show-signature`
    # verifies locally against allowed_signers.
    signing = {
      format = "ssh";
      key = "${config.home.homeDirectory}/.ssh/thomas-signing.pub";
      signByDefault = true;
      # Agent commits (lib/agent-git.nix) are signed with the same key under the
      # agent address.
      allowedSigners = ''
        ${user.email} namespaces="git" ${user.sshKeys.gitSigning}
        ${user.agent.email} namespaces="git" ${user.sshKeys.gitSigning}
      '';
    };
    settings = {
      user = {
        name = user.fullName;
        inherit (user) email;
      };
      init.defaultBranch = "main";
      pull.rebase = false;
    };
  };

  programs.fastfetch.enable = true;

  # Keep ~/ minimal: only ~/Downloads and ~/Pictures are created; unused XDG
  # folders point at $HOME per the freedesktop spec so browsers/GTK apps do not
  # recreate ~/Music, ~/Videos, ~/Documents, ~/Desktop, ~/Templates, or ~/Public.
  xdg.userDirs = {
    enable = true;
    createDirectories = true;
    download = "${config.home.homeDirectory}/Downloads";
    pictures = "${config.home.homeDirectory}/Pictures";
    desktop = config.home.homeDirectory;
    documents = config.home.homeDirectory;
    music = config.home.homeDirectory;
    videos = config.home.homeDirectory;
    publicShare = config.home.homeDirectory;
    templates = config.home.homeDirectory;
    extraConfig.PROJECTS = "${config.home.homeDirectory}/work";
  };
}
