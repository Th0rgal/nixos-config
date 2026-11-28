#!/usr/bin/env python3
"""Wake-on-request, idle-unload, thermal-guard & UFS 4.0 prefix-state cache proxy
for Ternary Bonsai 2.5 27B on Motorola Razr 60 Ultra (Hexagon V79 NPU)."""

import hashlib
import http.client
import json
import os
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LISTEN_PORT = int(os.environ.get("BONSAI_PORT", "8090"))
BACKEND_PORT = int(os.environ.get("BONSAI_BACKEND_PORT", "8092"))
BRIDGE_SOCK = os.environ.get("BONSAI_BRIDGE_SOCK", "/var/lib/sidesystem/bridge.sock")
MODE = os.environ.get("BONSAI_MODE", "npu")
IDLE_TIMEOUT = int(os.environ.get("BONSAI_IDLE_TIMEOUT", "180"))
WAKE_TIMEOUT = int(os.environ.get("BONSAI_WAKE_TIMEOUT", "60"))
BURST_MAX_BATT_C = float(os.environ.get("BONSAI_BURST_MAX_BATT_C", "40.0"))
BURST_MAX_SOC_C = float(os.environ.get("BONSAI_BURST_MAX_SOC_C", "68.0"))
MAX_BATT_TEMP_C = float(os.environ.get("BONSAI_MAX_BATT_C", "45.0"))
MAX_SOC_TEMP_C = float(os.environ.get("BONSAI_MAX_SOC_C", "85.0"))
CACHE_DIR = os.environ.get(
    "BONSAI_PROMPT_CACHE_DIR",
    os.path.expanduser("~/bonsai-shootout/prompt-cache"),
)
MIN_CACHE_PREFIX_BYTES = int(os.environ.get("BONSAI_MIN_CACHE_BYTES", "1024"))

lock = threading.RLock()
slot_lock = threading.Lock()
last_activity = time.monotonic()
active_reqs = 0
state = "idle"
phase = "idle"
thermal_tier = "eco"
slot_loaded_hash: str | None = None
live_tok_s: float = 0.0
last_tok_s: float = 0.0
tokens_generated: int = 0
BACKEND_VERSION = os.environ.get("BONSAI_BACKEND_VERSION", "npu-v8")


def log(msg: str) -> None:
    sys.stderr.write(f"[bonsai-proxy] {msg}\n")
    sys.stderr.flush()


def bridge_cmd(cmd: str) -> str:
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
            s.settimeout(15.0)
            s.connect(BRIDGE_SOCK)
            s.sendall((cmd + "\n").encode())
            return s.recv(1024).decode(errors="replace").strip()
    except Exception as e:
        return f"ERR {e}"


def read_batt_c() -> float:
    try:
        with open("/sys/class/power_supply/battery/temp", "r") as f:
            return float(f.read().strip()) / 10.0
    except Exception:
        return 0.0


def read_soc_c() -> float:
    try:
        with open("/sys/class/thermal/thermal_zone0/temp", "r") as f:
            v = float(f.read().strip())
            return v / 1000.0 if v > 200.0 else v
    except Exception:
        return 0.0


def set_thermal_tier(target: str) -> None:
    global thermal_tier
    if thermal_tier == target:
        return
    profile = {"burst": "full", "cruise": "balanced", "guard": "eco", "eco": "eco"}.get(
        target, "balanced"
    )
    bridge_cmd(f"PROFILE_SET {profile}")
    thermal_tier = target


def regulate_thermal(active: bool) -> None:
    batt = read_batt_c()
    soc = read_soc_c()
    if not active:
        set_thermal_tier("eco")
        return
    if batt >= MAX_BATT_TEMP_C or soc >= MAX_SOC_TEMP_C:
        set_thermal_tier("guard")
    elif batt < BURST_MAX_BATT_C and soc < BURST_MAX_SOC_C:
        set_thermal_tier("burst")
    else:
        set_thermal_tier("cruise")


