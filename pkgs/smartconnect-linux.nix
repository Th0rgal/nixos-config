{
  lib,
  pkgs,
  rustPlatform,
  fetchFromGitHub,
  pkg-config,
  makeWrapper,
  makeDesktopItem,
  gtk4,
  libadwaita,
  glib,
  dbus,
  fuse3,
  gst_all_1,
  libnice,
  wl-clipboard,
  avahi,
  openssl,
}:
let
  src = fetchFromGitHub {
    owner = "rudrahnpatel";
    repo = "smartconnect-linux";
    rev = "8f2b1905a1df4c2a8d9d6240494a29127aa45a87";
    hash = "sha256-FnB8yGN8/8OxzLCE4++N9jUA8O0z4xfsHLdJLmloYkw=";
  };

  desktopItem = makeDesktopItem {
    name = "smartconnect";
    desktopName = "Smart Connect";
    genericName = "Motorola Smart Connect";
    comment = "Pair and connect Motorola Razr via native Smart Connect protocol";
    exec = "smartconnect-gui";
    icon = "phone";
    categories = [
      "Utility"
      "Network"
    ];
    keywords = [
      "motorola"
      "razr"
      "smartconnect"
      "phone"
      "mirror"
      "clipboard"
    ];
  };

  gstPluginPath = lib.makeSearchPathOutput "lib" "lib/gstreamer-1.0" [
    gst_all_1.gstreamer
    gst_all_1.gst-plugins-base
    gst_all_1.gst-plugins-good
    gst_all_1.gst-plugins-bad
    gst_all_1.gst-libav
    libnice
  ];
in
rustPlatform.buildRustPackage {
  pname = "smartconnect-linux";
  version = "0.1.0-unstable-2026-10-25";
  inherit src;

  cargoLock = {
    lockFile = "${src}/Cargo.lock";
  };

  # Build all workspace crates (`smartconnectd`, `smartconnect-gui`, `mirror`,
  # `crosscontrol`, `display`, `webcam`, `files`, `fex`) into `$out/bin`.
  cargoBuildFlags = [ "--workspace" ];
  doCheck = false;

  nativeBuildInputs = [
    pkg-config
    makeWrapper
    pkgs.wrapGAppsHook4
  ];

  buildInputs = [
    gtk4
    libadwaita
    glib
    dbus
    fuse3
    libnice
    openssl
    gst_all_1.gstreamer
    gst_all_1.gst-plugins-base
    gst_all_1.gst-plugins-good
    gst_all_1.gst-plugins-bad
    gst_all_1.gst-libav
  ];

  postInstall = ''
    mkdir -p "$out/share/applications"
    cp "${desktopItem}/share/applications/smartconnect.desktop" "$out/share/applications/"
    for bin in "$out"/bin/*; do
      if [ -f "$bin" ] && [ -x "$bin" ]; then
        wrapProgram "$bin" \
          --prefix GST_PLUGIN_SYSTEM_PATH_1_0 : "${gstPluginPath}" \
          --prefix PATH : "${
            lib.makeBinPath [
              wl-clipboard
              fuse3
              avahi
            ]
          }:/run/wrappers/bin"
      fi
    done
  '';

  meta = {
    description = "Unofficial Linux peer for the Motorola Smart Connect phone app";
    homepage = "https://github.com/rudrahnpatel/smartconnect-linux";
    license = lib.licenses.mit;
    platforms = lib.platforms.linux;
    mainProgram = "smartconnect-gui";
  };
}
