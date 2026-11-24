# Declarative per-Space wallpaper mapping backed by `github:Th0rgal/wallpapers`.
# All paths resolve directly inside `/nix/store`, so VMs and workstations spin up
# with identical wallpapers out of the box without manual file syncing.
#
# Both `th0rgal.workspace-wallpapers` and Mission Control automatically adapt to:
#   - Dark vs Light theme (`variants.dark` / `variants.light`)
#   - Landscape vs Portrait monitors (`variants.portrait.{dark,light}`)
wallpapersSrc:
let
  landscapeDark = {
    default = "${wallpapersSrc}/bologna-dome.webp";
    workspaces = {
      "1" = "${wallpapersSrc}/singapore.webp";
      "2" = "${wallpapersSrc}/bologna-dome.webp";
      "3" = "${wallpapersSrc}/spiral-brick-vault-archway.webp";
      "4" = "${wallpapersSrc}/shanghai-tower-twilight-spiral.webp";
      "5" = "${wallpapersSrc}/zambezi-violet-twilight.webp";
      "6" = "${wallpapersSrc}/lisbon-greenhouse-canopy-top.webp";
      "7" = "${wallpapersSrc}/lisbon-greenhouse-canopy-bottom.webp";
    };
  };

  landscapeLight = {
    default = "${wallpapersSrc}/bologna-dome.webp";
    workspaces = {
      "1" = "${wallpapersSrc}/singapore.webp";
      "2" = "${wallpapersSrc}/bologna-dome.webp";
      "3" = "${wallpapersSrc}/mallorca-son-marroig-arches.webp";
      "4" = "${wallpapersSrc}/praia-da-ursa-sea-stacks.webp";
      "5" = "${wallpapersSrc}/kazakhstan-charyn-canyon-peaks.webp";
      "6" = "${wallpapersSrc}/lisbon-greenhouse-canopy-top.webp";
      "7" = "${wallpapersSrc}/madeira-mountain-peaks.webp";
    };
  };

  portraitDark = {
    default = "${wallpapersSrc}/portrait-baroque-stone-nave.webp";
    workspaces = {
      "1" = "${wallpapersSrc}/portrait-shanghai-greenhouse-canopy.webp";
      "2" = "${wallpapersSrc}/portrait-baroque-stone-nave.webp";
      "3" = "${wallpapersSrc}/portrait-helsinki-cathedral.webp";
      "4" = "${wallpapersSrc}/portrait-shanghai-tower-twilight-spiral.webp";
      "5" = "${wallpapersSrc}/portrait-lisbon-25-abril-bridge.webp";
      "6" = "${wallpapersSrc}/portrait-lisbon-greenhouse-oculus.webp";
      "7" = "${wallpapersSrc}/portrait-mallorca-vine-pergola.webp";
    };
  };

  portraitLight = {
    default = "${wallpapersSrc}/portrait-sanqingshan-cliffside-pine.webp";
    workspaces = {
      "1" = "${wallpapersSrc}/portrait-madeira-misty-valley.webp";
      "2" = "${wallpapersSrc}/portrait-sanqingshan-cliffside-pine.webp";
      "3" = "${wallpapersSrc}/portrait-sanqingshan-lone-pine-pinnacle.webp";
      "4" = "${wallpapersSrc}/portrait-praia-da-ursa-sea-stacks.webp";
      "5" = "${wallpapersSrc}/portrait-costa-brava-coastal-pine.webp";
      "6" = "${wallpapersSrc}/portrait-lisbon-greenhouse-oculus.webp";
      "7" = "${wallpapersSrc}/portrait-costa-brava-pine-inlet.webp";
    };
  };
in
{
  inherit (landscapeDark) default workspaces;
  variants = {
    dark = landscapeDark;
    light = landscapeLight;
    portrait = {
      dark = portraitDark;
      light = portraitLight;
    };
  };
}
