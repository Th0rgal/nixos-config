{
  lib,
  stdenvNoCC,
  fetchurl,
  dpkg,
  autoPatchelfHook,
  makeWrapper,
  wrapGAppsHook3,
  cairo,
  dbus,
  gcc-unwrapped,
  gdk-pixbuf,
  glib,
  gtk3,
  libayatana-appindicator,
  libsoup_3,
  pango,
  webkitgtk_4_1,
}:
let
  pname = "gnosisvpn";
  version = "1.1.0";

  srcs = {
    aarch64-linux = fetchurl {
      url = "https://download.vpn.gnosis.eth.limo/linux/apt/pool/main/g/gnosisvpn/gnosisvpn_${version}_arm64.deb";
      hash = "sha256-cGprZCnTfq6DMbdmtocgIvsBVbSupnusXM3ZFKMbf6Q=";
    };
    x86_64-linux = fetchurl {
      url = "https://download.vpn.gnosis.eth.limo/linux/apt/pool/main/g/gnosisvpn/gnosisvpn_${version}_amd64.deb";
      hash = "sha256-yUTilMQIkW0hpi4pJir1LV8PZv5nFQVtM8H0B1WddxI=";
    };
  };
in
stdenvNoCC.mkDerivation {
  inherit pname version;

  src =
    srcs.${stdenvNoCC.hostPlatform.system}
      or (throw "gnosisvpn: unsupported system ${stdenvNoCC.hostPlatform.system}");

  nativeBuildInputs = [
    dpkg
    autoPatchelfHook
    makeWrapper
    wrapGAppsHook3
  ];

  buildInputs = [
    cairo
    dbus
    gcc-unwrapped.lib
    gdk-pixbuf
    glib
    gtk3
    libayatana-appindicator
    libsoup_3
    pango
    webkitgtk_4_1
  ];

  strictDeps = true;
  dontWrapGApps = true;

  unpackPhase = ''
    runHook preUnpack
    dpkg-deb --fsys-tarfile "$src" | tar -x --no-same-owner --no-same-permissions
    runHook postUnpack
  '';

  installPhase = ''
    runHook preInstall

    mkdir -p "$out/bin" "$out/lib" "$out/share" "$out/etc"
    cp -a usr/bin/. "$out/bin/"
    chmod 0755 "$out/bin/"*

    cp -a "usr/lib/Gnosis VPN" "$out/lib/Gnosis VPN"
    cp -a usr/share/. "$out/share/"
    cp -a etc/gnosisvpn "$out/etc/gnosisvpn"

    mv "$out/share/applications/Gnosis VPN.desktop" "$out/share/applications/gnosis_vpn-app.desktop"
    substituteInPlace "$out/share/applications/gnosis_vpn-app.desktop" \
      --replace-quiet "Exec=gnosis_vpn-app" "Exec=$out/bin/gnosis_vpn-app"

    runHook postInstall
  '';

  preFixup = ''
    wrapProgram "$out/bin/gnosis_vpn-app" \
      "''${gappsWrapperArgs[@]}" \
      --prefix LD_LIBRARY_PATH : "${lib.makeLibraryPath [ libayatana-appindicator ]}" \
      --prefix XDG_DATA_DIRS : "$out/share"
  '';

  meta = {
    description = "Decentralized, trustless, uncensorable VPN powered by HOPR";
    homepage = "https://vpn.gnosis.eth.limo/";
    license = lib.licenses.gpl3Only;
    mainProgram = "gnosis_vpn-app";
    platforms = [
      "aarch64-linux"
      "x86_64-linux"
    ];
    sourceProvenance = with lib.sourceTypes; [ binaryNativeCode ];
  };
}
