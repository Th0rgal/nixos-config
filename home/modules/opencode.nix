# Declarative OpenCode v2 CLI (`anomalyco/opencode` v2.2.7) and Orb Router provider sync.
{
  config,
  lib,
  pkgs,
  ...
}:
let
  opencodeLatest =
    if pkgs.stdenv.hostPlatform.system == "aarch64-linux" then
      let
        opencodeVersion = "2.2.7";
        opencodeSrc = pkgs.fetchFromGitHub {
          owner = "anomalyco";
          repo = "opencode";
          tag = "v${opencodeVersion}";
          hash = "sha256-JPCR867ZoMbYNrCvAa3OnilsxVcktD3mHCGpR6o/nlw=";
        };
        opencodeNodeModules = pkgs.callPackage "${opencodeSrc}/nix/node_modules.nix" {
          rev = "v${opencodeVersion}";
          hash = "sha256-L0rDvr0j/+R8bSErWXO1xTPac5rVamUny4clIThzSUk=";
        };
      in
      (pkgs.callPackage "${opencodeSrc}/nix/opencode.nix" {
        node_modules = opencodeNodeModules;
      }).overrideAttrs
        {
          version = opencodeVersion;
          __intentionallyOverridingVersion = true;
          installPhase = ''
            runHook preInstall

            install -Dm755 dist/cli-*/bin/opencode "$out/bin/opencode"

            wrapProgram "$out/bin/opencode" \
              --set OPENCODE_DISABLE_AUTOUPDATE true \
              --suffix PATH : ${lib.makeBinPath [ pkgs.ripgrep ]} \
              --prefix LD_LIBRARY_PATH : ${
                lib.makeLibraryPath [
                  pkgs.stdenv.cc.cc
                  pkgs.wayland
                ]
              }

            ln -s opencode "$out/bin/opencode2"

            runHook postInstall
          '';
        }
    else
      pkgs.opencode;
