# Encrypted, deduplicated restic backup of a host's irreplaceable state to Google Drive
# (`gdrive:01 — Backups & Infra/<host>-restic`), next to spark's `spark-memory-restic`.
#
# Zero-Rebuild policy: nothing that can be re-cloned or rebuilt is uploaded.
#   - Git repositories are skipped as directories (`--exclude-if-present .git`); their
#     unpushed commits, stashes, uncommitted changes and untracked files are captured
#     as bundles/patches by `backup-git-deltas` right before each run, with a manifest
#     of remotes to re-clone from.
#   - Caches, build outputs, cloud mounts and the Wallpapers checkout are excluded.
#
# Runs daily on AC power (or, in container guests like `razr`, only while charging on
# unmetered Wi-Fi, catching up after sleep), keeps 7 daily, 4 weekly and 12 monthly
# snapshots, and verifies the repository plus a test restore every month.
# The password comes from sops (`restic_password`), its offline copy in Bitwarden.
#
#   backup now | status | snapshots | restore <path> [target] | mount <dir>
{
  config,
  lib,
  osConfig ? { },
  pkgs,
  ...
}:
let
  home = config.home.homeDirectory;
  hostName = osConfig.networking.hostName or "m2air";
  isContainer = osConfig.boot.isContainer or false;
  serviceName = "restic-backups-${hostName}";
  repository = "rclone:gdrive:01 — Backups & Infra/${hostName}-restic";
  passwordFile = osConfig.sops.secrets.restic_password.path or null;
  enabled = passwordFile != null;
  stateDir = "${config.xdg.stateHome}/backup";
  deltaDir = "${stateDir}/git-deltas";

  paths = [
    "${home}/Notes"
    "${home}/work"
    "${home}/Pictures"
    "${home}/Downloads"
    "${home}/.ssh"
    "${home}/.gnupg"
    "${home}/.config"
    "${home}/.local/share"
    "${home}/.orb"
    "${home}/.zsh_history"
    stateDir
  ];

  excludes = [
    # Reconstructible or synced elsewhere.
    "${home}/.cache"
    "${home}/Cloud"
    "${home}/Pictures/Wallpapers"
    "${home}/.local/share/Trash"
    "${home}/.local/share/Steam"
    "${home}/.local/share/containers"
    "${home}/.local/share/flatpak"
    "${home}/.config/rclone"
    "${home}/.local/share/pnpm"
    "${home}/.local/share/TelegramDesktop/tdata/user_data"
    "${home}/.local/share/TelegramDesktop/tdata/emoji"
    "${home}/.config/Claude/claude-code"
    # Chromium/Electron caches and re-downloadable state, at any depth under .config.
  ]
  ++ map (name: "${home}/.config/**/${name}") [
    "Cache"
    "Code Cache"
    "GPUCache"
    "GPUPersistentCache"
    "GrShaderCache"
    "ShaderCache"
    "DawnGraphiteCache"
    "DawnWebGPUCache"
    "Service Worker"
    "DeferredBrowserMetrics"
    "component_crx_cache"
    "CertificateRevocation"
    "Safe Browsing"
    "Shared Dictionary"
    "Extensions"
  ]
  ++ [
    # Build outputs anywhere under the backed-up trees.
    "node_modules"
    "target"
    ".lake"
    "result"
    "result-*"
    ".direnv"
    "__pycache__"
    ".venv"
  ];

  gitDeltas = pkgs.writeShellApplication {
    name = "backup-git-deltas";
    runtimeInputs = [
      pkgs.git
      pkgs.coreutils
      pkgs.findutils
      pkgs.gnutar
      pkgs.gawk
    ];
    text = ''
      out=${lib.escapeShellArg deltaDir}
      rm -rf "$out.new"
      mkdir -p "$out.new"
      manifest="$out.new/MANIFEST.tsv"
      printf 'repo\thead\tremotes\n' > "$manifest"

      find ${lib.escapeShellArg "${home}/work"} -mindepth 1 -maxdepth 4 -name .git \
        -not -path '*/node_modules/*' -print0 | while IFS= read -r -d "" gitdir; do
        repo="$(dirname "$gitdir")"
        rel="''${repo#${home}/work/}"
        dest="$out.new/''${rel//\//__}"
        mkdir -p "$dest"
        g() { git -C "$repo" "$@"; }

        printf '%s\t%s\t%s\n' "$rel" "$(g rev-parse HEAD 2>/dev/null || echo none)" \
          "$(g remote -v 2>/dev/null | awk '$3=="(fetch)"{print $1"="$2}' | paste -sd, -)" >> "$manifest"

        # Temporary refs so stashes travel inside the bundle.
        i=0
        while read -r sha; do
          g update-ref "refs/backup/stash/$i" "$sha"
          i=$((i + 1))
        done < <(g rev-list -g refs/stash 2>/dev/null || true)

        if [ -z "$(g remote)" ]; then
          refs=(--all)
        else
          refs=(--branches --tags --glob=refs/backup --not --remotes)
        fi
        if [ -n "$(g rev-list "''${refs[@]}" 2>/dev/null | head -1)" ]; then
          g bundle create "$dest/unpushed.bundle" "''${refs[@]}" >/dev/null 2>&1 || true
        fi
        g for-each-ref --format='%(refname)' refs/backup | while read -r ref; do g update-ref -d "$ref"; done

        if g rev-parse --verify -q HEAD >/dev/null; then
          g diff --binary HEAD > "$dest/worktree.patch" 2>/dev/null || true
        fi
        if [ -n "$(g ls-files --others --exclude-standard | head -1)" ]; then
          g ls-files --others --exclude-standard -z \
            | tar --null -C "$repo" -T - -cf "$dest/untracked.tar" 2>/dev/null || true
        fi
        find "$dest" -type f -empty -delete
        rmdir "$dest" 2>/dev/null || true
      done

      rm -rf "$out"
      mv "$out.new" "$out"
    '';
  };

  resticEnv = ''
    export RESTIC_REPOSITORY=${lib.escapeShellArg repository}
    export RESTIC_PASSWORD_FILE=${lib.escapeShellArg (toString passwordFile)}
    export RESTIC_CACHE_DIR="''${XDG_CACHE_HOME:-$HOME/.cache}/${serviceName}"
    export PATH=${lib.makeBinPath [ pkgs.rclone ]}:$PATH
  '';

  # Phone/container guests (e.g. `razr`) share Android's kernel and network namespace:
  # skip scheduled runs unless USB/wireless power is online AND the default route uses
  # unmetered Wi-Fi (`wlan*`, never `rmnet*` mobile data).
  containerBackupCondition = pkgs.writeShellApplication {
    name = "backup-container-condition";
    runtimeInputs = [
      pkgs.coreutils
      pkgs.gnugrep
      pkgs.iproute2
    ];
    text = ''
      charging=0
      for ps in /sys/class/power_supply/usb/online /sys/class/power_supply/wireless/online; do
        if [ -r "$ps" ] && [ "$(cat "$ps" 2>/dev/null || echo 0)" = "1" ]; then
          charging=1
          break
        fi
      done
      if [ "$charging" != "1" ]; then
        echo "backup-container-condition: not charging (usb/wireless offline), skipping" >&2
        exit 1
      fi

      route="$(ip route get 1.1.1.1 2>/dev/null || true)"
      if ! printf '%s\n' "$route" | grep -Eq '\bdev[[:space:]]+wlan[0-9a-zA-Z_-]*\b'; then
        echo "backup-container-condition: default route is not Wi-Fi ($route), skipping" >&2
        exit 1
      fi
    '';
  };

  backupCli = pkgs.writeShellApplication {
    name = "backup";
    runtimeInputs = [
      pkgs.restic
      pkgs.systemd
    ];
    text = ''
      ${resticEnv}
      case "''${1:-status}" in
        now) exec systemctl --user start --wait ${serviceName}.service ;;
        status)
          systemctl --user list-timers --no-pager 'restic-*' 'backup-*' || true
          systemctl --user status --no-pager --lines=5 ${serviceName}.service || true
          ;;
        snapshots) shift; exec restic snapshots "$@" ;;
        restore)
          [ $# -ge 2 ] || { echo "usage: backup restore <path> [target-dir]" >&2; exit 2; }
          exec restic restore latest --include "$2" --target "''${3:-$HOME/restored}" ;;
        mount)
          [ $# -ge 2 ] || { echo "usage: backup mount <dir>" >&2; exit 2; }
          mkdir -p "$2"; exec restic mount "$2" ;;
        restic) shift; exec restic "$@" ;;
        *) echo "usage: backup [now|status|snapshots|restore <path> [target]|mount <dir>|restic <args>]" >&2; exit 2 ;;
      esac
    '';
  };

  verify = pkgs.writeShellApplication {
    name = "backup-verify";
    runtimeInputs = [
      pkgs.restic
      pkgs.coreutils
      pkgs.diffutils
    ];
    text = ''
      ${resticEnv}
      restic check --read-data-subset=1/16
      probe=${lib.escapeShellArg "${home}/.zsh_history"}
      target="$(mktemp -d)"
      trap 'rm -rf "$target"' EXIT
      restic restore latest --include "$probe" --target "$target"
      [ -s "$target$probe" ] || { echo "backup-verify: test restore of $probe produced nothing" >&2; exit 1; }
      echo "backup-verify: repository check and test restore passed"
    '';
  };
