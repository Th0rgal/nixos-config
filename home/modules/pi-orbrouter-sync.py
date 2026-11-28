import json
import os
import pathlib
import socket
import tempfile
import urllib.request

home = pathlib.Path.home()
agent_dir = home / ".pi" / "agent"
agent_dir.mkdir(parents=True, exist_ok=True)
os.chmod(agent_dir, 0o700)

key_file = agent_dir / "orbrouter.key"
opencode_key_file = home / ".config" / "opencode" / "orbrouter.key"
models_file = agent_dir / "models.json"
settings_file = agent_dir / "settings.json"


def _read_nonempty_file(path: pathlib.Path) -> str:
    if path.is_file() and not path.is_symlink():
        try:
            return path.read_text(encoding="utf-8").strip()
        except Exception:
            return ""
    return ""


def _write_atomic_json(path: pathlib.Path, data: dict) -> None:
    fd, tmp_path = tempfile.mkstemp(prefix=f"{path.name}.", dir=str(path.parent))
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=2)
            fh.write("\n")
        os.replace(tmp_path, str(path))
    except Exception:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise


# 1. Provision 0600 proxy key (reuse OpenCode's key if present, else mint from ~/.orb/connection.json).
if key_file.is_symlink():
    key_file.unlink()

proxy_key = _read_nonempty_file(key_file)
if not proxy_key:
    reused = _read_nonempty_file(opencode_key_file)
    if reused:
        fd = os.open(str(key_file), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(reused + "\n")
        proxy_key = reused

if not proxy_key:
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
                    data=json.dumps({"name": f"{host_label}-pi"}).encode("utf-8"),
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
                    proxy_key = raw_key
        except Exception:
            pass

if key_file.is_file() and not key_file.is_symlink():
    os.chmod(key_file, 0o600)

# 2. Seed core routing chains and merge live chat models from Orb Router.
seed_models = {
    "builtin/smart": {"name": "Routing — Smart", "reasoning": True, "contextWindow": 200000, "maxTokens": 32000},
    "builtin/fast": {"name": "Routing — Fast", "reasoning": True, "contextWindow": 200000, "maxTokens": 16384},
    "builtin/cheap": {"name": "Routing — Cheap", "reasoning": False, "contextWindow": 128000, "maxTokens": 16384},
    "builtin/assistant": {"name": "Routing — Assistant", "reasoning": True, "contextWindow": 200000, "maxTokens": 16384},
    "google-opus": {"name": "Google Opus 5.5", "reasoning": True, "contextWindow": 1000000, "maxTokens": 32000},
    "google-sonnet": {"name": "Google Sonnet 5.5", "reasoning": True, "contextWindow": 1000000, "maxTokens": 32000},
    "reviewer": {"name": "Reviewer", "reasoning": True, "contextWindow": 200000, "maxTokens": 32000},
    "reviewer_scout": {"name": "Reviewer Scout", "reasoning": True, "contextWindow": 200000, "maxTokens": 16384},
    "obliterated": {"name": "Obliterated", "reasoning": True, "contextWindow": 200000, "maxTokens": 16384},
    "private": {"name": "Private (Self-Hosted)", "reasoning": True, "contextWindow": 128000, "maxTokens": 16384},
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
if proxy_key:
    try:
        mreq = urllib.request.Request(
            "https://orbrouter.thomas.md/v1/models",
            headers={
                "Authorization": f"Bearer {proxy_key}",
                "User-Agent": "pi-coding-agent/1.5.0",
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
            discovered[mid] = seed_models.get(
                mid,
                {
                    "name": mid,
                    "reasoning": True,
                    "contextWindow": 200000,
                    "maxTokens": 32000,
                },
            )
    except Exception:
        pass

existing = {}
if models_file.exists() and not models_file.is_symlink():
    try:
        loaded = json.loads(models_file.read_text(encoding="utf-8"))
        if isinstance(loaded, dict):
            existing = loaded
    except Exception:
        existing = {}
elif models_file.is_symlink():
    models_file.unlink()

providers = existing.get("providers")
if not isinstance(providers, dict):
    providers = {}

prev_orb_models = {}
orb_prev = providers.get("orb")
if isinstance(orb_prev, dict) and isinstance(orb_prev.get("models"), list):
    for m in orb_prev["models"]:
        if isinstance(m, dict) and isinstance(m.get("id"), str) and m["id"].strip():
            prev_orb_models[m["id"].strip()] = m

merged = {}
merged.update(prev_orb_models)
for mid, meta in seed_models.items():
    merged[mid] = {
        "id": mid,
        "name": meta["name"],
        "reasoning": meta["reasoning"],
        "input": ["text", "image"],
        "contextWindow": meta["contextWindow"],
        "maxTokens": meta["maxTokens"],
    }
if discovered:
    merged = {}
    for mid, meta in {**seed_models, **discovered}.items():
        merged[mid] = {
            "id": mid,
            "name": meta["name"],
            "reasoning": meta["reasoning"],
            "input": ["text", "image"],
            "contextWindow": meta["contextWindow"],
            "maxTokens": meta["maxTokens"],
        }

# Use Pi shell-command indirection so no literal API key is persisted inside models.json.
providers["orb"] = {
    "baseUrl": "https://orbrouter.thomas.md/v1",
    "api": "openai-completions",
    "apiKey": f"!cat {key_file}",
    "authHeader": True,
    "models": [merged[k] for k in sorted(merged.keys())],
}

# On Razr 60 Ultra (or any host running local bonsai-proxy on :8090), register Bonsai 27B NPU.
hostname = (socket.gethostname() or "").strip().lower()
bonsai_present = hostname == "razr"
if not bonsai_present:
    try:
        with socket.create_connection(("127.0.0.1", 8090), timeout=0.3):
            bonsai_present = True
    except OSError:
        bonsai_present = False

if bonsai_present:
    providers["bonsai-local"] = {
        "baseUrl": "http://127.0.0.1:8090/v1",
        "api": "openai-completions",
        "apiKey": "bonsai-local",
        "compat": {
            "supportsDeveloperRole": False,
            "supportsReasoningEffort": False,
        },
        "models": [
            {
                "id": "ternary-bonsai-2.5-27b",
                "name": "Bonsai 2.5 27B (Local NPU)",
                "reasoning": False,
                "input": ["text"],
                "contextWindow": 8192,
                "maxTokens": 2048,
            }
        ],
    }

existing["providers"] = providers
_write_atomic_json(models_file, existing)

# 3. Ensure sensible defaults in ~/.pi/agent/settings.json without clobbering user choices.
settings = {}
if settings_file.exists() and not settings_file.is_symlink():
    try:
        loaded_s = json.loads(settings_file.read_text(encoding="utf-8"))
        if isinstance(loaded_s, dict):
            settings = loaded_s
    except Exception:
        settings = {}
elif settings_file.is_symlink():
    settings_file.unlink()

settings.setdefault("defaultProvider", "orb")
settings.setdefault("defaultModel", "builtin/smart")
settings["checkForUpdates"] = False
_write_atomic_json(settings_file, settings)
