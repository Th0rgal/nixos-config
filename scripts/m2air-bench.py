#!/usr/bin/env python3
"""m2air-bench: power and efficiency sampler for the MacBook Air M2 under Linux.

Logs SMC power rails, battery state, CPU load, idle-state residency and interrupt
rates to CSV for a named scenario, then writes a JSON summary. The macOS
counterpart (scripts/macos-bench.sh) records the same SMC "system load" rail, so
watts per scenario are directly comparable.

  m2air-bench sample NAME [-d SECONDS] [-i INTERVAL]   sample while you do NAME
  m2air-bench exec NAME -- CMD...                      sample while CMD runs
  m2air-bench display-off [-d SECONDS]                 blank the panel and sample
  m2air-bench sleep                                    suspend now, report drain on wake
  m2air-bench report                                   markdown table of all runs

Results live in ~/.local/share/m2air-bench/. Nothing here needs root.
"""

import argparse
import csv
import glob
import json
import os
import statistics
import subprocess
import sys
import time

OUT = os.path.expanduser(
    os.environ.get("M2AIR_BENCH_DIR", "~/.local/share/m2air-bench")
)
BAT = "/sys/class/power_supply/macsmc-battery"
AC = "/sys/class/power_supply/macsmc-ac"
E_CORES = range(0, 4)
P_CORES = range(4, 8)
# Mailbox interrupts of the GPU and display coprocessors: a proxy for how often
# the compositor renders and presents a frame.
IRQ_NAMES = {
    "timer_irq": "arch_timer",
    "ipi_call": "Function call interrupts",
    "ipi_resched": "Rescheduling interrupts",
    "gpu_irq": "206408000.mbox-recv",
    "dcp_irq": "231c08000.mbox-recv",
    "wifi_irq": "brcmf_pcie_intr",
    "nvme_irq": "nvme-apple",
}


def read(path, default=None):
    try:
        with open(path) as f:
            return f.read().strip()
    except OSError:
        return default


def read_int(path, default=0):
    try:
        return int(read(path))
    except (TypeError, ValueError):
        return default


def smc_rails():
    """Map label -> sysfs path for the macsmc_hwmon sensors."""
    rails = {}
    for hw in glob.glob("/sys/class/hwmon/hwmon*"):
        if read(f"{hw}/name") != "macsmc_hwmon":
            continue
        for label in glob.glob(f"{hw}/*_label"):
            rails[read(label)] = label.replace("_label", "_input")
    return rails


def cpu_times():
    """Per-CPU (busy, total) jiffies and the context switch counter."""
    cpus, ctxt = {}, 0
    with open("/proc/stat") as f:
        for line in f:
            p = line.split()
            if p[0].startswith("cpu") and p[0] != "cpu":
                v = [int(x) for x in p[1:9]]
                idle = v[3] + v[4]
                cpus[int(p[0][3:])] = (sum(v) - idle, sum(v))
            elif p[0] == "ctxt":
                ctxt = int(p[1])
    return cpus, ctxt


def idle_times():
    """Per-CPU {state name: microseconds}."""
    out = {}
    for cpu in list(E_CORES) + list(P_CORES):
        states = {}
        base = f"/sys/devices/system/cpu/cpu{cpu}/cpuidle"
        for s in glob.glob(f"{base}/state*"):
            states[read(f"{s}/name")] = read_int(f"{s}/time")
        out[cpu] = states
    return out


def irq_counts():
    counts = dict.fromkeys(IRQ_NAMES, 0)
    total = 0
    with open("/proc/interrupts") as f:
        ncpu = len(f.readline().split())
        for line in f:
            p = line.split()
            n = sum(int(x) for x in p[1:1 + ncpu] if x.isdigit())
            total += n
            for key, needle in IRQ_NAMES.items():
                if needle in line:
                    counts[key] += n
    counts["irq_total"] = total
    return counts


def proc_times():
    """pid -> (comm, cpu jiffies) for every process."""
    out = {}
    for d in glob.glob("/proc/[0-9]*"):
        stat = read(f"{d}/stat")
        if not stat:
            continue
        try:
            comm = stat[stat.index("(") + 1:stat.rindex(")")]
            rest = stat[stat.rindex(")") + 2:].split()
            out[int(d[6:])] = (comm, int(rest[11]) + int(rest[12]))
        except (ValueError, IndexError):
            continue
    return out


def freq_mhz(policy):
    path = f"/sys/devices/system/cpu/cpufreq/policy{policy}/scaling_cur_freq"
    return read_int(path) / 1000


def backlight():
    for bl in glob.glob("/sys/class/backlight/*"):
        return read_int(f"{bl}/brightness"), read_int(f"{bl}/max_brightness")
    return 0, 0


