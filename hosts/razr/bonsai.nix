# Local Ternary Bonsai 2.5 27B (Hexagon V79 NPU) + Local Orb Core stack for Razr 60 Ultra.
# Keeps model RAM at 0 MiB when idle (wake-on-request + 180s idle-unload + 46 C thermal guard
# + UFS 4.0 prefix-state cache for sub-second OpenCode Turn-1).
{
  lib,
  pkgs,
  ...
}:
let
  bonsaiProxy = ./bonsai-proxy.py;

  orbCoreLibPath = lib.makeLibraryPath [
    pkgs.sqlite
    pkgs.stdenv.cc.cc.lib
    pkgs.curl
    pkgs.openssl
    pkgs.zlib
    pkgs.zstd
    pkgs.brotli
    pkgs.nghttp2
    pkgs.libssh2
    pkgs.libidn2
    pkgs.libpsl
    pkgs.krb5
    pkgs.vulkan-loader
  ];

  orbCoreStart = pkgs.writeShellScript "orb-core-local-start" ''
    set -euo pipefail
    BIN="/home/thomas/bonsai-shootout/orb-local/orb-core"
    if [ ! -x "$BIN" ]; then
      echo "orb-core binary not found at $BIN; skipping" >&2
      exit 0
    fi
    export DEV_MODE=true
    export PORT=3000
    export HOST=127.0.0.1
    export WORKING_DIR="/home/thomas/.local/share/orb-local"
    mkdir -p "$WORKING_DIR"
    exec "${pkgs.glibc}/lib/ld-linux-aarch64.so.1" \
      --library-path "${orbCoreLibPath}" \
      "$BIN"
  '';
in
{
  systemd.services.bonsai-proxy = {
    description = "Ternary Bonsai 2.5 27B wake-on-request & UFS prompt-cache proxy (:8090 -> :8092)";
    wantedBy = [ "multi-user.target" ];
    after = [ "network.target" ];
    environment = {
      BONSAI_PORT = "8090";
      BONSAI_BACKEND_PORT = "8092";
      BONSAI_BRIDGE_SOCK = "/var/lib/sidesystem/bridge.sock";
      BONSAI_MODE = "npu";
      BONSAI_IDLE_TIMEOUT = "180";
      BONSAI_MAX_BATT_C = "46.0";
      BONSAI_PROMPT_CACHE_DIR = "/home/thomas/bonsai-shootout/prompt-cache";
    };
    serviceConfig = {
      User = "thomas";
      Group = "users";
      ExecStart = "${pkgs.python3}/bin/python3 ${bonsaiProxy}";
      Restart = "on-failure";
      RestartSec = "3s";
    };
  };

  systemd.services.orb-core-local = {
    description = "Local offline Orb Core server on Razr SideSystem (127.0.0.1:3000)";
    wantedBy = [ "multi-user.target" ];
    after = [
      "network.target"
      "bonsai-proxy.service"
    ];
    wants = [ "bonsai-proxy.service" ];
    serviceConfig = {
      User = "thomas";
      Group = "users";
      WorkingDirectory = "/home/thomas";
      ExecStart = "${orbCoreStart}";
      Restart = "on-failure";
      RestartSec = "5s";
    };
  };

  home-manager.users.thomas.xdg.configFile."opencode/agents/bonsai-fast.md".text = ''
    ---
    description: Compact low-latency local coding agent optimized for Ternary Bonsai 2.5 27B on Razr NPU
    mode: primary
    ---
    You are a concise, accurate coding assistant running locally on a Motorola Razr 60 Ultra.
    - Be direct. Answer in the user language.
    - Read files before editing; make minimal, exact edits.
    - Run commands only when needed to verify or inspect.
    - Keep tool outputs and replies short unless asked for detail.
  '';
}
