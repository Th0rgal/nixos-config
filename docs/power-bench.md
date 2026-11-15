# Power benchmark: Linux vs macOS on the m2air

`m2air-bench` (Linux, [`scripts/m2air-bench.py`](../scripts/m2air-bench.py)) and
`macos-bench.sh` (macOS, [`scripts/macos-bench.sh`](../scripts/macos-bench.sh)) sample the
same SMC system-load rail, so watts per scenario compare directly. Both write a CSV and a
JSON summary per run and print a markdown table with `report`.

- Linux results: `~/.local/share/m2air-bench/`
- macOS results: `~/m2air-bench-macos/`

## Ground rules

Run every scenario the same way on both systems, or the ratio means nothing.

- Unplugged, battery between 40% and 85%.
- Display brightness at maximum on both (the only setting that matches without a light
  meter), keyboard backlight off, True Tone and auto-brightness off on macOS.
- Same Wi-Fi network, Bluetooth on, nothing connected over USB.
- Wait two minutes after login before the first run.
- Ten minutes per scenario unless stated.

## Scenarios

| # | Name | What to do | Linux | macOS |
| :-- | :-- | :-- | :-- | :-- |
| 1 | `idle-fresh` | Fresh login, no apps, hands off | `m2air-bench sample idle-fresh` | `./macos-bench.sh sample idle-fresh` |
| 2 | `idle-display-off` | Same, panel blanked, 5 min | `m2air-bench display-off` | `./macos-bench.sh sample idle-display-off 300` after `pmset displaysleepnow` |
| 3 | `session` | Usual apps open (Claude, browser, Telegram), hands off | `m2air-bench sample session` | `./macos-bench.sh sample session` |
| 4 | `video` | 1080p video, fullscreen, in the browser | `m2air-bench sample video` | `./macos-bench.sh sample video` |
| 5 | `cpu-multi` | 8-thread CPU load, 60 s | `m2air-bench exec cpu-multi -- sysbench cpu --threads=8 --time=60 run` | `./macos-bench.sh exec cpu-multi -- sysbench cpu --threads=8 --time=60 run` |
| 6 | `cpu-single` | 1-thread CPU load, 60 s | same with `--threads=1` | same with `--threads=1` |
| 7 | `disk` | 1 GiB random read/write | `m2air-bench exec disk -- fio --name=rw --rw=randrw --bs=4k --size=1g --runtime=60 --time_based --filename=$HOME/fio.tmp` | same command |
| 8 | `sleep` | Lid closed for 8 h | `m2air-bench sleep` | `./macos-bench.sh sleep` before and after |

On macOS, `brew install sysbench fio` provides the load generators. Scenarios 5 to 7 also
give a performance figure (events per second, IOPS) to compare alongside the energy.

## Reading the Linux output

- **system power**: whole-machine draw from the SMC, valid on AC and on battery.
- **deep idle E/P**: share of wall time each cluster spent in the `CPU PD` idle state.
  The efficiency cores sitting near 0% is the main known defect.
- **interrupts**: `IPI call` and `timer` rates show what keeps cores awake; `GPU` and
  `display` rates show how often the compositor renders.
- **top CPU**: per-program CPU over the run, as a percentage of one core.

For deeper digging the host also installs `powertop`, `perf`, `bpftrace`, `fio`,
`iperf3` and `sysbench` ([`hosts/m2air/bench.nix`](../hosts/m2air/bench.nix)).

## Results

Paste `m2air-bench report` and `./macos-bench.sh report` output here after each round,
with the date and the kernel or macOS version.
