# Motorola Razr 60 Ultra — SideSystem Android Linux container hardware module.
#
# Symmetric counterpart to `hosts/m2air/hardware.nix` + `omarchnix.hardware.m2air`:
# provides Adreno 830 GPU acceleration (`mesa-razr-kgsl`), pure-GPU standalone headless
# Hyprland (`aquamarine-razr`), hardware-backed Touch ID (`sidesystem-fprintd` +
# `pam_fprintd.so` + `TouchId.qml`), StrongBox FIDO2/CTAP2 (`sidesystem-fido2` over
# `/dev/uhid`), and live battery / Power Efficiency Mode (`sidesystem-upowerd`).
{
  lib,
  modulesPath,
  pkgs,
  inventory,
  ...
}:
let
  inherit (inventory) user;

  sidesystemUpowerd = pkgs.stdenv.mkDerivation {
    pname = "sidesystem-upowerd";
    version = "1.0.0";
    dontUnpack = true;
    nativeBuildInputs = [ pkgs.pkg-config ];
    buildInputs = [ pkgs.glib ];
    buildPhase = ''
      $CC -O2 -Wall -Wextra ${./daemons/sidesystem-upowerd.c} -o sidesystem-upowerd \
        $(pkg-config --cflags --libs gio-2.0 gio-unix-2.0) -lm
    '';
    installPhase = ''
      install -Dm755 sidesystem-upowerd $out/bin/sidesystem-upowerd
    '';
  };

  sidesystemFprintd = pkgs.stdenv.mkDerivation {
    pname = "sidesystem-fprintd";
    version = "1.0.0";
    dontUnpack = true;
    nativeBuildInputs = [ pkgs.pkg-config ];
    buildInputs = [ pkgs.glib ];
    buildPhase = ''
      $CC -O2 -Wall -Wextra ${./daemons/sidesystem-fprintd.c} -o sidesystem-fprintd \
        $(pkg-config --cflags --libs gio-2.0 gio-unix-2.0)
    '';
    installPhase = ''
      install -Dm755 sidesystem-fprintd $out/bin/sidesystem-fprintd
    '';
  };

  sidesystemFido2 = pkgs.stdenv.mkDerivation {
    pname = "sidesystem-fido2";
    version = "1.0.0";
    dontUnpack = true;
    buildPhase = ''
      $CC -O2 -Wall -Wextra ${./daemons/sidesystem-fido2.c} -o sidesystem-fido2
    '';
    installPhase = ''
      install -Dm755 sidesystem-fido2 $out/bin/sidesystem-fido2
    '';
  };

  sidesystemDbusPolicy = pkgs.writeTextDir "share/dbus-1/system.d/sidesystem.conf" ''
    <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
     "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
    <busconfig>
      <policy user="root">
        <allow own="org.freedesktop.UPower"/>
        <allow own="org.freedesktop.UPower.PowerProfiles"/>
        <allow own="net.hadess.PowerProfiles"/>
        <allow own="net.reactivated.Fprint"/>
      </policy>
      <policy context="default">
        <allow send_destination="org.freedesktop.UPower"/>
        <allow send_destination="org.freedesktop.UPower.PowerProfiles"/>
        <allow send_destination="net.hadess.PowerProfiles"/>
        <allow send_destination="net.reactivated.Fprint"/>
      </policy>
    </busconfig>
  '';

  mesaRazrKgsl =
    (pkgs.mesa.override {
      galliumDrivers = [
        "zink"
        "llvmpipe"
        "virgl"
      ];
      vulkanDrivers = [ "freedreno" ];
      vulkanLayers = [ ];
    }).overrideAttrs
      (old: {
        pname = "mesa-razr-kgsl";
        patches = (old.patches or [ ]) ++ [ ./patches/mesa-razr-kgsl.patch ];
        mesonFlags =
          (builtins.filter (
            f:
            !(lib.hasPrefix "-Dgallium-rusticl=" f)
            && !(lib.hasPrefix "-Dtools=" f)
            && !(lib.hasPrefix "-Dteflon=" f)
          ) (old.mesonFlags or [ ]))
          ++ [
            "-Dgallium-rusticl=false"
            "-Dtools="
            "-Dteflon=false"
          ];
        postInstall = (old.postInstall or "") + "\nmkdir -p $spirv2dxil $opencl $cross_tools\n";
        postFixup = builtins.replaceStrings [ " $opencl/lib/libRusticlOpenCL.so" ] [ "" ] (
          old.postFixup or ""
        );
      });

  aquamarineRazr = pkgs.aquamarine.overrideAttrs (old: {
    pname = "aquamarine-razr";
    patches = (old.patches or [ ]) ++ [ ./patches/aquamarine-headless-drm.patch ];
  });
