# shellcheck shell=bash
# Create or extend secrets/m2air.yaml (sops + age). Run once as root:
#
#   sudo bootstrap-secrets [/path/to/nixos-config]
#
# - Recipients: this host's key (/var/lib/sops-nix/key.txt, created if missing) and
#   Thomas's offline backup age key, whose private half lives in Bitwarden.
# - Adopts the hand-provisioned spark builder SSH key and Nix signing key when present.
# - Generates a fresh restic password for the Google Drive backup if none exists.
# - Never prints a secret, never overwrites a key that is already in the file.
# Plaintext only ever exists in a 0700 directory under /run (tmpfs), removed on exit.
set -euo pipefail

backup_recipient="age1lx57tf0tnmcc32lptrdvk72727hg0jstvg6t5gelw296x7vh6cnskh6mnl"
host_key=/var/lib/sops-nix/key.txt
repo="${1:-/home/${SUDO_USER:-thomas}/work/nixos/nixos-config}"
owner="${SUDO_USER:-thomas}"
file="$repo/secrets/m2air.yaml"

[ "$(id -u)" = 0 ] || { echo "bootstrap-secrets: run with sudo" >&2; exit 1; }
[ -f "$repo/flake.nix" ] || { echo "bootstrap-secrets: $repo is not the nixos-config checkout" >&2; exit 1; }

if [ ! -s "$host_key" ]; then
  install -d -m 0700 "$(dirname "$host_key")"
  age-keygen -o "$host_key" 2>/dev/null
  chmod 0600 "$host_key"
  echo "created host age key $host_key"
fi
host_recipient="$(age-keygen -y "$host_key")"

cat > "$repo/.sops.yaml" <<EOF
# Recipients for secrets/*.yaml. Written by scripts/bootstrap-secrets.sh.
keys:
  - &m2air $host_recipient
  - &backup $backup_recipient
creation_rules:
  - path_regex: secrets/m2air\.yaml$
    key_groups:
      - age:
          - *m2air
          - *backup
EOF

work="$(mktemp -d /run/bootstrap-secrets.XXXXXX)"
chmod 0700 "$work"
trap 'rm -rf "$work"' EXIT
plain="$work/m2air.yaml"
export SOPS_AGE_KEY_FILE="$host_key"

if [ -f "$file" ]; then
  sops --decrypt --input-type yaml --output-type yaml "$file" > "$plain"
else
  printf '{}\n' > "$plain"
fi

has() { [ "$(yq ".$1 // \"\"" "$plain")" != "" ]; }
put_file() { # key path
  if has "$1"; then echo "kept    $1"; return; fi
  if [ -s "$2" ]; then
    SOURCE_FILE="$2" yq -i ".$1 = load_str(strenv(SOURCE_FILE))" "$plain"
    echo "adopted $1 from $2"
  else
    echo "skipped $1 ($2 not found)"
  fi
}

put_file nix_builder_spark_key /root/.ssh/nix-builder-spark
put_file nix_signing_key /etc/nix/signing-key.sec
put_file oura_auth_key "/home/$owner/.config/oura/key.hex"
if has restic_password; then
  echo "kept    restic_password"
else
  V="$(head -c 32 /dev/urandom | base64 | tr -d '\n')" yq -i '.restic_password = strenv(V)' "$plain"
  echo "created restic_password"
fi

install -d -o "$owner" -m 0755 "$repo/secrets"
sops --config "$repo/.sops.yaml" --encrypt --filename-override "$file" \
  --input-type yaml --output-type yaml "$plain" > "$work/enc.yaml"
install -o "$owner" -m 0644 "$work/enc.yaml" "$file"
chown "$owner" "$repo/.sops.yaml"

echo
echo "Wrote $file (encrypted to the host key and the Bitwarden backup key)."
echo "Next: commit .sops.yaml and secrets/m2air.yaml, then run 'rebuild'."
echo "After that rebuild works, the plaintext originals can be removed:"
echo "  sudo rm /root/.ssh/nix-builder-spark /etc/nix/signing-key.sec"
