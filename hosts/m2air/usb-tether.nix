# Reliable USB phone Ethernet tethering (Android RNDIS/CDC-NCM/CDC-Ether & iPhone ipheth)
# with structured stall diagnostics, auto-recovery, and `usb-tether` CLI.
{
  lib,
  pkgs,
  ...
}:
let
  tetherDriverRe = "^(rndis_host|cdc_ncm|cdc_ether|ipheth)$";

  # Shared helper library sourced by the NM dispatcher, watchdog, and CLI.
  usbTetherLib = pkgs.writeText "usb-tether-lib.sh" ''
    TETHER_LOG_FILE="/var/log/usb-tether.log"
    TETHER_DRIVER_RE='${tetherDriverRe}'

    log_tether() {
      local level="$1"
      shift
      local msg="$*"
      local ts
      ts="$(date -Is)"
      ${pkgs.util-linux}/bin/logger -t usb-tether -p "daemon.$level" -- "$msg" 2>/dev/null || true
      if [ -w "$TETHER_LOG_FILE" ] || { [ ! -e "$TETHER_LOG_FILE" ] && [ -w "$(dirname "$TETHER_LOG_FILE")" ]; }; then
        printf '%s [%s] %s\n' "$ts" "$level" "$msg" >> "$TETHER_LOG_FILE" 2>/dev/null || true
        # Keep log bounded (~2000 lines).
        if [ "$(wc -l < "$TETHER_LOG_FILE" 2>/dev/null || echo 0)" -gt 2500 ]; then
          tail -n 1500 "$TETHER_LOG_FILE" > "''${TETHER_LOG_FILE}.tmp" 2>/dev/null \
            && mv -f "''${TETHER_LOG_FILE}.tmp" "$TETHER_LOG_FILE" 2>/dev/null || true
        fi
      fi
    }

    iface_driver() {
      local dev="$1"
      local drv
      drv="$(readlink -f "/sys/class/net/$dev/device/driver" 2>/dev/null || true)"
      basename "$drv" 2>/dev/null || true
    }

    is_tether_iface() {
      local dev="$1"
      local drv
      drv="$(iface_driver "$dev")"
      [[ "$drv" =~ $TETHER_DRIVER_RE ]]
    }

    find_tether_ifaces() {
      local p dev
      for p in /sys/class/net/*; do
        dev="$(basename "$p")"
        if is_tether_iface "$dev"; then
          printf '%s\n' "$dev"
        fi
      done
    }

    iface_usb_dev_dir() {
      local dev="$1"
      local cur
      cur="$(readlink -f "/sys/class/net/$dev/device" 2>/dev/null || true)"
      while [ -n "$cur" ] && [ "$cur" != "/" ]; do
        if [ -f "$cur/idVendor" ] && [ -f "$cur/idProduct" ]; then
          printf '%s\n' "$cur"
          return 0
        fi
        cur="$(dirname "$cur")"
      done
      return 1
    }

    iface_gateway() {
      local dev="$1"
      ${pkgs.iproute2}/bin/ip -4 route show dev "$dev" default 2>/dev/null \
        | ${pkgs.gawk}/bin/awk '{for (i=1;i<=NF;i++) if ($i=="via") {print $(i+1); exit}}'
    }

    snapshot_iface() {
      local dev="$1"
      local drv usb_dir vid pid prod pwr_ctrl pwr_stat carrier oper ipv4 gw metric rx_pkts tx_pkts rx_err tx_err rx_drop tx_drop
      drv="$(iface_driver "$dev")"
      usb_dir="$(iface_usb_dev_dir "$dev" || true)"
      vid="$(cat "$usb_dir/idVendor" 2>/dev/null || echo '?')"
      pid="$(cat "$usb_dir/idProduct" 2>/dev/null || echo '?')"
      prod="$(cat "$usb_dir/product" 2>/dev/null || echo '?')"
      pwr_ctrl="$(cat "$usb_dir/power/control" 2>/dev/null || echo '?')"
      pwr_stat="$(cat "$usb_dir/power/runtime_status" 2>/dev/null || echo '?')"
      carrier="$(cat "/sys/class/net/$dev/carrier" 2>/dev/null || echo '?')"
      oper="$(cat "/sys/class/net/$dev/operstate" 2>/dev/null || echo '?')"
      rx_pkts="$(cat "/sys/class/net/$dev/statistics/rx_packets" 2>/dev/null || echo '?')"
      tx_pkts="$(cat "/sys/class/net/$dev/statistics/tx_packets" 2>/dev/null || echo '?')"
      rx_err="$(cat "/sys/class/net/$dev/statistics/rx_errors" 2>/dev/null || echo '?')"
      tx_err="$(cat "/sys/class/net/$dev/statistics/tx_errors" 2>/dev/null || echo '?')"
      rx_drop="$(cat "/sys/class/net/$dev/statistics/rx_dropped" 2>/dev/null || echo '?')"
      tx_drop="$(cat "/sys/class/net/$dev/statistics/tx_dropped" 2>/dev/null || echo '?')"
      ipv4="$(${pkgs.iproute2}/bin/ip -4 -o addr show dev "$dev" 2>/dev/null | ${pkgs.gawk}/bin/awk '{print $4}' | tr '\n' ',' | sed 's/,$//')"
      gw="$(iface_gateway "$dev")"
      metric="$(${pkgs.iproute2}/bin/ip -4 route show dev "$dev" default 2>/dev/null | ${pkgs.gawk}/bin/awk '{for(i=1;i<=NF;i++) if($i=="metric"){print $(i+1); exit}}')"
      printf 'dev=%s drv=%s usb=%s:%s(%s) pwr=%s/%s oper=%s carrier=%s ip=%s gw=%s metric=%s rx=%s(err=%s,drop=%s) tx=%s(err=%s,drop=%s)' \
        "$dev" "$drv" "$vid" "$pid" "$prod" "$pwr_ctrl" "$pwr_stat" "$oper" "$carrier" \
        "''${ipv4:-none}" "''${gw:-none}" "''${metric:-none}" \
        "$rx_pkts" "$rx_err" "$rx_drop" "$tx_pkts" "$tx_err" "$tx_drop"
    }
  '';

  # NetworkManager dispatcher hook: logs every USB tether transition and disables
  # USB autosuspend / problematic offloads on the live device.
  nmDispatcher = pkgs.writeShellScript "95-usb-tether" ''
    set -euo pipefail
    source ${usbTetherLib}

    IFACE="''${1:-}"
    ACTION="''${2:-}"
    [ -n "$IFACE" ] || exit 0
    is_tether_iface "$IFACE" || exit 0

    usb_dir="$(iface_usb_dev_dir "$IFACE" || true)"
    if [ -n "$usb_dir" ] && [ -w "$usb_dir/power/control" ]; then
      echo on > "$usb_dir/power/control" 2>/dev/null || true
    fi

    # RNDIS/NCM USB gadgets frequently stall under GRO/TSO/GSO aggregation on Linux.
    if [ "$ACTION" = "up" ] || [ "$ACTION" = "dhcp4-change" ]; then
      ${pkgs.ethtool}/bin/ethtool -K "$IFACE" tso off gso off gro off 2>/dev/null || true
    fi

    dns_servers="$(grep '^nameserver ' /etc/resolv.conf 2>/dev/null | ${pkgs.gawk}/bin/awk '{print $2}' | tr '\n' ',' | sed 's/,$//')"
    def_routes="$(${pkgs.iproute2}/bin/ip -4 route show default 2>/dev/null | tr '\n' '; ' | sed 's/; $//')"
    log_tether info "NM action=$ACTION $(snapshot_iface "$IFACE") dns=''${dns_servers:-none} defaults=[$def_routes]"
  '';

  # Continuous watchdog that monitors active USB tether interfaces, diagnoses stalls
  # (USB suspend vs carrier vs gateway vs upstream vs DNS), and self-heals cleanly.
  watchdogScript = pkgs.writeShellScript "usb-tether-watchdog" ''
    set -uo pipefail
    source ${usbTetherLib}

    declare -A fail_count
    declare -A last_state
    declare -A last_recover_ts

    log_tether info "watchdog started"

    while true; do
      mapfile -t ifaces < <(find_tether_ifaces)
      if [ "''${#ifaces[@]}" -eq 0 ]; then
        sleep 5
        continue
      fi

      for dev in "''${ifaces[@]}"; do
        usb_dir="$(iface_usb_dev_dir "$dev" || true)"
        if [ -n "$usb_dir" ] && [ -w "$usb_dir/power/control" ]; then
          cur_pwr="$(cat "$usb_dir/power/control" 2>/dev/null || true)"
          if [ "$cur_pwr" != "on" ]; then
            echo on > "$usb_dir/power/control" 2>/dev/null || true
            log_tether warning "FAULT=USB_AUTOSUSPEND_REENABLED forced power/control=on for $dev"
          fi
        fi

        carrier="$(cat "/sys/class/net/$dev/carrier" 2>/dev/null || echo 0)"
        gw="$(iface_gateway "$dev")"
        state="OK"
        detail=""

        if [ "$carrier" != "1" ]; then
          state="CARRIER_LOST"
        elif [ -z "$gw" ]; then
          state="NO_DEFAULT_ROUTE"
        else
          # 1. Probe phone gateway directly on the USB tether interface.
          if ! ${pkgs.iputils}/bin/ping -I "$dev" -c 1 -W 2 "$gw" >/dev/null 2>&1; then
            state="GATEWAY_UNREACHABLE"
            neigh="$(${pkgs.iproute2}/bin/ip -4 neigh show dev "$dev" 2>/dev/null | tr '\n' '; ')"
            detail="neigh=[$neigh]"
          # 2. Probe public IP over the USB tether interface (distinguishes phone upstream drop).
          elif ! ${pkgs.iputils}/bin/ping -I "$dev" -c 1 -W 3 1.1.1.1 >/dev/null 2>&1 \
            && ! ${pkgs.iputils}/bin/ping -I "$dev" -c 1 -W 3 8.8.8.8 >/dev/null 2>&1; then
            state="UPSTREAM_DOWN"
          # 3. Probe DNS resolution.
          elif ! ${pkgs.glibc.bin}/bin/getent ahosts cloudflare.com >/dev/null 2>&1; then
            state="DNS_BLOCKED"
            dns_servers="$(grep '^nameserver ' /etc/resolv.conf 2>/dev/null | ${pkgs.gawk}/bin/awk '{print $2}' | tr '\n' ',' | sed 's/,$//')"
            detail="resolv=''${dns_servers:-empty}"
          fi
        fi

        prev="''${last_state[$dev]:-INIT}"
        if [ "$state" = "OK" ]; then
          if [ "$prev" != "OK" ]; then
            log_tether info "RECOVERED prev=$prev $(snapshot_iface "$dev")"
          fi
          fail_count[$dev]=0
          last_state[$dev]="OK"
        else
          fail_count[$dev]=$(( ''${fail_count[$dev]:-0} + 1 ))
          fc="''${fail_count[$dev]}"
          if [ "$prev" != "$state" ] || [ "$fc" -eq 3 ] || [ $((fc % 6)) -eq 0 ]; then
            def_routes="$(${pkgs.iproute2}/bin/ip -4 route show default 2>/dev/null | tr '\n' '; ' | sed 's/; $//')"
            log_tether warning "FAULT=$state streak=$fc $(snapshot_iface "$dev") $detail defaults=[$def_routes]"
          fi
          last_state[$dev]="$state"

          # Self-heal after 3 consecutive failures (~30s), rate-limited to once per 60s.
          now="$(date +%s)"
          last_rec="''${last_recover_ts[$dev]:-0}"
          if [ "$fc" -ge 3 ] && [ $((now - last_rec)) -ge 60 ]; then
            last_recover_ts[$dev]="$now"
            case "$state" in
              DNS_BLOCKED)
                log_tether notice "ACTION=NM_REAPPLY dev=$dev reason=$state"
                ${pkgs.networkmanager}/bin/nmcli dev reapply "$dev" >/dev/null 2>&1 || true
                ;;
              GATEWAY_UNREACHABLE|NO_DEFAULT_ROUTE)
                log_tether notice "ACTION=NM_BOUNCE dev=$dev reason=$state"
                ${pkgs.networkmanager}/bin/nmcli dev disconnect "$dev" >/dev/null 2>&1 || true
                sleep 1
                ${pkgs.networkmanager}/bin/nmcli dev connect "$dev" >/dev/null 2>&1 || true
                ;;
              CARRIER_LOST|UPSTREAM_DOWN)
                # Don't flap USB link when the phone itself lost cellular/Wi-Fi upstream.
                log_tether info "ACTION=WAIT dev=$dev reason=$state (waiting on phone upstream/carrier)"
                ;;
            esac
          fi
        fi
      done

      sleep 10
    done
  '';

  # Interactive CLI: `usb-tether status|on|off|logs`
  usbTetherCli = pkgs.writeShellApplication {
    name = "usb-tether";
    runtimeInputs = with pkgs; [
      android-tools
      coreutils
      ethtool
      gawk
      glibc.bin
      gnugrep
      iproute2
      iputils
      networkmanager
      systemd
      usbutils
      util-linux
    ];
    text = ''
      set -euo pipefail
      # shellcheck source=/dev/null
      source ${usbTetherLib}

      cmd="''${1:-status}"

      show_status() {
        echo "=== USB-C Ports ==="
        for p in /sys/class/typec/port*; do
          [ -d "$p" ] || continue
          name="$(basename "$p")"
          [[ "$name" == *-partner ]] && continue
          dr="$(cat "$p/data_role" 2>/dev/null || echo '?')"
          pr="$(cat "$p/power_role" 2>/dev/null || echo '?')"
          partner="none"
          [ -d "/sys/class/typec/''${name}-partner" ] && partner="connected"
          printf '  %-6s partner=%-9s data=%-15s power=%s\n' "$name" "$partner" "$dr" "$pr"
        done

        echo "=== Connected Phone USB Devices ==="
        found_usb=0
        for d in /sys/bus/usb/devices/*; do
          [ -f "$d/idVendor" ] || continue
          vid="$(cat "$d/idVendor" 2>/dev/null || true)"
          pid="$(cat "$d/idProduct" 2>/dev/null || true)"
          if [ "$vid" = "22b8" ] || [ "$vid" = "05ac" ] || [ "$vid" = "18d1" ] || [ "$vid" = "04e8" ]; then
            found_usb=1
            mfg="$(cat "$d/manufacturer" 2>/dev/null || echo '?')"
            prod="$(cat "$d/product" 2>/dev/null || echo '?')"
            cfg="$(cat "$d/configuration" 2>/dev/null || echo '?')"
            pwr="$(cat "$d/power/control" 2>/dev/null || echo '?')/$(cat "$d/power/runtime_status" 2>/dev/null || echo '?')"
            printf '  %s: %s:%s %s - %s [cfg=%s pwr=%s]\n' "$(basename "$d")" "$vid" "$pid" "$mfg" "$prod" "$cfg" "$pwr"
          fi
        done
        [ "$found_usb" -eq 1 ] || echo "  (none detected)"

        echo "=== USB Tether Interfaces ==="
        mapfile -t ifaces < <(find_tether_ifaces)
        if [ "''${#ifaces[@]}" -eq 0 ]; then
          echo "  (no rndis_host/cdc_ncm/cdc_ether/ipheth interface present)"
        else
          for dev in "''${ifaces[@]}"; do
            echo "  $(snapshot_iface "$dev")"
            gw="$(iface_gateway "$dev")"
            if [ -n "$gw" ]; then
              gw_ok="FAIL"; ping -I "$dev" -c 1 -W 2 "$gw" >/dev/null 2>&1 && gw_ok="OK"
              ip_ok="FAIL"; ping -I "$dev" -c 1 -W 2 1.1.1.1 >/dev/null 2>&1 && ip_ok="OK"
              dns_ok="FAIL"; getent ahosts cloudflare.com >/dev/null 2>&1 && dns_ok="OK"
              echo "    probes: gateway($gw)=$gw_ok  internet(1.1.1.1)=$ip_ok  dns(cloudflare.com)=$dns_ok"
            fi
          done
        fi

        echo "=== Default Routes & DNS ==="
        ip -4 route show default | sed 's/^/  /'
        grep '^nameserver ' /etc/resolv.conf 2>/dev/null | sed 's/^/  /' || echo "  (no nameservers)"
      }

      case "$cmd" in
        status)
          show_status
          ;;
        on)
          mode="''${2:-rndis}"
          mapfile -t ifaces < <(find_tether_ifaces)
          if [ "''${#ifaces[@]}" -eq 0 ]; then
            if adb get-state >/dev/null 2>&1; then
              echo "Enabling Android USB tethering ($mode) via ADB..."
              adb shell "su -c 'svc usb setFunctions $mode'" >/dev/null 2>&1 || \
                adb shell "svc usb setFunctions $mode" >/dev/null 2>&1 || true
              for _ in $(seq 1 15); do
                sleep 1
                mapfile -t ifaces < <(find_tether_ifaces)
                [ "''${#ifaces[@]}" -gt 0 ] && break
              done
            fi
          fi
          if [ "''${#ifaces[@]}" -eq 0 ]; then
            echo "No USB tether interface found. Ensure the phone is plugged in and USB Tethering is enabled." >&2
            exit 1
          fi
          for dev in "''${ifaces[@]}"; do
            nmcli dev connect "$dev" || true
          done
          sleep 1
          show_status
          ;;
        off)
          mapfile -t ifaces < <(find_tether_ifaces)
          for dev in "''${ifaces[@]}"; do
            nmcli dev disconnect "$dev" || true
          done
          ;;
        logs)
          lines="''${2:-60}"
          echo "=== usb-tether journal (last $lines) ==="
          journalctl -t usb-tether -n "$lines" --no-pager || true
          echo "=== recent kernel USB/net events ==="
          journalctl -k --since "2 hours ago" --no-pager \
            | grep -iE 'rndis|cdc_ncm|cdc_ether|ipheth|usb [0-9]|xhci-hcd|aceElec|22b8|05ac' \
            | tail -n 30 || true
          ;;
        *)
          echo "Usage: usb-tether [status|on [rndis|ncm]|off|logs [N]]" >&2
          exit 2
          ;;
      esac
    '';
  };
in
{
  # Enable usbmuxd so iPhone USB Ethernet (`ipheth`) also works out of the box.
  services.usbmuxd.enable = true;

  environment.systemPackages = [
    pkgs.ethtool
    pkgs.libimobiledevice
    pkgs.usbutils
    usbTetherCli
  ];

  # Prevent cellular MTU blackhole stalls (4G/5G CGNAT/464XLAT MTU < 1500) and
  # allow loose reverse-path filtering (RFC 3704) for smooth Wi-Fi <-> USB tether
  # + WireGuard multi-homed failover.
  boot.kernel.sysctl = {
    "net.ipv4.tcp_mtu_probing" = 1;
    "net.ipv4.conf.all.rp_filter" = lib.mkForce 2;
    "net.ipv4.conf.default.rp_filter" = lib.mkForce 2;
  };

  # Disable USB autosuspend for all USB Ethernet tethering drivers and connected
  # Motorola/Apple phones so the link never silently stalls after 2s idle.
  services.udev.extraRules = ''
    ACTION=="add|bind", SUBSYSTEM=="usb", ATTR{idVendor}=="22b8", TEST=="power/control", ATTR{power/control}="on"
    ACTION=="add|bind", SUBSYSTEM=="usb", ATTR{idVendor}=="05ac", TEST=="power/control", ATTR{power/control}="on"
    ACTION=="add|bind", SUBSYSTEM=="net", DRIVERS=="rndis_host|cdc_ncm|cdc_ether|ipheth", TEST=="device/../power/control", ATTR{device/../power/control}="on"
    ACTION=="add", SUBSYSTEM=="net", DRIVERS=="rndis_host|cdc_ncm|cdc_ether|ipheth", RUN+="${pkgs.util-linux}/bin/logger -t usb-tether 'UDEV add net=%k driver=$env{ID_USB_DRIVER} vid=$env{ID_VENDOR_ID} pid=$env{ID_MODEL_ID}'"
    ACTION=="remove", SUBSYSTEM=="net", ENV{ID_USB_DRIVER}=="rndis_host|cdc_ncm|cdc_ether|ipheth", RUN+="${pkgs.util-linux}/bin/logger -t usb-tether 'UDEV remove net=%k driver=$env{ID_USB_DRIVER}'"
  '';

  # Prefer USB phone Ethernet over Wi-Fi (metric 50 vs 600), prioritize its DNS,
  # and never let flaky carrier IPv6 RA/DHCPv6 block IPv4 activation.
  environment.etc."NetworkManager/conf.d/95-usb-tether.conf".text = ''
    [connection-usb-tether]
    match-device=driver:rndis_host;driver:cdc_ncm;driver:cdc_ether;driver:ipheth
    ipv4.route-metric=50
    ipv6.route-metric=50
    ipv4.dns-priority=50
    ipv6.dns-priority=60
    ipv4.dhcp-timeout=15
    ipv4.may-fail=false
    ipv6.may-fail=true
  '';

  networking.networkmanager.dispatcherScripts = [
    {
      source = nmDispatcher;
      type = "basic";
    }
  ];

  systemd.services.usb-tether-watchdog = {
    description = "USB Phone Ethernet Tethering Watchdog & Stall Diagnostics";
    wantedBy = [ "multi-user.target" ];
    after = [ "NetworkManager.service" ];
    serviceConfig = {
      ExecStart = watchdogScript;
      Restart = "always";
      RestartSec = 5;
      Nice = 10;
    };
  };
}
