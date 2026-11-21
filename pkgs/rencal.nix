{
  lib,
  stdenv,
  fetchurl,
  dpkg,
  autoPatchelfHook,
  wrapGAppsHook3,
  gtk3,
  webkitgtk_4_1,
  wayland,
  dbus,
  libnotify,
  xdg-utils,
  libayatana-appindicator,
}:
stdenv.mkDerivation (finalAttrs: {
  pname = "rencal";
  version = "0.10.0";

  src = fetchurl {
    url = "https://github.com/t4t5/rencal/releases/download/v${finalAttrs.version}/renCal_${finalAttrs.version}_arm64.deb";
    hash = "sha256-4dMKyu630zuyEXA6/eA+osRcL8CjnpxJL6nxC50/WeY=";
  };

  nativeBuildInputs = [
    dpkg
    autoPatchelfHook
    wrapGAppsHook3
  ];
  buildInputs = [
    gtk3
    webkitgtk_4_1
    wayland
    dbus
    stdenv.cc.cc.lib
  ];
  runtimeDependencies = [ libayatana-appindicator ];
  dontUnpack = true;
  dontStrip = true;

  installPhase = ''
    runHook preInstall
    dpkg-deb -x "$src" .
    mv usr "$out"

    # renCal looks here relative to its executable when installed outside /usr.
    mkdir -p "$out/libexec/renCal"
    mv "$out/lib/renCal/providers" "$out/libexec/renCal/"

    substituteInPlace "$out/lib/systemd/user/rencal-notifierd.service" \
      --replace-fail /usr/bin/rencal-notifierd "$out/bin/rencal-notifierd"
    runHook postInstall
  '';

  preFixup = ''
    gappsWrapperArgs+=(
      --prefix PATH : ${
        lib.makeBinPath [
          libnotify
          xdg-utils
        ]
      }
      --set RENCAL_NOTIFIER_ICON "$out/share/icons/hicolor/128x128/apps/rencal.png"
    )
  '';

  meta = {
    description = "Omarchy-themed desktop calendar with Caldir sync";
    homepage = "https://rencal.org/";
    license = lib.licenses.mit;
    platforms = [ "aarch64-linux" ];
    mainProgram = "rencal";
  };
})