def backend_healthy() -> bool:
    try:
        c = http.client.HTTPConnection("127.0.0.1", BACKEND_PORT, timeout=1.5)
        c.request("GET", "/health")
        r = c.getresponse()
        r.read()
        c.close()
        return r.status == 200
    except Exception:
        return False


def stop_backend(new_state: str) -> None:
    global state, slot_loaded_hash
    bridge_cmd("BONSAI_STOP")
    state = new_state
    slot_loaded_hash = None


def ensure_awake() -> tuple[bool, str]:
    global last_activity, state, slot_loaded_hash
    batt = read_batt_c()
    if batt >= MAX_BATT_TEMP_C:
        with lock:
            stop_backend("thermal_paused")
        return False, f"Thermal safety guard active (battery {batt:.1f}C >= {MAX_BATT_TEMP_C:.0f}C)"
    with lock:
        last_activity = time.monotonic()
        if backend_healthy():
            state = "ready"
            return True, "ok"
        state = "starting"
        slot_loaded_hash = None
        res = bridge_cmd(f"BONSAI_START {MODE}")
        if not res.startswith("OK"):
            state = "error"
            return False, f"Bridge start failed: {res}"
    deadline = time.monotonic() + WAKE_TIMEOUT
    while time.monotonic() < deadline:
        if backend_healthy():
            with lock:
                state = "ready"
                last_activity = time.monotonic()
            return True, "ok"
        time.sleep(0.5)
    with lock:
        state = "timeout"
    return False, "Backend wake timeout"


CORE_TOOLS = ("bash", "read", "write", "edit", "glob", "grep", "ls", "list")
COMPACT_SYSTEM_PROMPT = (
    "You are OpenCode running locally on a Motorola Razr 60 Ultra "
    "(Snapdragon 8 Elite SM8750, Hexagon V79 NPU) inside NixOS SideSystem. "
    "Be concise and direct. Use tools when needed to inspect the system or files."
)


def compact_opencode_payload(body: bytes) -> bytes:
    """Keep OpenCode requests well inside -c 8192 so GDN state never overflows."""
    try:
        payload = json.loads(body)
    except Exception:
        return body
    changed = False
    msgs = payload.get("messages")
    if isinstance(msgs, list):
        sys_len = sum(
            len(str(m.get("content", "")))
            for m in msgs
            if isinstance(m, dict) and m.get("role") in ("system", "developer")
        )
        if sys_len > 3000:
            non_sys = [m for m in msgs if not (isinstance(m, dict) and m.get("role") in ("system", "developer"))]
            payload["messages"] = [{"role": "system", "content": COMPACT_SYSTEM_PROMPT}] + non_sys
            changed = True
    tools = payload.get("tools")
    if isinstance(tools, list) and len(tools) > 6:
        kept = []
        for t in tools:
            fn = t.get("function") if isinstance(t, dict) else None
            name = str((fn or {}).get("name", "")).lower()
            if any(k == name or name.endswith("_" + k) for k in CORE_TOOLS):
                t_copy = json.loads(json.dumps(t))
                if isinstance(t_copy.get("function"), dict) and isinstance(t_copy["function"].get("description"), str):
                    t_copy["function"]["description"] = t_copy["function"]["description"][:160]
                kept.append(t_copy)
        if kept:
            payload["tools"] = kept
            changed = True
    if not changed:
        return body
    new_body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    log(f"compacted OpenCode payload: {len(body)} -> {len(new_body)} bytes")
    return new_body


def extract_prefix_hash(body: bytes) -> tuple[str | None, int]:
    """Compute a stable SHA-256 hash over the static system prompt + tools schema."""
    try:
        payload = json.loads(body)
    except Exception:
        return None, 0
    messages = payload.get("messages")
    if not isinstance(messages, list):
        return None, 0
    sys_parts = []
    for m in messages:
        if isinstance(m, dict) and m.get("role") in ("system", "developer"):
            sys_parts.append(m.get("content", ""))
        else:
            break
    tools = payload.get("tools")
    key_obj = {"system": sys_parts, "tools": tools}
    raw = json.dumps(key_obj, sort_keys=True, separators=(",", ":")).encode("utf-8")
    if len(raw) < MIN_CACHE_PREFIX_BYTES:
        return None, len(raw)
    return hashlib.sha256(raw).hexdigest()[:16], len(raw)


