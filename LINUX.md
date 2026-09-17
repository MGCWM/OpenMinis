# Minis Linux (OpenMinis-Linux fork)

This fork keeps the OpenMinis agent + PRoot sandbox, then adds a Linux-leaning
toolchain, host `su` passthrough, POSIX shared-storage mounts, and a distinct
Android identity so it can be installed **next to** official OpenMinis.

## Guest OS

The Android guest is **Ubuntu 24.04 (noble) arm64** extracted from Canonical's
`ubuntu-base` tarball, running under **PRoot** (not KVM, not a chroot that
needs kernel user namespaces).

| | |
|---|---|
| libc | glibc (`aarch64-linux-gnu`) |
| shell | GNU bash (`/bin/bash`; `/bin/sh` is dash) |
| packages | `apt-get` / `apt`. `yum`/`dnf` are apt shims, not RPM. |
| arch | arm64. Packages come from **ports.ubuntu.com**, not archive.ubuntu.com. |
| init | none — ubuntu-base has no systemd under PRoot |

PRoot still fakes uid 0 *inside* the guest. That is not host root. Host root is
only the `su` / `android-su` offload (Magisk/KernelSU).

iOS continues to use iSH + Alpine; this Ubuntu switch is Android-only.

## Toolchain

```
apt-get update && apt-get install -y python3
minis-dev-setup              # bash, gcc, python3, git, ffmpeg, openjdk-21, gradle
minis-android-sdk-setup      # ANDROID_HOME skeleton + best-effort cmdline-tools
yum install python3          # → apt-get install -y python3
```

`aapt2` / `zipalign` / `adb` ship in the APK as **aarch64** static binaries
(AOSP via lzhiyong/android-sdk-tools 35.0.2) and unpack to `/opt/android-sdk`.
`sdkmanager` is Java (works on aarch64 OpenJDK) and is used only to fetch
`platforms;android-35`. Do not install Google's linux build-tools — they are
x86_64 and would overwrite aapt2.

Optional: bind-mount a full SDK as `/var/minis/mounts/android-sdk`.

## Coexistence with official OpenMinis

| | Official | This fork |
|---|---|---|
| `applicationId` | `com.openminis.app` | `com.openminis.linux` |
| Launcher name | Minis | Minis Linux |
| Abstract socket | `native-offload` | `native-offload-linux` |
| Debug JSON-RPC | `127.0.0.1:5321` | `127.0.0.1:5322` |
| Guest | Alpine musl | Ubuntu 24.04 glibc |
| Rootfs dir | `files/alpine-rootfs` | `files/ubuntu-rootfs` |

First launch after this switch extracts Ubuntu and deletes leftover
`alpine-rootfs`. Rebuild assets with:

```
./scripts/prepare_android_sandbox.sh
```

## Host `su` (Shizuku coexist)

Prefer Magisk/KernelSU. If `su` is missing or Magisk denies elevation, the
same command is retried through Shizuku (when the binder is ready). Settings
→ Permissions has a **Host su** card next to Shizuku.

```
su -c id
android-su status
```

Deep link: `minis://settings/host-su`.

## Shared storage

SAF folders bind at `/var/minis/mounts/<name>/`. With All Files Access:
`/sdcard`, `/storage/emulated/0`, `/var/minis/mounts/sdcard`.
