# Core CLI & developer Home Manager profile for Thomas (safe on headless servers and GUI desktops alike).
{
  config,
  ...
}:
let
  user = import ../../inventory/user.nix;
in
{
  imports = [
    ../modules/ssh-config.nix
    ../modules/agent-shims.nix
    ../modules/opencode.nix
  ];

  programs.git = {
    enable = true;
    settings = {
      user = {
        name = user.fullName;
        email = user.email;
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
