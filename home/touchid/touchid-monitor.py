#!/usr/bin/env python3
"""Stream live Touch ID (fprintd) biometric sensor events as JSON lines for TouchId.qml."""

import ctypes
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time

RE_VERIFY_SELECTED = re.compile(r"net\.reactivated\.Fprint\.Device\.VerifyFingerSelected\s*\('([^']*)'\)")
RE_VERIFY_STATUS = re.compile(r"net\.reactivated\.Fprint\.Device\.VerifyStatus\s*\('([^']+)',\s*(true|false)\)")
RE_FINGER_NEEDED = re.compile(r"'finger-needed':\s*<(true|false)>")
RE_FINGER_PRESENT = re.compile(r"'finger-present':\s*<(true|false)>")

SUPPRESSED_COMMS = {"hyprlock", "swaylock", "gtklock", "polkit-agent-helper-1"}
SUPPRESSED_LAYERS = {"omarchy-polkit", "omarchy-lock", "hyprlock"}


def emit(payload: dict) -> None:
    try:
        sys.stdout.write(json.dumps(payload, separators=(",", ":")) + "\n")
        sys.stdout.flush()
    except BrokenPipeError:
        sys.exit(0)


def read_text(path: str) -> str:
    try:
        with open(path, "rb") as fh:
            return fh.read(4096).decode("utf-8", errors="replace")
    except OSError:
        return ""


def read_cmdline(pid: int) -> list[str]:
    try:
        with open(f"/proc/{pid}/cmdline", "rb") as fh:
            raw = fh.read(4096)
        if not raw:
            return []
        return [part.decode("utf-8", errors="replace") for part in raw.rstrip(b"\x00").split(b"\x00")]
    except OSError:
        return []


def read_status_meta(pid: int) -> tuple[int, int]:
    ppid = 0
    ruid = -1
    txt = read_text(f"/proc/{pid}/status")
    for line in txt.splitlines():
        if line.startswith("PPid:"):
            parts = line.split()
            if len(parts) >= 2 and parts[1].isdigit():
                ppid = int(parts[1])
        elif line.startswith("Uid:"):
            parts = line.split()
            if len(parts) >= 2 and parts[1].isdigit():
                ruid = int(parts[1])
        if ppid and ruid >= 0:
            break
    return ppid, ruid


def has_suppressed_layer() -> bool:
    hyprctl = shutil.which("hyprctl") or "/run/current-system/sw/bin/hyprctl"
    if not os.path.exists(hyprctl):
        return False
    try:
        res = subprocess.run(
            [hyprctl, "layers", "-j"],
            capture_output=True,
            text=True,
            timeout=0.35,
        )
        if res.returncode != 0 or not res.stdout:
            return False
        data = json.loads(res.stdout)
        if not isinstance(data, dict):
            return False
        for mon in data.values():
            levels = (mon or {}).get("levels") or {}
            for entries in levels.values():
                if not isinstance(entries, list):
                    continue
                for entry in entries:
                    ns = str((entry or {}).get("namespace") or "")
                    if ns in SUPPRESSED_LAYERS:
                        return True
    except Exception:
        pass
    return False


def format_sudo_subtitle(argv: list[str]) -> str:
    # Extract target command from `sudo [flags] <cmd ...>`
    i = 1
    flags_with_arg = {"-u", "-g", "-p", "-C", "-D", "-R", "-T", "-h", "-r", "-t"}
    while i < len(argv):
        tok = argv[i]
        if tok == "--":
            i += 1
            break
        if not tok.startswith("-"):
            break
        if tok in flags_with_arg:
            i += 2
        else:
            i += 1
    rest = " ".join(argv[i:]).strip()
    if not rest:
        return "Authenticate command in terminal"
    if len(rest) > 46:
        rest = rest[:45].rstrip() + "…"
    return f"sudo {rest}"


