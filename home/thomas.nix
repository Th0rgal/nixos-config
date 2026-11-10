# Thomas's personal Home Manager configuration layered on top of `programs.omarchnix`.
{
  config,
  lib,
  options,
  pkgs,
  ...
}:
let
  cfg = config.programs.omarchnix;
  inherit (lib.generators) mkLuaInline;
  mkBind = keys: dsp: {
    _args = [
      keys
      (mkLuaInline dsp)
    ];
  };

  omarchyShellBin = if cfg.shell.package != null then "${cfg.shell.package}/bin" else "";

  missionControlId = "io.github.joaovitor2763.mission-control";
  omarchyShellExe =
    if omarchyShellBin != "" then "${omarchyShellBin}/omarchy-shell" else "omarchy-shell";
  mcSummonCmd = "${omarchyShellExe} shell summon ${missionControlId} '{}'";
  mcHideCmd = "${omarchyShellExe} shell hide ${missionControlId}";
  mcToggleCmd = "${omarchyShellExe} shell toggle ${missionControlId} '{}'";

  upstreamMissionControlSrc = pkgs.fetchFromGitHub {
    owner = "joaovitor2763";
    repo = "omarchy-mission-control";
    rev = "86c3d652408fd0a84ec84e6f0e179647952611db";
    hash = "sha256-a4teoN7+IPaFw1xcYlbO3p2G/GyHghtQcERYT+pwotc=";
  };

  missionControlSrc = pkgs.runCommand "omarchy-mission-control-src" { } ''
    mkdir -p "$out"
    cp -r --no-preserve=mode,ownership "${upstreamMissionControlSrc}/." "$out/"
    cp --no-preserve=mode,ownership "${./mission-control}/"*.qml "$out/"
  '';

  missionControlPlugin =
    if (pkgs ? omarchnix) && (pkgs.omarchnix ? buildPlugin) then
      pkgs.omarchnix.buildPlugin {
        src = missionControlSrc;
      }
    else
      null;

  macosDockId = "th0rgal.macos-dock";

  macosDockPlugin =
    if (pkgs ? omarchnix) && (pkgs.omarchnix ? buildPlugin) then
      pkgs.omarchnix.buildPlugin {
        src = ./macos-dock;
      }
    else
      null;

  spotlightId = "th0rgal.spotlight";
  spotlightToggleCmd = "${omarchyShellExe} shell toggle ${spotlightId} '{}'";

  spotlightPlugin =
    if (pkgs ? omarchnix) && (pkgs.omarchnix ? buildPlugin) then
      pkgs.omarchnix.buildPlugin {
        src = ./spotlight;
      }
    else
      null;

  touchIdId = "th0rgal.touchid";

  touchIdPlugin =
    if (pkgs ? omarchnix) && (pkgs.omarchnix ? buildPlugin) then
      pkgs.omarchnix.buildPlugin {
        src = ./touchid;
        postInstall = ''
          substituteInPlace "$out/touchid-monitor.py" \
            --replace-fail '"/run/current-system/sw/bin/gdbus"' '"${pkgs.glib.bin}/bin/gdbus"'
        '';
      }
    else
      null;

  workspaceWallpapersId = "th0rgal.workspace-wallpapers";

  workspaceWallpapersPlugin =
    if (pkgs ? omarchnix) && (pkgs.omarchnix ? buildPlugin) then
      pkgs.omarchnix.buildPlugin {
        src = ./workspace-wallpapers;
      }
    else
      null;

  starshipSubtle = import ../themes/starship-subtle { inherit pkgs lib; };
  hasCustomThemesOpt = options.programs.omarchnix.theme ? customThemes;

  # Guard shims on $PATH so coding agents and local shells use `turbo-lean`
  # instead of spawning unshared multi-GiB `lake` builds or downloading `~/.elan` toolchains.
  useTurboLeanInsteadShims = pkgs.runCommand "use-turbo-lean-instead-shims" { } ''
    mkdir -p "$out/bin"
    for cmd in lake leanc; do
      {
        echo "#!${pkgs.runtimeShell}"
        cat <<'SHIM'
    set -u
    cmd="$(basename "$0")"
    self_real="$(readlink -f "$0" 2>/dev/null || printf '%s' "$0")"

    find_next_bin() {
      for cand in "''${LEAN_BIN_DIR:-}/$cmd" "''${ELAN_HOME:-}/bin/$cmd" "''${HOME:-}/.elan/bin/$cmd"; do
        [ -n "$cand" ] && [ -x "$cand" ] || continue
        cand_real="$(readlink -f "$cand" 2>/dev/null || printf '%s' "$cand")"
        [ "$cand_real" = "$self_real" ] && continue
        printf '%s\n' "$cand"
        return 0
      done
      old_ifs=$IFS; IFS=:
      for dir in ''${PATH:-}; do
        [ -n "$dir" ] || continue
        cand="$dir/$cmd"
        [ -x "$cand" ] || continue
        cand_real="$(readlink -f "$cand" 2>/dev/null || printf '%s' "$cand")"
        [ "$cand_real" = "$self_real" ] && continue
        IFS=$old_ifs
        printf '%s\n' "$cand"
        return 0
      done
      IFS=$old_ifs
      return 1
    }

    if [ "''${SANDBOXED_ALLOW_RAW_LAKE:-0}" = "1" ] || [ "''${SANDBOXED_REMOTE_EXECUTION:-0}" = "1" ]; then
      if real_bin="$(find_next_bin)"; then
        exec "$real_bin" "$@"
      fi
      echo "error: SANDBOXED_ALLOW_RAW_LAKE=1 was set, but no underlying '$cmd' binary was found on PATH (use 'turbo-lean' directly, or enter 'nix develop' if raw Lake is strictly required)." >&2
      exit 127
    fi

    cat >&2 <<EOF
    warning: '$cmd''${1:+ $1}' is disabled by default on this machine — use 'turbo-lean' instead (shared CAS + native clang codegen without multi-GiB .lake duplication):
      - build targets:     turbo-lean build --project-dir <dir> [targets...]
      - check single file: turbo-lean check <path/to/File.lean> [--decl <Full.DeclName>]
      - audit formal spec: turbo-lean audit-spec <path/to/Spec.lean>
      - Orb binaries:      ./scripts/build_orb_bins.sh --only <bin>
    (Set SANDBOXED_ALLOW_RAW_LAKE=1 only if raw Lake is strictly required, e.g. 'lake update'.)
    EOF
    exit 1
    SHIM
      } > "$out/bin/$cmd"
      chmod 0755 "$out/bin/$cmd"
    done

    {
      echo "#!${pkgs.runtimeShell}"
      cat <<'SHIM'
    set -u
    self_real="$(readlink -f "$0" 2>/dev/null || printf '%s' "$0")"

    if [ "''${SANDBOXED_ALLOW_RAW_ELAN:-0}" = "1" ]; then
      old_ifs=$IFS; IFS=:
      for dir in ''${PATH:-}; do
        [ -n "$dir" ] && [ -x "$dir/elan" ] || continue
        cand_real="$(readlink -f "$dir/elan" 2>/dev/null || printf '%s' "$dir/elan")"
        [ "$cand_real" = "$self_real" ] && continue
        IFS=$old_ifs
        exec "$dir/elan" "$@"
      done
      IFS=$old_ifs
      echo "error: SANDBOXED_ALLOW_RAW_ELAN=1 was set, but no underlying 'elan' binary was found on PATH." >&2
      exit 127
    fi

    cat >&2 <<EOF
    warning: 'elan''${1:+ $1}' is disabled on this machine — Lean toolchains are managed declaratively via Nix and 'turbo-lean' (never install private ~/.elan toolchains):
      - build targets:     turbo-lean build --project-dir <dir> [targets...]
      - check single file: turbo-lean check <path/to/File.lean> [--decl <Full.DeclName>]
      - audit formal spec: turbo-lean audit-spec <path/to/Spec.lean>
    (Set SANDBOXED_ALLOW_RAW_ELAN=1 only if raw elan is strictly required.)
    EOF
    exit 1
    SHIM
    } > "$out/bin/elan"
    chmod 0755 "$out/bin/elan"
  '';
