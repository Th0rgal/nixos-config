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

  sidesystemBridged = pkgs.stdenv.mkDerivation {
    pname = "sidesystem-bridged";
    version = "1.0.0";
    dontUnpack = true;
    buildPhase = ''
      $CC -O2 -Wall -Wextra -Werror ${./android/sidesystem-bridged.c} -o sidesystem-bridged
    '';
    installPhase = ''
      install -Dm755 sidesystem-bridged $out/bin/sidesystem-bridged
    '';
  };

  razrNpuCheck = pkgs.writeShellScriptBin "razr-npu-check" ''
    set -eu
    echo "=== Hexagon V79 NPU Container Diagnostics ==="
    for dev in /dev/fastrpc-cdsp /dev/fastrpc-cdsp-secure; do
      if [ -c "$dev" ]; then
        echo "[OK] $dev ($(ls -l "$dev" | awk '{print $1, $3, $4, $5, $6}'))"
      else
        echo "[MISSING] $dev"
      fi
    done
    if [ -r /run/host-vendor-dsp/cdsp/fastrpc_shell_unsigned_3 ]; then
      echo "[OK] Unsigned CDSP PD shell: /run/host-vendor-dsp/cdsp/fastrpc_shell_unsigned_3"
    else
      echo "[MISSING] /run/host-vendor-dsp/cdsp/fastrpc_shell_unsigned_3 (restart container to mount /vendor/dsp)"
    fi
    if [ -r /run/host-vendor-lib64/libQnnHtpV79Stub.so ]; then
      echo "[OK] QNN HTP V79 stub: /run/host-vendor-lib64/libQnnHtpV79Stub.so"
    else
      echo "[MISSING] /run/host-vendor-lib64/libQnnHtpV79Stub.so"
    fi
    echo "ADSP_LIBRARY_PATH=''${ADSP_LIBRARY_PATH:-<unset>}"
    echo "DSP_LIBRARY_PATH=''${DSP_LIBRARY_PATH:-<unset>}"
  '';

  sidesystemAudioBridge = pkgs.writeShellScript "sidesystem-audio-bridge" ''
    set -u
    export PATH="${
      pkgs.lib.makeBinPath [
        pkgs.pulseaudio
        pkgs.socat
        pkgs.coreutils
      ]
    }:$PATH"
    # Wait for PulseAudio/PipeWire socket to come up
    i=0
    while [ "$i" -lt 30 ]; do
      pactl info >/dev/null 2>&1 && break
      sleep 1
      i=$((i + 1))
    done
    pactl info >/dev/null 2>&1 || exit 0

    # Create virtual sink ("SideSystem Speaker") and source ("SideSystem Mic")
    if ! pactl list short sinks | grep -q "sidesystem_speaker"; then
      pactl load-module module-null-sink sink_name=sidesystem_speaker \
        sink_properties=device.description=SideSystem_Speaker rate=48000 channels=2 >/dev/null 2>&1 || true
    fi
    if ! pactl list short sources | grep -q "sidesystem_mic"; then
      pactl load-module module-pipe-source source_name=sidesystem_mic \
        file=/tmp/sidesystem-mic.pcm format=s16le rate=16000 channels=1 \
        source_properties=device.description=SideSystem_Mic >/dev/null 2>&1 || true
    fi

    # Loopback playback forwarder (SideSystem Speaker monitor -> Android 127.0.0.1:4714)
    (
      while true; do
        if socat -T 2 -u /dev/null TCP:127.0.0.1:4714 2>/dev/null; then
          parec --device=sidesystem_speaker.monitor --format=s16le --rate=48000 --channels=2 --latency-msec=25 2>/dev/null \
            | socat -u STDIN TCP:127.0.0.1:4714 2>/dev/null || true
        fi
        sleep 2
      done
    ) &

    # Loopback microphone reader (Android 127.0.0.1:4715 -> /tmp/sidesystem-mic.pcm)
    while true; do
      if [ -p /tmp/sidesystem-mic.pcm ]; then
        socat -u TCP:127.0.0.1:4715 OPEN:/tmp/sidesystem-mic.pcm,wronly 2>/dev/null || true
      fi
      sleep 2
    done
  '';

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
    linger = true;
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

  # Android containers block CAP_SYS_NICE (rtkit-daemon fails with start-limit-hit)
  # and mount nosuid tmpfs (unix_chkpwd fails in pam_unix for systemd-user).
  # Use pam_permit for internal PID-1-spawned user@1000.service and enable linger
  # so `systemctl --user`, `uwsm-app`, and `xdg-desktop-portal` work with full parity.
  systemd.services.rtkit-daemon.enable = lib.mkForce false;
  security.pam.services.systemd-user.text = lib.mkForce ''
    account required pam_permit.so
    session required pam_limits.so
    session required pam_env.so
    session required pam_systemd.so
  '';

  nixpkgs.overlays = [
    (_final: prev: {
      mission-center = prev.mission-center.overrideAttrs (old: {
        patches = (old.patches or [ ]) ++ [ ./patches/mission-center-kgsl-gpu.patch ];
      });
    })
  ];

  environment.sessionVariables = {
    ADSP_LIBRARY_PATH = "/run/host-vendor-dsp/cdsp;/run/host-vendor-dsp/adsp";
    DSP_LIBRARY_PATH = "/run/host-vendor-dsp/cdsp;/run/host-vendor-dsp/adsp";
  };

  systemd.user.services.sidesystem-audio-bridge = {
    description = "SideSystem Zero-Termux Android Loopback Audio & Mic Bridge";
    wantedBy = [ "default.target" ];
    serviceConfig = {
      ExecStart = "${sidesystemAudioBridge}";
      Restart = "always";
      RestartSec = 3;
    };
  };

  environment.systemPackages = [
    sidesystemUpowerd
    sidesystemFprintd
    sidesystemFido2
    sidesystemBridged
    razrNpuCheck
    mesaRazrKgsl
    aquamarineRazr
    pkgs.fprintd
    pkgs.libfido2
    pkgs.mesa
    pkgs.vulkan-tools
    pkgs.mesa-demos
    pkgs.ffmpeg
    pkgs.pulseaudio
    pkgs.socat
  ];

  # Android secures the phone lockscreen; disable container hypridle auto-lock & keepalive.
  home-manager.users.${user.username} = {
    programs.omarchnix.macos.externalDisplayKeepalive.enable = false;
    services.hypridle.enable = lib.mkForce false;
  };
}
