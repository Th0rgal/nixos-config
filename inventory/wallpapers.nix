# Declarative per-Space wallpaper mapping backed by `github:Th0rgal/wallpapers`.
# All paths resolve directly inside `/nix/store`, so VMs and workstations spin up
# with identical wallpapers out of the box without manual file syncing.
wallpapersSrc: {
  default = "${wallpapersSrc}/bologna-dome.webp";
  workspaces = {
    "1" = "${wallpapersSrc}/singapore.webp";
    "2" = "${wallpapersSrc}/bologna-dome.webp";
    "3" = "${wallpapersSrc}/kazakhstan-charyn-canyon-peaks.webp";
    "4" = "${wallpapersSrc}/shanghai-tower-twilight-spiral.webp";
    "5" = "${wallpapersSrc}/zambezi-violet-twilight.webp";
    "6" = "${wallpapersSrc}/spiral-brick-vault-archway.webp";
    "7" = "${wallpapersSrc}/lisbon-greenhouse-canopy-top.webp";
    "8" = "${wallpapersSrc}/lisbon-greenhouse-canopy-bottom.webp";
  };
}