in
{
  config = lib.mkIf enabled {
    home.packages = [
      backupCli
      gitDeltas
    ];

    services.restic = {
      enable = true;
      backups.${hostName} = {
        inherit repository passwordFile paths;
        initialize = true;
        # The monthly `backup-verify` checks the repository; a full check after every
        # daily run would re-read the index from Drive each time.
        runCheck = false;
        exclude = excludes;
        extraBackupArgs = [
          "--exclude-if-present=.git"
          "--exclude-caches"
          "--one-file-system"
          "--tag=${hostName}"
        ];
        extraOptions = [ "rclone.program=${pkgs.rclone}/bin/rclone" ];
        backupPrepareCommand = lib.getExe gitDeltas;
        pruneOpts = [
          "--keep-daily 7"
          "--keep-weekly 4"
          "--keep-monthly 12"
          "--tag ${hostName}"
        ];
        timerConfig = {
          OnCalendar = "daily";
          RandomizedDelaySec = "1h";
          Persistent = true;
        };
        inhibitsSleep = true;
      };
    };

    # Never start on battery (or mobile data in phone containers); the timer is Persistent,
    # so a missed run catches up once charging (and on Wi-Fi).
    systemd.user.services.${serviceName} = {
      Unit = {
        After = [ "network-online.target" ];
      }
      // lib.optionalAttrs (!isContainer) {
        ConditionACPower = true;
      };
    }
    // lib.optionalAttrs isContainer {
      Service.ExecCondition = lib.getExe containerBackupCondition;
    };

    systemd.user.services.backup-verify = {
      Unit = {
        Description = "Monthly restic repository check and test restore (${hostName})";
        After = [ "network-online.target" ];
      }
      // lib.optionalAttrs (!isContainer) {
        ConditionACPower = true;
      };
      Service = {
        Type = "oneshot";
        ExecStart = lib.getExe verify;
        Nice = 10;
        IOSchedulingClass = "idle";
      }
      // lib.optionalAttrs isContainer {
        ExecCondition = lib.getExe containerBackupCondition;
      };
    };
    systemd.user.timers.backup-verify = {
      Unit.Description = "Monthly restic repository check and test restore (${hostName})";
      Timer = {
        OnCalendar = "monthly";
        RandomizedDelaySec = "6h";
        Persistent = true;
      };
      Install.WantedBy = [ "timers.target" ];
    };
  };
}