in
{
  imports = [
    ./fcitx5-fallback.nix
  ];

  programs.omarchnix.theme = lib.mkIf (starshipSubtle.dark.attrset != null) (
    {
      name = starshipSubtle.dark.name;
      attrset = starshipSubtle.dark.attrset;
    }
    // lib.optionalAttrs hasCustomThemesOpt {
      darkName = starshipSubtle.dark.name;
      lightName = starshipSubtle.light.name;
      customThemes = {
        ${starshipSubtle.dark.name} = starshipSubtle.dark.package;
        ${starshipSubtle.light.name} = starshipSubtle.light.package;
      };
    }
  );

  home.activation.starshipSubtleTheme = lib.mkIf (!hasCustomThemesOpt) (
    config.lib.dag.entryBetween [ "omarchnixStateAndPlugins" ] [ "writeBoundary" ] ''
      for pair in \
        "${starshipSubtle.dark.name}:${starshipSubtle.dark.package}" \
        "${starshipSubtle.light.name}:${starshipSubtle.light.package}"; do
        slug="''${pair%%:*}"
        pkg="''${pair#*:}"
        target_theme_dir="$HOME/.config/omarchy/themes/$slug"
        rm -rf "$target_theme_dir"
        mkdir -p "$target_theme_dir"
        cp -r --no-preserve=mode "$pkg/." "$target_theme_dir/"
        chmod -R u+w "$target_theme_dir"
      done
    ''
  );

  programs.git = {
    enable = true;
    settings = {
      user = {
        name = "Thomas Marchand";
        email = "thomas@lfglabs.dev";
      };
      init.defaultBranch = "main";
      pull.rebase = false;
    };
  };

  home.packages = [
    useTurboLeanInsteadShims
  ];

  home.sessionVariables = {
    BROWSER = "brave";
  };

  programs.fastfetch.enable = true;

  xdg.mimeApps = {
    enable = true;
    defaultApplications = {
      "x-scheme-handler/bitwarden" = [ "bitwarden.desktop" ];
      "x-scheme-handler/tg" = [ "org.telegram.desktop.desktop" ];
      "x-scheme-handler/tonsite" = [ "org.telegram.desktop.desktop" ];
      "x-scheme-handler/ton" = [ "org.telegram.desktop.desktop" ];
    };
  };

  # macOS-style Mission Control overlay with top Spaces strip + auto-hide bottom Dock.
  programs.omarchnix.plugins.locked =
    lib.optionalAttrs (missionControlPlugin != null) {
      ${missionControlId} = {
        package = missionControlPlugin;
        enable = true;
      };
    }
    // lib.optionalAttrs (macosDockPlugin != null) {
      ${macosDockId} = {
        package = macosDockPlugin;
        enable = true;
      };
    }
    // lib.optionalAttrs (spotlightPlugin != null) {
      ${spotlightId} = {
        package = spotlightPlugin;
        enable = true;
      };
    }
    // lib.optionalAttrs (touchIdPlugin != null) {
      ${touchIdId} = {
        package = touchIdPlugin;
        enable = true;
      };
    }
    // lib.optionalAttrs (workspaceWallpapersPlugin != null) {
      ${workspaceWallpapersId} = {
        package = workspaceWallpapersPlugin;
        enable = true;
      };
    }
    // {
      "omarchnix.clipboard".enable = false;
      "omarchnix.appearance".section = "tray";
    };

  programs.omarchnix.shell.extraSettings = lib.mkIf (macosDockPlugin != null) {
    disabledPlugins = [
      "omarchy.dock"
      "omarchy.dev-gallery"
    ]
    ++ lib.optional (workspaceWallpapersPlugin != null) "omarchy.background";
    dock = {
      position = "bottom";
      autohide = true;
      allWorkspaces = true;
    };
  };

  home.activation.macosDockShellConfig = lib.mkIf (macosDockPlugin != null) (
    config.lib.dag.entryAfter [ "omarchnixStateAndPlugins" ] ''
      if [ -f "$HOME/.config/omarchy/shell.json" ] && [ ! -L "$HOME/.config/omarchy/shell.json" ]; then
        tmp_json="$(mktemp "$HOME/.config/omarchy/shell.json.XXXXXX")"
        ${pkgs.jq}/bin/jq '
          .version = 1
          | .disabledPlugins = (
              (.disabledPlugins // [])
              | if index("omarchy.dock") then . else . + ["omarchy.dock"] end
              | if index("omarchy.dev-gallery") then . else . + ["omarchy.dev-gallery"] end
              ${lib.optionalString (
                workspaceWallpapersPlugin != null
              ) ''| if index("omarchy.background") then . else . + ["omarchy.background"] end''}
            )
          | .dock = ((.dock // {}) | .position = (.position // "bottom") | .autohide = (if has("autohide") then .autohide else true end) | .allWorkspaces = (.allWorkspaces // true))
        ' "$HOME/.config/omarchy/shell.json" > "$tmp_json" && mv "$tmp_json" "$HOME/.config/omarchy/shell.json"
        chmod 0644 "$HOME/.config/omarchy/shell.json"
      fi
    ''
  );

  home.activation.minimalStatusBarLayout = config.lib.dag.entryAfter [ "omarchnixStateAndPlugins" ] ''
    if [ -f "$HOME/.config/omarchy/shell.json" ] && [ ! -L "$HOME/.config/omarchy/shell.json" ]; then
      tmp_json="$(mktemp "$HOME/.config/omarchy/shell.json.XXXXXX")"
      ${pkgs.jq}/bin/jq '
        def ensure_id($arr; $id):
          if any($arr[]; (.id // .) == $id) then $arr else $arr + [{ id: $id }] end;
        .version = 1
        | .bar.layout.right = [
            (.bar.layout.right // [])[]
            | select(
                ((.id // .) | IN("omarchy.agents", "omarchy.monitor", "omarchnix.clipboard", "omarchnix.appearance")) | not
              )
            | select(
                ((.id // .) == "omarchy.indicators") | not
              )
          ]
        | .bar.layout.tray = (
            (.bar.layout.tray // [])
            | ensure_id(.; "omarchnix.appearance")
            | ensure_id(.; "omarchy.agents")
            | ensure_id(.; "omarchy.monitor")
          )
      ' "$HOME/.config/omarchy/shell.json" > "$tmp_json" && mv "$tmp_json" "$HOME/.config/omarchy/shell.json"
      chmod 0644 "$HOME/.config/omarchy/shell.json"
    fi
  '';

  wayland.windowManager.hyprland = {
    # Re-apply active runtime theme border hairline (`starship-subtle` vs `starship-subtle-light`) on `hyprctl reload`,
    # and maintain contiguous macOS-style Spaces `1..N` via lightweight persistent workspace rules.
    extraConfig = ''
      local theme_hyprland = (os.getenv("HOME") or "") .. "/.local/state/omarchy/current/theme/hyprland.lua"
      local f = io.open(theme_hyprland, "r")
      if f then
        f:close()
        pcall(dofile, theme_hyprland)
      end

      do
        local MAX_SPACES = 32
        local state_path = (os.getenv("XDG_RUNTIME_DIR") or "/tmp") .. "/omarchnix-spaces-max"

        local function read_manual_max()
          local sf = io.open(state_path, "r")
          if not sf then return 1 end
          local raw = sf:read("*a")
          sf:close()
          local n = tonumber(raw)
          if not n or n < 1 then return 1 end
          return math.min(MAX_SPACES, math.floor(n))
        end

        local state = {
          rules = {},
          enabled = {},
          max_rule_id = 0,
          manual_max = read_manual_max(),
          syncing = false,
          reordering = false,
        }

        local function write_manual_max(n)
          local clamped = math.max(1, math.min(MAX_SPACES, math.floor(tonumber(n) or 1)))
          state.manual_max = clamped
          local sf = io.open(state_path, "w")
          if sf then
            sf:write(tostring(clamped))
            sf:close()
          end
        end

        local function compute_live_max()
          local max_id = 1
          for _, ws in ipairs(hl.get_workspaces()) do
            local id = ws.id
            if id and id >= 1 and id <= MAX_SPACES and not ws.special then
              if (ws.windows and ws.windows > 0) or ws.active or ws.visible then
                if id > max_id then
                  max_id = id
                end
              end
            end
          end
          return max_id
        end

        local function set_rule_enabled(id, en)
          if en then
            if not state.rules[id] then
              state.rules[id] = hl.workspace_rule({
                workspace = tostring(id),
                persistent = true,
              })
              state.enabled[id] = true
              if id > state.max_rule_id then
                state.max_rule_id = id
              end
            elseif not state.enabled[id] or hl.get_workspace(id) == nil then
              state.rules[id]:set_enabled(true)
              state.enabled[id] = true
            end
          else
            if state.rules[id] and state.enabled[id] then
              state.rules[id]:set_enabled(false)
              state.enabled[id] = false
            end
          end
        end

        function state.sync()
          if state.syncing or state.reordering then return end
          state.syncing = true
          pcall(function()
            local target = math.min(MAX_SPACES, math.max(1, state.manual_max, compute_live_max()))
            for id = 1, target do
              set_rule_enabled(id, true)
            end
            for id = target + 1, state.max_rule_id do
              set_rule_enabled(id, false)
            end
          end)
          state.syncing = false
        end

        function state.add_space(target_id)
          local cur = math.max(state.manual_max, compute_live_max())
          local wanted = tonumber(target_id) or (cur + 1)
          write_manual_max(math.max(state.manual_max, wanted))
          state.sync()
        end

        function state.reorder(moves)
          if not moves or #moves == 0 then return end
          state.reordering = true
          pcall(function()
            for id = 1, state.max_rule_id do
              set_rule_enabled(id, false)
            end
            local items = {}
            local next_tmp = 9001
            for _, m in ipairs(moves) do
              if m[1] ~= m[2] and hl.get_workspace(m[1]) then
                while hl.get_workspace(next_tmp) ~= nil do next_tmp = next_tmp + 1 end
                items[#items + 1] = { from = m[1], to = m[2], tmp = next_tmp }
                next_tmp = next_tmp + 1
              end
            end
            for _, it in ipairs(items) do
              hl.dispatch(hl.dsp.workspace.change_id({ workspace = tostring(it.from), id = it.tmp }))
            end
            for _, it in ipairs(items) do
              hl.dispatch(hl.dsp.workspace.change_id({ workspace = tostring(it.tmp), id = it.to }))
            end
          end)
          state.reordering = false
          state.sync()
        end

        function state.close_space(space_id)
          local sid = math.floor(tonumber(space_id) or 0)
          if sid < 1 or sid > MAX_SPACES then return end
          local target_ws = hl.get_workspace(sid)
          if target_ws and target_ws.windows and target_ws.windows > 0 then return end
          local upper = math.min(MAX_SPACES, math.max(1, state.manual_max, compute_live_max()))
          if upper <= 1 or sid > upper then return end

          state.reordering = true
          pcall(function()
            local active_ws = hl.get_active_workspace()
            if active_ws and active_ws.id == sid then
              local fallback = sid < upper and (sid + 1) or (upper - 1)
              hl.dispatch(hl.dsp.focus({ workspace = fallback }))
            end
            for id = 1, state.max_rule_id do
              set_rule_enabled(id, false)
            end
            local items = {}
            local next_tmp = 9001
            for id = sid + 1, upper do
              if hl.get_workspace(id) then
                while hl.get_workspace(next_tmp) ~= nil do next_tmp = next_tmp + 1 end
                items[#items + 1] = { from = id, to = id - 1, tmp = next_tmp }
                next_tmp = next_tmp + 1
              end
            end
            for _, it in ipairs(items) do
              hl.dispatch(hl.dsp.workspace.change_id({ workspace = tostring(it.from), id = it.tmp }))
            end
            for _, it in ipairs(items) do
              hl.dispatch(hl.dsp.workspace.change_id({ workspace = tostring(it.tmp), id = it.to }))
            end
            write_manual_max(math.max(1, upper - 1))
          end)
          state.reordering = false
          state.sync()
        end

        _G.__omarchnix_spaces = state

        for _, ev in ipairs({
          "workspace.created",
          "workspace.removed",
          "workspace.active",
          "window.open",
          "window.close",
          "window.move_to_workspace",
        }) do
          hl.on(ev, function() state.sync() end)
        end

        state.sync()
      end
    '';

    settings = {
      launcher = lib.mkIf (spotlightPlugin != null) {
        _var = lib.mkForce spotlightToggleCmd;
      };

      config = {
        general = {
          border_size = 1;
          col = {
            active_border = lib.mkForce starshipSubtle.dark.palette.hyprland_active_border;
            inactive_border = lib.mkForce starshipSubtle.dark.palette.hyprland_inactive_border;
          };
        };
        input.touchpad = {
          # Invert scroll so content goes up when fingers go up, and smooth speed (0.5x).
          natural_scroll = lib.mkForce false;
          scroll_factor = 0.5;
        };
        misc = {
          focus_on_activate = true;
        };
      };

      layer_rule = lib.mkAfter [
        {
          name = "omarchy-mission-control";
          match = {
            namespace = "^omarchy-mission-control$";
          };
          no_anim = true;
          animation = "none";
        }
        {
          name = "omarchnix-spotlight";
          match = {
            namespace = "^omarchnix-spotlight$";
          };
          blur = true;
          ignore_alpha = 0.15;
          no_anim = true;
          animation = "none";
        }
        {
          name = "omarchnix-touchid";
          match = {
            namespace = "^omarchnix-touchid$";
          };
          blur = true;
          ignore_alpha = 0.15;
          no_anim = true;
          animation = "none";
        }
      ];

      gesture = lib.mkAfter [
        # macOS Mission Control: 3-finger swipe up opens immediately on gesture start,
        # 3-finger swipe down closes immediately on gesture start.
        {
          fingers = 3;
          direction = "up";
          scale = 1.5;
          disable_inhibit = true;
          action = {
            start = mkLuaInline "function() hl.dispatch(hl.dsp.exec_cmd(${builtins.toJSON mcSummonCmd})) end";
          };
        }
        {
          fingers = 3;
          direction = "down";
          scale = 1.5;
          disable_inhibit = true;
          action = {
            start = mkLuaInline "function() hl.dispatch(hl.dsp.exec_cmd(${builtins.toJSON mcHideCmd})) end";
          };
        }
      ];

      bind = lib.mkAfter [
        # macOS Mission Control shortcuts (Ctrl+Up toggles, Ctrl+Down closes)
        (mkBind "CTRL + up" "hl.dsp.exec_cmd(${builtins.toJSON mcToggleCmd})")
        (mkBind "CTRL + down" "hl.dsp.exec_cmd(${builtins.toJSON mcHideCmd})")
      ];
    };
  };

  xdg.configFile."omarchy/workspace-wallpapers.json".text = builtins.toJSON {
    default = "/home/thomas/Pictures/Wallpapers/bologna-dome.webp";
    workspaces = {
      "1" = "/home/thomas/Pictures/Wallpapers/singapore.webp";
      "2" = "/home/thomas/Pictures/Wallpapers/bologna-dome.webp";
      "3" = "/home/thomas/Pictures/Wallpapers/kazakhstan-charyn-canyon-peaks.webp";
      "4" = "/home/thomas/Pictures/Wallpapers/shanghai-tower-twilight-spiral.webp";
      "5" = "/home/thomas/Pictures/Wallpapers/zambezi-violet-twilight.webp";
      "6" = "/home/thomas/Pictures/Wallpapers/spiral-brick-vault-archway.webp";
      "7" = "/home/thomas/Pictures/Wallpapers/lisbon-greenhouse-canopy-top.webp";
      "8" = "/home/thomas/Pictures/Wallpapers/lisbon-greenhouse-canopy-bottom.webp";
    };
  };

  # Disable redundant hyprpaper daemon since th0rgal.workspace-wallpapers renders wallpapers in Quickshell.
  services.hyprpaper.enable = lib.mkForce false;
}
