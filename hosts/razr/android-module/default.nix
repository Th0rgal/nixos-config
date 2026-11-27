{
  pkgs,
  michelangelus-font,
}:
let
  fontsSpec = [
    {
      id = "IBMPlexSans";
      label = "IBM Plex Sans";
      family = "IBMPlexSans";
    }
    {
      id = "Michelangelus";
      label = "Michelangelus";
      family = "Michelangelus";
    }
    {
      id = "Lilex";
      label = "Lilex";
      family = "Lilex";
    }
  ];

  modulePackage = pkgs.stdenvNoCC.mkDerivation {
    pname = "razr-android-module";
    version = "1.0.0";
    dontUnpack = true;
    preferLocalBuild = true;

    nativeBuildInputs = [
      pkgs.python3
      pkgs.jdk21_headless
      pkgs.apksigner
      pkgs.zip
    ];

    buildPhase = ''
      runHook preBuild

      mod="$TMPDIR/module"
      mkdir -p \
        "$mod/system/product/fonts" \
        "$mod/system/product/etc" \
        "$mod/system/product/overlay" \
        "$mod/system/usr/keylayout"

      cat > "$mod/module.prop" <<'EOF'
      id=razr-omarchnix
      name=Razr 60 Ultra — Omarchnix Fonts, Keymaps & Tweaks
      version=1.1.0
      versionCode=110
      author=thomas
      description=Declarative Nix-built Magisk/KernelSU module adding Omarchnix fonts (IBM Plex Sans, Michelangelus, Lilex) to Moto Personalize, left AI key -> F13 remap, and boot-time Android tweaks.
      EOF

      cat > "$mod/service.sh" <<'EOF'
      #!/system/bin/sh
      # Wait until Android boot completes before applying device_config / settings.
      while [ "$(getprop sys.boot_completed)" != "1" ]; do
        sleep 2
      done

      # Keep SideSystem / Termux Linux containers alive (disable Android 12-15 phantom process killer).
      /system/bin/device_config set_sync_disabled_for_tests persistent
      /system/bin/device_config put activity_manager max_phantom_processes 2147483647
      settings put global settings_enable_monitor_phantom_procs false

      # Snappy, calm 0.5x system animation scales (consistent with Omarchnix desktop feel).
      settings put global window_animation_scale 0.5
      settings put global transition_animation_scale 0.5
      settings put global animator_duration_scale 0.5

      # Set Monolith as the default back/cover screen launcher (both package & component).
      if pm path de.mm20.launcher2.debug >/dev/null 2>&1; then
        cmd overlay fabricate --target android --name monolith_cover_home android:string/config_secondaryHomePackage 0x03 de.mm20.launcher2.debug 2>/dev/null || true
        cmd overlay enable com.android.shell:monolith_cover_home 2>/dev/null || true
        cmd overlay fabricate --target android --name monolith_cover_component android:string/config_secondaryHomeComponent 0x03 de.mm20.launcher2.debug/de.mm20.launcher2.ui.starship.MonolithCoverActivity 2>/dev/null || true
        cmd overlay enable com.android.shell:monolith_cover_component 2>/dev/null || true
        pm grant de.mm20.launcher2.debug android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
        if [ -x /data/adb/lspd/cli ]; then
          /data/adb/lspd/cli modules enable de.mm20.launcher2.debug >/dev/null 2>&1 || true
        fi
      fi

      # Clean status bar: hide noisy system icons, strip app notification icons on the left,
      # and keep IBM Plex Sans active as the system UI font (lockscreen dates, status bar, menus).
      cmd overlay enable-exclusive com.android.theme.font.IBMPlexSans 2>/dev/null || true
      settings put secure icon_blacklist nfc,ims,alarm_clock,bluetooth,volume,mute,headset,rotate,data_saver,managed_profile,zen
      cmd overlay enable-exclusive com.android.internal.systemui.navbar.gestural 2>/dev/null || true
      settings put secure navigation_mode 2
      cmd overlay fabricate --target com.android.systemui --name monolith_statusbar_clean com.android.systemui:integer/max_notif_static_icons 0x10 0 2>/dev/null || true
      cmd overlay enable com.android.shell:monolith_statusbar_clean 2>/dev/null || true

      # Keep core personal apps exempt from Doze/App Standby throttling.
      for pkg in de.mm20.launcher2.debug com.th0rgal.orbvoice com.th0rgal.sidesystem; do
        dumpsys deviceidle whitelist "+$pkg" >/dev/null 2>&1 || true
      done

      # Apply safe, reversible debloat list (carrier bloat, telemetry, demo, live wallpapers).
      if [ -f /data/adb/modules/razr-omarchnix/debloat.txt ]; then
        while IFS= read -r pkg; do
          case "$pkg" in
            ""|\#*) continue ;;
          esac
          pm disable-user --user 0 "$pkg" >/dev/null 2>&1 || true
        done < /data/adb/modules/razr-omarchnix/debloat.txt
      fi
      EOF
      chmod 0755 "$mod/service.sh"

      cp "${./debloat.txt}" "$mod/debloat.txt"
      cp "${./monolith-cover-watch.sh}" "$mod/monolith-cover-watch.sh"
      chmod 0755 "$mod/monolith-cover-watch.sh"

      # Remap left AI key (scancode 217) from ASSIST to F13 (replaces standalone razr-ai-key module)
      cp "${./keylayout/gpio-keys.kl}" "$mod/system/usr/keylayout/gpio-keys.kl"

      # Copy Omarchnix / nixos-config TrueType font files into /product/fonts
      for f in Regular Medium Bold Italic MediumItalic BoldItalic; do
        cp "${pkgs.ibm-plex}/share/fonts/truetype/IBMPlexSans-$f.ttf" "$mod/system/product/fonts/"
        cp "${pkgs.lilex}/share/fonts/truetype/Lilex-$f.ttf" "$mod/system/product/fonts/"
      done
      for f in Regular Bold Italic BoldItalic; do
        cp "${michelangelus-font}/share/fonts/truetype/michelangelus/Michelangelus-$f.ttf" "$mod/system/product/fonts/"
      done

      cp "${./fonts_customization.xml}" "$mod/system/product/etc/fonts_customization.xml"

      # Generate an ephemeral signing key for RRO overlay APKs (product-partition RROs
      # only require any valid v2/v3 APK signature).
      keytool -genkeypair -noprompt \
        -keystore "$TMPDIR/rro.keystore" \
        -alias razr-rro \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass razr-rro-key -keypass razr-rro-key \
        -dname "CN=Razr Omarchnix RRO, OU=Personal, O=Th0rgal, L=Local, C=FR"

      ${pkgs.lib.concatMapStringsSep "\n" (font: ''
        mkdir -p "$mod/system/product/overlay/Font${font.id}"
        python3 "${./build-font-rro.py}" \
          --package "com.android.theme.font.${font.id}" \
          --label "${font.label}" \
          --family "${font.family}" \
          --out "$TMPDIR/Font${font.id}Overlay-unsigned.apk"
        apksigner sign \
          --ks "$TMPDIR/rro.keystore" \
          --ks-key-alias razr-rro \
          --ks-pass pass:razr-rro-key \
          --key-pass pass:razr-rro-key \
          --v1-signing-enabled true \
          --v2-signing-enabled true \
          --v3-signing-enabled true \
          --v4-signing-enabled false \
          --out "$mod/system/product/overlay/Font${font.id}/Font${font.id}Overlay.apk" \
          "$TMPDIR/Font${font.id}Overlay-unsigned.apk"
        apksigner verify "$mod/system/product/overlay/Font${font.id}/Font${font.id}Overlay.apk"
      '') fontsSpec}

      chmod -R a+rX "$mod"
      chmod 0755 "$mod/service.sh"

      runHook postBuild
    '';

    installPhase = ''
      runHook preInstall
      mkdir -p "$out"
      cp -r "$TMPDIR/module" "$out/module"
      (cd "$out/module" && zip -qr "$out/razr-omarchnix-mod.zip" .)
      runHook postInstall
    '';

    meta = {
      description = "Declarative Nix-built Magisk/KernelSU module for Motorola Razr 60 Ultra (Omarchnix fonts & tweaks)";
      platforms = pkgs.lib.platforms.linux;
    };
  };

  deployApp = pkgs.writeShellApplication {
    name = "razr-deploy-android-module";
    runtimeInputs = [
      pkgs.android-tools
      pkgs.gnutar
      pkgs.coreutils
    ];
    text = ''
      set -euo pipefail

      DO_REBOOT=0
      for arg in "$@"; do
        case "$arg" in
          --reboot|-r) DO_REBOOT=1 ;;
          --help|-h)
            echo "Usage: razr-deploy-android-module [--reboot]"
            echo "Deploys the Nix-built Magisk/KernelSU module (${modulePackage}/module)"
            echo "to /data/adb/modules/razr-omarchnix on a connected Razr over ADB."
            exit 0
            ;;
          *)
            echo "Unknown argument: $arg" >&2
            exit 1
            ;;
        esac
      done

      if ! adb get-state >/dev/null 2>&1; then
        echo "Error: no ADB device connected. Plug in the Razr 60 Ultra with USB debugging enabled." >&2
        exit 1
      fi

      echo "Deploying razr-omarchnix Magisk/KernelSU module from ${modulePackage}/module ..."
      tar -C "${modulePackage}/module" -cf - . \
        | adb shell "su -c 'rm -rf /data/adb/modules/razr-omarchnix && mkdir -p /data/adb/modules/razr-omarchnix && tar -C /data/adb/modules/razr-omarchnix -xf - && chown -R root:root /data/adb/modules/razr-omarchnix && chmod 0755 /data/adb/modules/razr-omarchnix/service.sh && chcon -R u:object_r:system_file:s0 /data/adb/modules/razr-omarchnix/system 2>/dev/null || true; [ -d /data/adb/modules/razr-ai-key ] && touch /data/adb/modules/razr-ai-key/disable || true'"

      echo "Installed to /data/adb/modules/razr-omarchnix."

      if [ "$DO_REBOOT" -eq 1 ]; then
        echo "Rebooting device to mount systemless overlays..."
        adb reboot
        adb wait-for-device
        echo "Waiting for Android boot_completed..."
        while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
          sleep 2
        done
        sleep 3
        echo "Verifying registered font overlays:"
        adb shell "su -c 'cmd overlay list | grep -iE \"IBMPlexSans|Michelangelus|Lilex\"'"
      else
        echo "Reboot the Razr (or re-run with --reboot) to activate the new fonts in Moto -> Personalize -> Fonts."
      fi
    '';
  };
in
{
  package = modulePackage;
  deploy = deployApp;
}
