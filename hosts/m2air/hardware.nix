# MacBook Air M2 — Partition UUIDs & Removable ARM64 UEFI GRUB bootloader.
# Intentionally uses pure NixOS `fileSystems` + `boot.initrd.luks.devices` by UUID
# (no `disko`) so NixOS never modifies Apple Silicon GPT partition tables,
# `m1n1/boot.bin`, `vendorfw/firmware.cpio`, or Apple NVRAM.
{ lib, pkgs, ... }:
{
  boot.loader = {
    systemd-boot.enable = false;
    efi = {
      canTouchEfiVariables = false;
      efiSysMountPoint = "/boot";
    };
    grub = {
      enable = true;
      device = "nodev";
      efiSupport = true;
      efiInstallAsRemovable = true;
      enableCryptodisk = false;
      # Five bootable rollbacks. Generations that share a kernel and initrd (the
      # common case) share one copy in /boot/kernels; `bootSpace` below refuses a
      # switch whose new kernel/initrd pair would not fit.
      configurationLimit = 5;
      extraFiles = lib.mkForce { };
    };
  };

  # install-grub copies a kernel/initrd pair into /boot/kernels only when that exact
  # store path is not there yet. Fail the switch up front (before anything is copied)
  # when the 476 MiB ESP cannot hold the new pair plus a 16 MiB margin, instead of
  # leaving a half-written .tmp file and a stale grub.cfg.
  system.preSwitchChecks.bootSpace = ''
    case "''${2:-}" in switch|boot) ;; *) exit 0 ;; esac
    need=$((16 * 1024 * 1024))
    for f in kernel initrd; do
      src="$(${pkgs.coreutils}/bin/readlink -f "$1/$f")"
      name="''${src#/nix/store/}"
      name="''${name//\//-}"
      if [ ! -e "/boot/kernels/$name" ]; then
        need=$((need + $(${pkgs.coreutils}/bin/stat -c %s "$src")))
      fi
    done
    avail=$(( $(${pkgs.coreutils}/bin/stat -f -c '%a * %S' /boot) ))
    if [ "$avail" -lt "$need" ]; then
      echo "/boot has $((avail / 1048576)) MiB free but the new kernel/initrd need $((need / 1048576)) MiB." >&2
      echo "Free space with 'sudo nix-env -p /nix/var/nix/profiles/system --delete-generations +3' then retry." >&2
      exit 1
    fi
  '';

  boot.initrd.luks.devices.cryptroot = {
    device = "/dev/disk/by-uuid/aa0403dd-9917-49e1-85b1-46b21b39f2d9";
    allowDiscards = true;
    # Execute ARMv8 hardware AES-XTS inline on the submitting CPU instead of
    # bouncing 4 KiB NVMe I/O across unbound kcryptd workqueues.
    bypassWorkqueues = true;
  };

  fileSystems."/" = {
    device = "/dev/mapper/cryptroot";
    fsType = "ext4";
    options = [
      "defaults"
      "noatime"
    ];
  };

  fileSystems."/boot" = {
    device = "/dev/disk/by-uuid/C650-1003";
    fsType = "vfat";
    options = [
      "fmask=0077"
      "dmask=0077"
    ];
  };

  # Secondary 16 GiB swapfile inside LUKS `/dev/mapper/cryptroot` (`priority = 0`)
  # behind fast in-RAM `zstd` `zramSwap` (`priority = 100`), preventing hard
  # `Free swap = 0kB` kernel OOM kills during parallel Lean / Rust / Nix VM builds.
  swapDevices = [
    {
      device = "/var/lib/swapfile";
      size = 16 * 1024;
      priority = 0;
    }
  ];
}