in
{
  imports = [ (modulesPath + "/virtualisation/lxc-container.nix") ];

  thomas.workstation.wrapElectronForContainer = true;

  # Android owns the shared host network namespace and interfaces; provide static DNS.
  networking = {
    useHostResolvConf = false;
    resolvconf.enable = false;
    nameservers = [
      "1.1.1.1"
      "9.9.9.9"
    ];
    useDHCP = false;
    firewall.enable = false;
  };

  # Android SELinux denies `associate` on unlabeled `ramfs`; use `tmpfs` for sops-nix.
  sops.useTmpfs = true;

  omarchnix = {
    # Android owns the host firewall, AppArmor/SELinux policy, and kernel sysctls.
    hardening.enable = lib.mkForce false;
    containers.enable = false;
    desktop.macos = {
      touchIdHud = true;
      gestures = false;
    };
  };

  # Replace stock upowerd/power-profiles-daemon with SideSystem's sysfs-backed D-Bus daemons.
  services.upower.enable = lib.mkForce false;
  services.power-profiles-daemon.enable = lib.mkForce false;
  services.fprintd.enable = lib.mkForce false;
  services.dbus.packages = [ sidesystemDbusPolicy ];

  systemd.services.sidesystem-upowerd = {
    description = "SideSystem UPower & PowerProfiles D-Bus daemon (sysfs battery + Snapdragon profiles)";
    wantedBy = [ "multi-user.target" ];
    after = [ "dbus.service" ];
    serviceConfig = {
      ExecStart = "${sidesystemUpowerd}/bin/sidesystem-upowerd";
      Restart = "always";
      RestartSec = 2;
    };
  };

  systemd.services.sidesystem-fprintd = {
    description = "SideSystem net.reactivated.Fprint D-Bus daemon (Android StrongBox Touch ID)";
    wantedBy = [ "multi-user.target" ];
    after = [ "dbus.service" ];
    path = [ pkgs.openssl ];
    serviceConfig = {
      ExecStart = "${sidesystemFprintd}/bin/sidesystem-fprintd";
      Restart = "always";
      RestartSec = 2;
    };
  };

  systemd.services.sidesystem-fido2 = {
    description = "SideSystem virtual UHID FIDO2/CTAP2 hardware authenticator";
    wantedBy = [ "multi-user.target" ];
    serviceConfig = {
      ExecStart = "${sidesystemFido2}/bin/sidesystem-fido2";
      Restart = "always";
      RestartSec = 2;
    };
  };

  # Hardware-backed Razr side-button Touch ID via standard pam_fprintd.so -> sidesystem-fprintd
  # (automatically drives Omarchnix's frosted-glass TouchId.qml HUD).
  security.pam.services = {
    sudo.rules.auth.sidesystem-fprintd = {
      order = 11400;
      control = "sufficient";
      modulePath = "${pkgs.fprintd}/lib/security/pam_fprintd.so";
    };
    polkit-1.rules.auth.sidesystem-fprintd = {
      order = 11400;
      control = "sufficient";
      modulePath = "${pkgs.fprintd}/lib/security/pam_fprintd.so";
    };
    hyprlock.rules.auth.sidesystem-fprintd = {
      order = 11400;
      control = "sufficient";
      modulePath = "${pkgs.fprintd}/lib/security/pam_fprintd.so";
    };
  };

  # Map Android supplementary GIDs so user processes have native access to
  # paranoid networking (`aid_inet`) and GPU/input nodes (`/dev/kgsl-3d0`, `/dev/dri`).
  users.groups = {
    droidspaces-gpu.gid = 786;
    aid_graphics.gid = 1003;
    aid_input.gid = 1004;
    aid_inet.gid = 3003;
  };

  users.users.${user.username} = {
    # Non-loginable password hash (`*`) satisfies PAM account checks for SSH pubkey
    # logins without setting a plaintext password (`!` causes pam_unix to reject).
    hashedPassword = "*";
    extraGroups = [
      "wheel"
      "video"
      "render"
      "audio"
      "input"
      "droidspaces-gpu"
      "aid_inet"
      "aid_graphics"
      "aid_input"
    ];
  };

  # Android's tmpfs does not support `security.capability` xattrs, which causes
  # `suid-sgid-wrappers.service` to abort before creating `unix_chkpwd`/`sudo`.
  security.wrappers = {
    gnome-keyring-daemon = {
      capabilities = lib.mkForce "";
      setuid = lib.mkForce false;
    };
    nethogs = {
      capabilities = lib.mkForce "";
      setuid = lib.mkForce true;
    };
    newgidmap = {
      capabilities = lib.mkForce "";
      setuid = lib.mkForce true;
    };
    newuidmap = {
      capabilities = lib.mkForce "";
      setuid = lib.mkForce true;
    };
  };

  environment.systemPackages = [
    sidesystemUpowerd
    sidesystemFprintd
    sidesystemFido2
    mesaRazrKgsl
    aquamarineRazr
    pkgs.fprintd
    pkgs.libfido2
    pkgs.mesa
    pkgs.vulkan-tools
    pkgs.mesa-demos
    pkgs.ffmpeg
    pkgs.pulseaudio
  ];

  # Android secures the phone lockscreen; disable container hypridle auto-lock & keepalive.
  home-manager.users.${user.username} = {
    programs.omarchnix.macos.externalDisplayKeepalive.enable = false;
    services.hypridle.enable = lib.mkForce false;
  };
}