def slot_action(action: str, filename: str, timeout: float = 30.0) -> tuple[bool, float]:
    t0 = time.monotonic()
    try:
        conn = http.client.HTTPConnection("127.0.0.1", BACKEND_PORT, timeout=timeout)
        body = json.dumps({"filename": filename}).encode("utf-8")
        conn.request(
            "POST",
            f"/slots/0?action={action}",
            body=body,
            headers={"Content-Type": "application/json", "Content-Length": str(len(body))},
        )
        resp = conn.getresponse()
        resp.read()
        conn.close()
        return resp.status == 200, (time.monotonic() - t0) * 1000.0
    except Exception as e:
        log(f"slot {action} ({filename}) failed: {e}")
        return False, (time.monotonic() - t0) * 1000.0


def maybe_restore_prefix(prefix_hash: str) -> None:
    global slot_loaded_hash
    if not prefix_hash:
        return
    cache_file = os.path.join(CACHE_DIR, f"{prefix_hash}.bin")
    if not os.path.isfile(cache_file):
        return
    with slot_lock:
        if slot_loaded_hash == prefix_hash:
            return
        ok, ms = slot_action("restore", f"{prefix_hash}.bin")
        if ok:
            slot_loaded_hash = prefix_hash
            log(f"restored UFS prompt state {prefix_hash}.bin in {ms:.0f} ms")


def maybe_save_prefix(prefix_hash: str) -> None:
    global slot_loaded_hash
    if not prefix_hash:
        return
    os.makedirs(CACHE_DIR, exist_ok=True)
    cache_file = os.path.join(CACHE_DIR, f"{prefix_hash}.bin")
    if os.path.isfile(cache_file):
        return
    with slot_lock:
        if os.path.isfile(cache_file):
            return
        ok, ms = slot_action("save", f"{prefix_hash}.bin", timeout=60.0)
        if ok:
            slot_loaded_hash = prefix_hash
            size_mb = os.path.getsize(cache_file) / (1024 * 1024) if os.path.isfile(cache_file) else 0.0
            log(f"saved UFS prompt state {prefix_hash}.bin ({size_mb:.1f} MiB) in {ms:.0f} ms")


