# Power and efficiency measurement for the m2air: the `m2air-bench` sampler plus
# the profilers and load generators its scenarios use. Runbook: docs/power-bench.md.
{ pkgs, ... }:
let
  m2airBench = pkgs.writeShellApplication {
    name = "m2air-bench";
    runtimeInputs = [ pkgs.python3 ];
    text = ''
      exec python3 -I ${../../scripts/m2air-bench.py} "$@"
    '';
  };
in
{
  environment.systemPackages = [
    m2airBench
    pkgs.powertop
    pkgs.perf
    pkgs.bpftrace
    pkgs.fio
    pkgs.iperf3
    pkgs.sysbench
  ];
}
