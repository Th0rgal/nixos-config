# Motorola Razr 60 Ultra — Thomas's pocket NixOS workstation (SideSystem container).
# Shared workstation apps, policies, keyring, IME, remote builder, and backups live in
# `hosts/common/workstation.nix`; Razr hardware/container plumbing lives in `./hardware.nix`.
{
  pkgs,
  inventory,
  ...
}:
{
  imports = [
    ../common/workstation.nix
    ./hardware.nix
  ];

  networking.hostName = "razr";

  omarchnix.mesh =
    inventory.meshByHost.razr or {
      enable = false;
      instances = { };
    };

  services.openssh = {
    enable = true;
    startWhenNeeded = false;
    ports = [ 2222 ];
    settings = {
      PasswordAuthentication = false;
      PermitRootLogin = "no";
      # Pubkey-only SSH inside an Android container avoids pam_unix/unix_chkpwd
      # failures under Android SELinux.
      UsePAM = false;
    };
  };

  environment.systemPackages = with pkgs; [
    # Pure-Wayland headless streaming (`sunshine` UDP + `wayvnc` input/fallback)
    sunshine
    wayvnc
    # Local Adreno 830 Vulkan LLM inference
    (llama-cpp.override { vulkanSupport = true; })
  ];

  # First-install release of this host's state. Never bump it to "upgrade"; it only
  # changes when the machine is reinstalled.
  system.stateVersion = "25.05";
}
