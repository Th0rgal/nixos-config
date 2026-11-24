# Stage-2 Aurora SEP / Touch ID / FIDO2 / ANE opt-in module for MacBook Air M2.
_: {
  omarchnix.hardware.m2air = {
    ane.enable = true;
    touchId = {
      enable = true;
      requirePasswordAfterBoot = true;
      fido.enable = true;
    };
  };
}