in
{
  home.packages = [ opencodeLatest ];

  # Keep `~/.config/opencode/opencode.json` as a regular writable file (managed by
  # `home.activation.opencodeOrbRouterConfig` below) instead of a `/nix/store` symlink,
  # so `orbrouter.thomas.md` models + `OrbLocald` MCP entries coexist cleanly.
  xdg.configFile."opencode/opencode.json".enable = lib.mkForce false;

  home.activation.opencodeOrbRouterConfig =
    config.lib.dag.entryAfter [ "writeBoundary" "linkGeneration" ]
      ''
        ${pkgs.python3}/bin/python3 - <<'PY'
        import json
        import os
        import pathlib
        import socket
        import tempfile
        import urllib.request

        home = pathlib.Path.home()
        cfg_dir = home / ".config" / "opencode"
        cfg_dir.mkdir(parents=True, exist_ok=True)
        key_file = cfg_dir / "orbrouter.key"
        cfg_file = cfg_dir / "opencode.json"

        # 1. Provision 0600 proxy key from local Orb connection if missing.
        has_valid_key = False
        if key_file.is_file() and not key_file.is_symlink():
            try:
                has_valid_key = bool(key_file.read_text(encoding="utf-8").strip())
            except Exception:
                has_valid_key = False
        elif key_file.is_symlink():
            key_file.unlink()

        if not has_valid_key:
            conn_file = home / ".orb" / "connection.json"
            if conn_file.is_file() and not conn_file.is_symlink():
                try:
                    conn = json.loads(conn_file.read_text(encoding="utf-8"))
                    api_url = str(conn.get("api_url") or "").rstrip("/")
                    token = str(conn.get("token") or "").strip()
                    if api_url and token:
                        host_label = socket.gethostname() or "nixos"
                        req = urllib.request.Request(
                            f"{api_url}/api/proxy-keys",
                            data=json.dumps({"name": f"{host_label}-opencode"}).encode("utf-8"),
                            headers={
                                "Authorization": f"Bearer {token}",
                                "Content-Type": "application/json",
                                "User-Agent": "orb-desktop",
                            },
                            method="POST",
                        )
                        with urllib.request.urlopen(req, timeout=8) as resp:
                            created = json.loads(resp.read())
                        raw_key = str(created.get("key") or "").strip()
                        if raw_key:
                            fd = os.open(str(key_file), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                                fh.write(raw_key + "\n")
                except Exception:
                    pass
        if key_file.is_file() and not key_file.is_symlink():
            os.chmod(key_file, 0o600)

        # 2. Seed core routing chains and merge live chat models from orbrouter.
        seed_models = {
            "builtin/smart": {"name": "Smart Router (builtin/smart)"},
            "builtin/fast": {"name": "Fast Router (builtin/fast)"},
            "builtin/cheap": {"name": "Cheap Router (builtin/cheap)"},
            "builtin/assistant": {"name": "Assistant Router (builtin/assistant)"},
            "google-opus": {"name": "Google Opus 5.5"},
            "google-sonnet": {"name": "Google Sonnet 5.5"},
            "reviewer": {"name": "Reviewer"},
            "reviewer_scout": {"name": "Reviewer Scout"},
            "obliterated": {"name": "Obliterated"},
            "private": {"name": "Private (Self-Hosted)"},
        }

        non_chat_tokens = (
            "embed",
            "whisper",
            "transcribe",
            "-tts",
            "-ocr",
            "moderation",
            "/sam-",
            "-fim-",
            "muse-image-",
            "flash-image",
            "realtime",
        )

        discovered = {}
        if key_file.is_file() and not key_file.is_symlink():
            try:
                proxy_key = key_file.read_text(encoding="utf-8").strip()
                if proxy_key:
                    mreq = urllib.request.Request(
                        "https://orbrouter.thomas.md/v1/models",
                        headers={
                            "Authorization": f"Bearer {proxy_key}",
                            "User-Agent": "opencode/2.2.7",
                        },
                    )
                    with urllib.request.urlopen(mreq, timeout=5) as resp:
                        payload = json.loads(resp.read())
                    for item in payload.get("data") or []:
                        mid = str(item.get("id") or "").strip()
                        if not mid:
                            continue
                        low = mid.lower()
                        if any(tok in low for tok in non_chat_tokens):
                            continue
                        discovered[mid] = seed_models.get(mid, {"name": mid})
            except Exception:
                pass

        existing = {}
        if cfg_file.exists() and not cfg_file.is_symlink():
            try:
                existing = json.loads(cfg_file.read_text(encoding="utf-8"))
                if not isinstance(existing, dict):
                    existing = {}
            except Exception:
                existing = {}
        elif cfg_file.is_symlink():
            cfg_file.unlink()

        # Drop legacy OpenCode v1 keys that fail OpenCode v2 strict schema validation.
        existing.pop("provider", None)
        existing.pop("autoupdate", None)

        providers_obj = existing.get("providers")
        orbrouter_obj = providers_obj.get("orbrouter") if isinstance(providers_obj, dict) else None
        prev_models = orbrouter_obj.get("models") if isinstance(orbrouter_obj, dict) else None
        merged_models = {}
        if isinstance(prev_models, dict):
            merged_models.update(prev_models)
        merged_models.update(seed_models)
        if discovered:
            merged_models = {**seed_models, **discovered}

        existing["$schema"] = "https://opencode.ai/config.json"
        existing["update"] = "disable"
        existing["model"] = "orbrouter/builtin/smart"
        providers = existing.get("providers")
        if not isinstance(providers, dict):
            providers = {}
        providers["orbrouter"] = {
            "name": "Orb Router",
            "package": "@opencode/ai/providers/openai-compatible",
            "settings": {
                "baseURL": "https://orbrouter.thomas.md/v1",
                "apiKey": "{file:~/.config/opencode/orbrouter.key}",
            },
            "models": dict(sorted(merged_models.items())),
        }
        existing["providers"] = providers

        fd, tmp_path = tempfile.mkstemp(prefix="opencode.json.", dir=str(cfg_dir))
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(existing, fh, indent=2)
                fh.write("\n")
            os.replace(tmp_path, str(cfg_file))
        except Exception:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
            raise
        PY
      '';
}
