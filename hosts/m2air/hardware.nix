# MacBook Air M2 — Partition UUIDs & Removable ARM64 UEFI GRUB bootloader.
# Intentionally uses pure NixOS `fileSystems` + `boot.initrd.luks.devices` by UUID
# (no `disko`) so NixOS never modifies Apple Silicon GPT partition tables,
# `m1n1/boot.bin`, `vendorfw/firmware.cpio`, or Apple NVRAM.
{ lib, ... }:
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
      configurationLimit = 2;
      extraFiles = lib.mkForce { };
    };
  };

  boot.initrd.luks.devices.cryptroot = {
    device = "/dev/disk/by-uuid/aa0403dd-9917-49e1-85b1-46b21b39f2d9";
    allowDiscards = true;
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
}