def cluster_pct(delta_busy, cores):
    busy = sum(delta_busy[c][0] for c in cores)
    total = sum(delta_busy[c][1] for c in cores)
    return 100 * busy / total if total else 0.0


def deep_idle_pct(d_idle, cores, dt):
    """Share of wall time the cluster spent in the deepest idle state."""
    deep = sum(d_idle[c].get("CPU PD", 0) for c in cores)
    return 100 * deep / (dt * 1e6 * len(cores)) if dt else 0.0


FIELDS = [
    "t", "ac", "sys_w", "soc_w", "ac_in_w", "bat_w", "energy_wh", "capacity",
    "backlight", "cpu_e_pct", "cpu_p_pct", "deep_e_pct", "deep_p_pct",
    "freq_e_mhz", "freq_p_mhz", "ctxt_s", "irq_total", *IRQ_NAMES,
]


def sample_loop(name, duration, interval, until=None):
    """Sample until `duration` elapses or `until()` returns True."""
    os.makedirs(os.path.join(OUT, name), exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    base = os.path.join(OUT, name, stamp)
    rails = smc_rails()
    rows = []
    start = time.monotonic()
    procs0 = proc_times()
    hz = os.sysconf("SC_CLK_TCK")
    cpu0, ctxt0 = cpu_times()
    idle0, irq0, t0 = idle_times(), irq_counts(), start
    energy_start = read_int(f"{BAT}/energy_now") / 1e6

    with open(base + ".csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        while True:
            time.sleep(interval)
            now = time.monotonic()
            dt = now - t0
            cpu1, ctxt1 = cpu_times()
            idle1, irq1 = idle_times(), irq_counts()
            d_cpu = {
                c: (cpu1[c][0] - cpu0[c][0], cpu1[c][1] - cpu0[c][1])
                for c in cpu1 if c in cpu0
            }
            d_idle = {
                c: {s: idle1[c][s] - idle0[c].get(s, 0) for s in idle1[c]}
                for c in idle1
            }
            bl, bl_max = backlight()
            row = {
                "t": round(now - start, 1),
                "ac": read_int(f"{AC}/online"),
                "sys_w": read_int(rails.get("Total System Power", "")) / 1e6,
                "soc_w": read_int(rails.get("Heatpipe Power", "")) / 1e6,
                "ac_in_w": read_int(rails.get("AC Input Power", "")) / 1e6,
                "bat_w": read_int(f"{BAT}/power_now") / 1e6,
                "energy_wh": read_int(f"{BAT}/energy_now") / 1e6,
                "capacity": read_int(f"{BAT}/capacity"),
                "backlight": round(100 * bl / bl_max) if bl_max else 0,
                "cpu_e_pct": cluster_pct(d_cpu, E_CORES),
                "cpu_p_pct": cluster_pct(d_cpu, P_CORES),
                "deep_e_pct": deep_idle_pct(d_idle, E_CORES, dt),
                "deep_p_pct": deep_idle_pct(d_idle, P_CORES, dt),
                "freq_e_mhz": freq_mhz(0),
                "freq_p_mhz": freq_mhz(4),
                "ctxt_s": (ctxt1 - ctxt0) / dt,
                "irq_total": (irq1["irq_total"] - irq0["irq_total"]) / dt,
            }
            for key in IRQ_NAMES:
                row[key] = (irq1[key] - irq0[key]) / dt
            row = {
                k: round(v, 2) if isinstance(v, float) else v
                for k, v in row.items()
            }
            w.writerow(row)
            f.flush()
            rows.append(row)
            cpu0, ctxt0, idle0, irq0, t0 = cpu1, ctxt1, idle1, irq1, now
            print(
                f"\r{name} {row['t']:>6.0f}s  sys {row['sys_w']:5.2f} W  "
                f"E {row['cpu_e_pct']:4.1f}%  P {row['cpu_p_pct']:4.1f}%  "
                f"deep-idle E {row['deep_e_pct']:4.1f}% "
                f"P {row['deep_p_pct']:4.1f}%",
                end="", file=sys.stderr, flush=True,
            )
            if until is not None:
                if until():
                    break
            elif now - start >= duration:
                break
    print(file=sys.stderr)

    elapsed = time.monotonic() - start
    procs1 = proc_times()
    top = sorted(
        (
            (procs1[p][1] - procs0.get(p, (None, 0))[1], procs1[p][0])
            for p in procs1
        ),
        reverse=True,
    )[:12]
    # Merge processes sharing a name so helper swarms show as one line.
    merged = {}
    for p, (comm, ticks) in procs1.items():
        delta = ticks - procs0.get(p, (None, 0))[1]
        merged[comm] = merged.get(comm, 0) + delta
    by_name = sorted(merged.items(), key=lambda kv: -kv[1])[:12]
    del top

    def mean(key):
        return round(statistics.fmean(r[key] for r in rows), 2)

    energy_end = read_int(f"{BAT}/energy_now") / 1e6
    full = read_int(f"{BAT}/energy_full") / 1e6
    on_ac = any(r["ac"] for r in rows)
    sys_w = mean("sys_w")
    summary = {
        "scenario": name,
        "os": "linux",
        "kernel": os.uname().release,
        "started": stamp,
        "seconds": round(elapsed),
        "on_ac": on_ac,
        "backlight_pct": mean("backlight"),
        "sys_w_mean": sys_w,
        "sys_w_median": round(statistics.median(r["sys_w"] for r in rows), 2),
        "sys_w_min": min(r["sys_w"] for r in rows),
        "sys_w_max": max(r["sys_w"] for r in rows),
        "soc_w_mean": mean("soc_w"),
        "battery_w_from_energy": (
            None if on_ac
            else round((energy_start - energy_end) * 3600 / elapsed, 2)
        ),
        "energy_full_wh": round(full, 1),
        "hours_at_this_load": round(full / sys_w, 1) if sys_w else None,
        "cpu_e_pct": mean("cpu_e_pct"),
        "cpu_p_pct": mean("cpu_p_pct"),
        "deep_idle_e_pct": mean("deep_e_pct"),
        "deep_idle_p_pct": mean("deep_p_pct"),
        "ctxt_s": mean("ctxt_s"),
        "irq_total_s": mean("irq_total"),
        **{f"{k}_s": mean(k) for k in IRQ_NAMES},
        "top_cpu_pct_of_one_core": [
            {"name": n, "pct": round(100 * t / hz / elapsed, 1)}
            for n, t in by_name if t > 0
        ],
    }
    with open(base + ".json", "w") as f:
        json.dump(summary, f, indent=2)
    return summary, base


def print_summary(summary, base):
    s = summary
    print(f"\n{s['scenario']}: {s['seconds']} s, "
          f"{'AC' if s['on_ac'] else 'battery'}, "
          f"backlight {s['backlight_pct']:.0f}%")
    print(f"  system power   {s['sys_w_mean']} W mean "
          f"({s['sys_w_min']}-{s['sys_w_max']}), SoC {s['soc_w_mean']} W")
    if s["battery_w_from_energy"] is not None:
        print(f"  battery gauge  {s['battery_w_from_energy']} W")
    print(f"  runtime        {s['hours_at_this_load']} h on a full "
          f"{s['energy_full_wh']} Wh")
    print(f"  CPU busy       E {s['cpu_e_pct']}%  P {s['cpu_p_pct']}%")
    print(f"  deep idle      E {s['deep_idle_e_pct']}%  "
          f"P {s['deep_idle_p_pct']}%")
    print(f"  interrupts     {s['irq_total_s']}/s (timer {s['timer_irq_s']}, "
          f"IPI call {s['ipi_call_s']}, GPU {s['gpu_irq_s']}, "
          f"display {s['dcp_irq_s']})")
    print("  top CPU        " + ", ".join(
        f"{p['name']} {p['pct']}%" for p in s["top_cpu_pct_of_one_core"][:6]))
    print(f"  saved          {base}.csv / .json")


def cmd_sample(args):
    print_summary(*sample_loop(args.name, args.duration, args.interval))


def cmd_exec(args):
    if not args.cmd:
        sys.exit("m2air-bench exec: give a command after --")
    t0 = time.monotonic()
    proc = subprocess.Popen(args.cmd)
    summary, base = sample_loop(
        args.name, 0, args.interval, until=lambda: proc.poll() is not None
    )
    summary["command"] = " ".join(args.cmd)
    summary["command_seconds"] = round(time.monotonic() - t0, 1)
    summary["command_exit"] = proc.returncode
    summary["energy_j"] = round(summary["sys_w_mean"] * summary["seconds"])
    with open(base + ".json", "w") as f:
        json.dump(summary, f, indent=2)
    print_summary(summary, base)
    print(f"  command        {summary['command_seconds']} s, "
          f"exit {proc.returncode}, about {summary['energy_j']} J")


def hyprctl_dpms(state):
    subprocess.run(
        ["hyprctl", "dispatch", f'hl.dsp.dpms("{state}")'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
    )


def cmd_display_off(args):
    print("Blanking the panel; it comes back when the run ends.",
          file=sys.stderr)
    time.sleep(3)
    hyprctl_dpms("off")
    try:
        result = sample_loop("idle-display-off", args.duration, args.interval)
    finally:
        hyprctl_dpms("on")
    print_summary(*result)


def cmd_sleep(args):
    if read_int(f"{AC}/online"):
        print("warning: on AC power, the drain figure will be meaningless",
              file=sys.stderr)
    before = read_int(f"{BAT}/energy_now") / 1e6
    boot0 = time.clock_gettime(time.CLOCK_BOOTTIME)
    mono0 = time.monotonic()
    print("Suspending in 5 s. Close the lid, open it when you are done.",
          file=sys.stderr)
    time.sleep(5)
    subprocess.run(["systemctl", "suspend"], check=False)
    # CLOCK_BOOTTIME keeps counting while suspended, CLOCK_MONOTONIC does not.
    while True:
        time.sleep(2)
        asleep = (time.clock_gettime(time.CLOCK_BOOTTIME) - boot0) - (
            time.monotonic() - mono0)
        if asleep > 30:
            break
    time.sleep(5)
    after = read_int(f"{BAT}/energy_now") / 1e6
    hours = asleep / 3600
    full = read_int(f"{BAT}/energy_full") / 1e6
    summary = {
        "scenario": "sleep",
        "os": "linux",
        "kernel": os.uname().release,
        "started": time.strftime("%Y%m%d-%H%M%S"),
        "seconds": round(asleep),
        "on_ac": bool(read_int(f"{AC}/online")),
        "energy_before_wh": round(before, 2),
        "energy_after_wh": round(after, 2),
        "sys_w_mean": round((before - after) / hours, 3),
        "pct_per_hour": round(100 * (before - after) / full / hours, 2),
    }
    os.makedirs(os.path.join(OUT, "sleep"), exist_ok=True)
    path = os.path.join(OUT, "sleep", summary["started"] + ".json")
    with open(path, "w") as f:
        json.dump(summary, f, indent=2)
    print(f"slept {hours:.2f} h, {before - after:.2f} Wh used: "
          f"{summary['sys_w_mean']} W, {summary['pct_per_hour']} %/h")
    print(f"saved {path}")


def cmd_report(_args):
    runs = []
    for path in sorted(glob.glob(os.path.join(OUT, "*", "*.json"))):
        with open(path) as f:
            runs.append(json.load(f))
    if not runs:
        sys.exit(f"no runs in {OUT}")
    print("| Scenario | OS | When | Power | Secs | W mean | SoC W | "
          "Deep idle E/P % | CPU E/P % | IRQ/s | Hours |")
    print("| :-- | :-- | :-- | :-- | --: | --: | --: | --: | --: | --: | --: |")
    for s in runs:
        def g(key, default="-"):
            v = s.get(key)
            return default if v is None else v
        print(
            f"| {s['scenario']} | {g('os')} | {g('started')} | "
            f"{'AC' if s.get('on_ac') else 'battery'} | {g('seconds')} | "
            f"{g('sys_w_mean')} | {g('soc_w_mean')} | "
            f"{g('deep_idle_e_pct')} / {g('deep_idle_p_pct')} | "
            f"{g('cpu_e_pct')} / {g('cpu_p_pct')} | {g('irq_total_s')} | "
            f"{g('hours_at_this_load')} |"
        )


def main():
    ap = argparse.ArgumentParser(
        prog="m2air-bench", description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = ap.add_subparsers(dest="command", required=True)

    def common(p, duration=600):
        p.add_argument("-d", "--duration", type=float, default=duration,
                       help=f"seconds to sample (default {duration})")
        p.add_argument("-i", "--interval", type=float, default=5,
                       help="seconds between samples (default 5)")

    p = sub.add_parser("sample", help="sample while you run a scenario")
    p.add_argument("name")
    common(p)
    p.set_defaults(func=cmd_sample)

    p = sub.add_parser("exec", help="sample while a command runs")
    p.add_argument("name")
    p.add_argument("-i", "--interval", type=float, default=2)
    p.add_argument("cmd", nargs=argparse.REMAINDER)
    p.set_defaults(func=cmd_exec)

    p = sub.add_parser("display-off", help="blank the panel and sample")
    common(p, 300)
    p.set_defaults(func=cmd_display_off)

    p = sub.add_parser("sleep", help="suspend and report the drain on wake")
    p.set_defaults(func=cmd_sleep)

    p = sub.add_parser("report", help="markdown table of all runs")
    p.set_defaults(func=cmd_report)

    args = ap.parse_args()
    if getattr(args, "cmd", None) and args.cmd[0] == "--":
        args.cmd = args.cmd[1:]
    args.func(args)


if __name__ == "__main__":
    main()
