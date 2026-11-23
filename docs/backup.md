# Host backup and restore (`m2air` & `razr`)

Declared in [`home/modules/backup.nix`](../home/modules/backup.nix), parameterized by `networking.hostName` (`<host>-restic`).

| | |
| :--- | :--- |
| Repository | `rclone:gdrive:01 — Backups & Infra/<host>-restic` (`m2air-restic` on `m2air`, `razr-restic` on `razr`; restic, AES-256, next to spark's `spark-memory-restic`) |
| Password | sops secret `restic_password` → `/run/secrets/restic_password`; offline copy in the Bitwarden personal vault |
| Schedule | daily on AC power (`restic-backups-<host>.timer`, catches up after sleep); prune to 7 daily / 4 weekly / 12 monthly |
| Container gating (`razr`) | On `boot.isContainer` hosts such as `razr`, `ConditionACPower` is replaced by an `ExecCondition` requiring USB/wireless charging (`/sys/class/power_supply/{usb,wireless}/online == 1`) **and** an unmetered Wi-Fi default route (`wlan*`, never `rmnet*` mobile data) |
| Verification | monthly `backup-verify.timer`: `restic check --read-data-subset=1/16` plus a test restore |
| Contents | `~/Notes`, `~/work` (non-git projects only), `~/Pictures`, `~/Downloads`, `~/.ssh`, `~/.gnupg`, `~/.config`, `~/.local/share`, `~/.orb`, shell history, and git deltas |
| Git deltas | for each repo under `~/work`: unpushed commits and stashes as `unpushed.bundle`, `worktree.patch`, `untracked.tar`, plus `MANIFEST.tsv` of remotes to re-clone |

Excluded (Zero-Rebuild): git working trees already on a remote, caches, browser caches, build outputs, `~/Cloud`, the Wallpapers checkout, rclone credentials.

## Activating it

The units only exist once `secrets/m2air.yaml` contains `restic_password`:

```bash
sudo bootstrap-secrets
```

```bash
cd ~/work/nixos/nixos-config && git add .sops.yaml secrets/m2air.yaml && git commit -m "chore(secrets): add m2air secrets"
```

```bash
rebuild
```

```bash
backup now
```

Then save the password in Bitwarden (personal vault, item `m2air restic`):

```bash
sudo cat /run/secrets/restic_password
```

## Everyday commands

```bash
backup status
```

```bash
backup snapshots
```

```bash
backup restore /home/thomas/Notes ~/restored
```

```bash
backup mount ~/old-backup
```

## Restoring on a new machine

1. Get the restic password from Bitwarden and write it to a file, e.g. `~/restic.pw` (mode 0600).
2. Configure Drive access: `rclone config create gdrive drive scope=drive` (or `cloud setup google` on omarchnix).
3. Point restic at the repository:

```bash
export RESTIC_REPOSITORY="rclone:gdrive:01 — Backups & Infra/m2air-restic" RESTIC_PASSWORD_FILE=~/restic.pw
```

```bash
nix shell nixpkgs#restic nixpkgs#rclone -c restic restore latest --target ~/restored
```

4. Re-clone repos from `~/restored/home/thomas/.local/state/backup/git-deltas/MANIFEST.tsv`, then in each repo:

```bash
git fetch <delta-dir>/unpushed.bundle 'refs/*:refs/restored/*'
```

```bash
git apply <delta-dir>/worktree.patch && tar -xf <delta-dir>/untracked.tar
```

Stashes come back as `refs/restored/backup/stash/N`; `git stash apply refs/restored/backup/stash/0` restores the newest.

## Known risk: rclone's shared Google client_id

Both Drive remotes (`gdrive:` and `google:`) use rclone's shared OAuth client, which Google is retiring during 2026. When it stops working, backups and `~/Cloud/Google` fail with an auth error. Fix: create a Desktop OAuth client in Google Cloud (<https://rclone.org/drive/#making-your-own-client-id>), then:

```bash
rclone config update gdrive client_id=<id> client_secret=<secret> && rclone config reconnect gdrive:
```
