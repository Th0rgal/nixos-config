# Stage-2 Aurora SEP / Touch ID / FIDO2 opt-in module for MacBook Air M2.
{ ... }:
{
  omarchnix.hardware.m2air.touchId = {
    enable = true;
    requirePasswordAfterBoot = true;
    fido.enable = true;
  };
}