def watchdog() -> None:
    while True:
        time.sleep(10)
        batt = read_batt_c()
        with lock:
            if batt >= MAX_BATT_TEMP_C and state in ("ready", "starting"):
                stop_backend("thermal_paused")
                log(f"thermal pause at {batt:.1f}C")
                continue
            if active_reqs == 0 and state == "ready" and (time.monotonic() - last_activity) >= IDLE_TIMEOUT:
                stop_backend("idle")
                log(f"idle unload after {IDLE_TIMEOUT}s")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _send_json(self, code: int, obj: dict):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _proxy(self, method: str):
        global active_reqs, last_activity, phase, live_tok_s, last_tok_s, tokens_generated
        if self.path == "/health":
            self._send_json(
                200,
                {
                    "status": "ok",
                    "state": state,
                    "phase": phase,
                    "backend": BACKEND_VERSION,
                    "thermal_tier": thermal_tier,
                    "mode": MODE,
                    "batt_c": read_batt_c(),
                    "soc_c": read_soc_c(),
                    "tok_s": round(live_tok_s, 2),
                    "last_tok_s": round(last_tok_s, 2),
                    "tokens_generated": tokens_generated,
                    "slot_loaded_hash": slot_loaded_hash,
                },
            )
            return
        if self.path == "/v1/models" and method == "GET" and not backend_healthy():
            self._send_json(
                200,
                {
                    "object": "list",
                    "data": [
                        {
                            "id": "ternary-bonsai-2.5-27b",
                            "object": "model",
                            "owned_by": "bonsai-local",
                        }
                    ],
                },
            )
            return
        ok, msg = ensure_awake()
        if not ok:
            self._send_json(503, {"error": {"message": msg, "type": "bonsai_unavailable"}})
            return
        length = int(self.headers.get("Content-Length", "0") or "0")
        body = self.rfile.read(length) if length > 0 else b""
        hdrs = {
            k: v
            for k, v in self.headers.items()
            if k.lower() not in ("host", "connection", "transfer-encoding")
        }

        prefix_hash = None
        if method == "POST" and self.path.startswith("/v1/chat/completions") and body:
            body = compact_opencode_payload(body)
            hdrs["Content-Length"] = str(len(body))
            prefix_hash, _ = extract_prefix_hash(body)
            if prefix_hash:
                maybe_restore_prefix(prefix_hash)

        with lock:
            active_reqs += 1
            last_activity = time.monotonic()
            phase = "prefill"
            live_tok_s = 0.0
            tokens_generated = 0
            regulate_thermal(True)
        last_therm = time.monotonic()
        tok_times: list[float] = []
        tok_count = 0
        resp_status = 0
        try:
            conn = http.client.HTTPConnection("127.0.0.1", BACKEND_PORT, timeout=600)
            conn.request(method, self.path, body=body, headers=hdrs)
            resp = conn.getresponse()
            resp_status = resp.status
            self.send_response(resp.status)
            is_chunked = False
            is_sse = False
            for k, v in resp.getheaders():
                kl = k.lower()
                if kl in ("connection", "keep-alive"):
                    continue
                if kl == "transfer-encoding" and "chunked" in v.lower():
                    is_chunked = True
                if kl == "content-type" and "text/event-stream" in v.lower():
                    is_sse = True
                self.send_header(k, v)
            self.send_header("X-Accel-Buffering", "no")
            self.send_header("Connection", "close")
            self.end_headers()
            while True:
                chunk = resp.readline() if is_sse else resp.read(4096)
                if not chunk:
                    break
                if is_chunked:
                    self.wfile.write(f"{len(chunk):x}\r\n".encode() + chunk + b"\r\n")
                else:
                    self.wfile.write(chunk)
                self.wfile.flush()
                now = time.monotonic()
                n_sse = chunk.count(b"data: {")
                if n_sse > 0:
                    for _ in range(n_sse):
                        tok_times.append(now)
                    tok_count += n_sse
                    # Rolling 2.5s window (min 0.5s span to avoid burst spikes)
                    cutoff = now - 2.5
                    while tok_times and tok_times[0] < cutoff:
                        tok_times.pop(0)
                    span = max(now - tok_times[0], 0.5) if len(tok_times) > 1 else 0.5
                    rate = len(tok_times) / span
                    with lock:
                        phase = "decoding"
                        tokens_generated = tok_count
                        live_tok_s = rate
                        last_tok_s = rate
                with lock:
                    last_activity = now
                    if now - last_therm >= 1.0:
                        regulate_thermal(True)
                        last_therm = now
            if is_chunked:
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
            conn.close()
        except Exception as e:
            try:
                self._send_json(502, {"error": {"message": str(e)}})
            except Exception:
                pass
        finally:
            with lock:
                active_reqs = max(0, active_reqs - 1)
                last_activity = time.monotonic()
                if active_reqs == 0:
                    phase = "idle"
                    live_tok_s = 0.0
                    regulate_thermal(False)
        if prefix_hash and resp_status == 200:
            maybe_save_prefix(prefix_hash)

    def do_GET(self):
        self._proxy("GET")

    def do_POST(self):
        self._proxy("POST")


if __name__ == "__main__":
    os.makedirs(CACHE_DIR, exist_ok=True)
    threading.Thread(target=watchdog, daemon=True).start()
    srv = ThreadingHTTPServer(("127.0.0.1", LISTEN_PORT), Handler)
    log(
        f"listening on 127.0.0.1:{LISTEN_PORT} -> 127.0.0.1:{BACKEND_PORT} "
        f"(mode={MODE}, idle={IDLE_TIMEOUT}s, max_batt={MAX_BATT_TEMP_C}C, cache={CACHE_DIR})"
    )
    srv.serve_forever()
