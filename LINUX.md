# Minis Linux (OpenMinis-Linux fork)

This fork keeps the OpenMinis agent + PRoot sandbox, then adds a Linux-leaning
toolchain, host `su` passthrough, POSIX shared-storage mounts, and a distinct
Android identity so it can be installed **next to** official OpenMinis.

启动器名称是 **Minis Ultra**，`applicationId` 为 `com.openminis.linux`。当前版本 **1.36.10-linux**（versionCode 63）。

滚动 APK：GitHub Releases 标签 `android-latest`，文件名 `minis-ultra-com.openminis.linux.apk`。关于页 / 检查更新走 fork `tall-1997/OpenMinis-Linux`；滚动包用 release body 里的 `versionCode` / `versionName`（以及 APK `updated_at`）判断是否比本机新。

正式发行包：[Releases `1.36.10-linux`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/1.36.10-linux)。1.36.10：人格提示词改为文件名入口 + 二级编辑，支持导入 .md/.txt 并按供应商选择。详见 [docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

## 沙箱当服务器

客户机里可以直接驱动主机：

```
minis-toast 备份完成
minis-clipboard get
minis-clipboard set --text 'hello'
minis-open --system https://example.com   # 无 TTY 时 http(s) 也会走 android-open
minis-firewall status
minis-firewall set wifi-only              # 可选 --strict（整进程绑 Wi-Fi，含 LLM）
minis-firewall log                        # 沙箱 http_proxy 记 CONNECT，无 VpnService
minis-firewall cut                        # 一键切断沙箱出站
minis-notify post --title 完成 --body ok --action-label 重试 --action-command /var/minis/hooks/retry.sh
minis-on-event register battery_low /var/minis/hooks/pause.sh
minis-doze status
minis-doze request
minis-ps
cat /run/minis-host-status.json
cat /run/android-events.jsonl
cat /run/minis-netlog.jsonl
cat /run/minis-proc.json
```

任务完成通知上的「重试 / 备份 / 清理」会在对应会话沙箱执行预设命令（上次 shell、打包 workspace、清 `/tmp`）。

## 客户机系统

Android 客户机是 Canonical **Ubuntu 24.04 (noble) arm64** 的 `ubuntu-base`，跑在 **PRoot** 里（不是 KVM，也不依赖内核 user namespace 的 chroot）。

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