def inspect_caller() -> dict:
    my_uid = os.getuid()
    procs: dict[int, tuple[str, list[str], int, int]] = {}
    try:
        entries = os.listdir("/proc")
    except OSError:
        entries = []

    for entry in entries:
        if not entry.isdigit():
            continue
        pid = int(entry)
        comm = read_text(f"/proc/{pid}/comm").strip()
        if not comm:
            continue
        if comm in SUPPRESSED_COMMS:
            return {"suppressed": True}
        argv = read_cmdline(pid)
        if argv:
            base0 = os.path.basename(argv[0])
            if base0 in SUPPRESSED_COMMS or "polkit-agent-helper" in base0:
                return {"suppressed": True}
        ppid, ruid = read_status_meta(pid)
        procs[pid] = (comm, argv, ppid, ruid)

    if has_suppressed_layer():
        return {"suppressed": True}

    # 1. Check `fprintd-verify` and walk its ancestor chain (e.g. Orb `.orb-signer`).
    fp_candidates = [
        pid
        for pid, (comm, argv, _, _) in procs.items()
        if comm == "fprintd-verify" or (argv and os.path.basename(argv[0]) == "fprintd-verify")
    ]
    if fp_candidates:
        fp_pid = max(fp_candidates)
        _, _, ppid, ruid = procs[fp_pid]
        cancel_pid = fp_pid if ruid == my_uid else 0
        cur = ppid
        visited = set()
        while cur > 1 and cur not in visited and cur in procs:
            visited.add(cur)
            p_comm, p_argv, p_ppid, _ = procs[cur]
            joined = " ".join(p_argv).lower()
            if "orb" in p_comm.lower() or "orb-signer" in joined or ".orb-signer" in joined or "orb-desktop" in joined:
                orb_reason = ""
                for idx, arg in enumerate(p_argv):
                    if arg == "--reason" and idx + 1 < len(p_argv):
                        orb_reason = p_argv[idx + 1].strip()
                        break
                    if arg.startswith("--reason="):
                        orb_reason = arg.split("=", 1)[1].strip()
                        break
                return {
                    "suppressed": False,
                    "callerApp": "Orb",
                    "callerSubtitle": orb_reason or "Touch sensor to approve",
                    "cancelPid": cancel_pid,
                }
            cur = p_ppid
        return {
            "suppressed": False,
            "callerApp": "",
            "callerSubtitle": "Touch sensor to verify",
            "cancelPid": cancel_pid,
        }

    # 2. Check `sudo` / `doas` / `su` PAM authentication in terminal.
    sudo_candidates = [
        (pid, argv)
        for pid, (comm, argv, _, _) in procs.items()
        if comm in ("sudo", "doas") or (argv and os.path.basename(argv[0]) in ("sudo", "doas"))
    ]
    if sudo_candidates:
        _, argv = max(sudo_candidates, key=lambda item: item[0])
        return {
            "suppressed": False,
            "callerApp": "Terminal",
            "callerSubtitle": format_sudo_subtitle(argv),
            "cancelPid": 0,
        }

    # 3. Check `aurora-fidod` (WebAuthn / passkey / SSH sk-ecdsa).
    has_fidod = any(
        comm == "aurora-fidod" or (argv and os.path.basename(argv[0]) == "aurora-fidod")
        for comm, argv, _, _ in procs.values()
    )
    if has_fidod:
        return {
            "suppressed": False,
            "callerApp": "Passkey",
            "callerSubtitle": "Verify passkey with Touch ID",
            "cancelPid": 0,
        }

    return {
        "suppressed": False,
        "callerApp": "",
        "callerSubtitle": "Touch sensor to authenticate",
        "cancelPid": 0,
    }


def resolve_gdbus() -> str:
    for candidate in (
        os.environ.get("OMARCHNIX_GDBUS", ""),
        shutil.which("gdbus") or "",
        "/run/current-system/sw/bin/gdbus",
    ):
        if candidate and os.path.exists(candidate):
            return candidate
    return "gdbus"


def _set_pdeathsig() -> None:
    if sys.platform != "linux":
        return
    try:
        libc = ctypes.CDLL("libc.so.6", use_errno=True)
        libc.prctl(1, signal.SIGTERM, 0, 0, 0)  # PR_SET_PDEATHSIG = 1
    except Exception:
        pass


def main() -> None:
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    signal.signal(signal.SIGINT, lambda *_: sys.exit(0))
    _set_pdeathsig()
    if os.getppid() == 1:
        return

    gdbus_bin = resolve_gdbus()
    session_active = False
    session_suppressed = False

    while True:
        proc = None
        try:
            proc = subprocess.Popen(
                [gdbus_bin, "monitor", "--system", "--dest", "net.reactivated.Fprint"],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                bufsize=1,
                preexec_fn=_set_pdeathsig,
            )
            assert proc.stdout is not None
            for raw_line in proc.stdout:
                line = raw_line.strip()
                if not line:
                    continue

                m_sel = RE_VERIFY_SELECTED.search(line)
                if m_sel and not session_active:
                    info = inspect_caller()
                    session_active = True
                    session_suppressed = bool(info.get("suppressed"))
                    if session_suppressed:
                        emit({"event": "suppressed"})
                    else:
                        emit(
                            {
                                "event": "start",
                                "finger": m_sel.group(1),
                                "callerApp": info.get("callerApp", ""),
                                "callerSubtitle": info.get("callerSubtitle", ""),
                                "cancelPid": info.get("cancelPid", 0),
                            }
                        )

                m_needed = RE_FINGER_NEEDED.search(line)
                if m_needed:
                    needed = m_needed.group(1) == "true"
                    if needed and not session_active:
                        info = inspect_caller()
                        session_active = True
                        session_suppressed = bool(info.get("suppressed"))
                        if session_suppressed:
                            emit({"event": "suppressed"})
                        else:
                            emit(
                                {
                                    "event": "start",
                                    "finger": "",
                                    "callerApp": info.get("callerApp", ""),
                                    "callerSubtitle": info.get("callerSubtitle", ""),
                                    "cancelPid": info.get("cancelPid", 0),
                                }
                            )
                    elif not needed and session_active:
                        if not session_suppressed:
                            emit({"event": "stop"})
                        session_active = False
                        session_suppressed = False

                m_present = RE_FINGER_PRESENT.search(line)
                if m_present and session_active and not session_suppressed:
                    present = m_present.group(1) == "true"
                    emit({"event": "finger-down" if present else "finger-up"})

                m_status = RE_VERIFY_STATUS.search(line)
                if m_status:
                    status = m_status.group(1)
                    done = m_status.group(2) == "true"
                    if not session_suppressed:
                        if status == "verify-match":
                            emit({"event": "match", "done": done})
                        elif status == "verify-no-match":
                            emit({"event": "no-match", "done": done})
                        elif status in (
                            "verify-retry-scan",
                            "verify-swipe-too-short",
                            "verify-center-finger",
                            "verify-remove-and-retry",
                        ):
                            emit({"event": "retry", "reason": status, "done": done})
                        elif status in ("verify-disconnected", "verify-unknown-error"):
                            emit({"event": "stop"})
                    if done:
                        session_active = False
                        session_suppressed = False
        except Exception:
            pass
        finally:
            if proc is not None and proc.poll() is None:
                try:
                    proc.terminate()
                except Exception:
                    pass
        time.sleep(1.5)


if __name__ == "__main__":
    main()
