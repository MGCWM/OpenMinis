# MinisApp shell configuration
# Loaded by /etc/profile via the profile.d mechanism (login shells only).

# T294: prompt parity with iOS — `root@minis:/var/minis#`. iOS bakes the
# literal "minis" into PS1 (deps/prepare_alpine_rootfs.sh) rather than
# relying on \h, so the prompt is stable regardless of what /etc/hostname
# happens to contain. We do the same on Android so a fresh install
# matches without needing a rootfs rebuild.
export PS1='\u@minis-linux:\w\$ '

# Enable bash command history with arrow keys
export HISTFILE="$HOME/.bash_history"
export SHELL=/bin/bash
export DEBIAN_FRONTEND=noninteractive
export HISTSIZE=1000

# Interactive bash reads ~/.bashrc; keep ENV for dash leftovers.
export ENV="$HOME/.bashrc"

# Default pager — less is standard on Alpine; keep explicit for scripts
# that probe $PAGER.
export PAGER=less

# URL interception: $BROWSER is also seeded directly into every process
# envp via PRootKernel.customEnvironment so non-login shells (which never
# source profile.d) still see it. The xdg-open / sensible-browser /
# www-browser / x-www-browser / gnome-open / kde-open wrappers live as
# real files in default_mount/usr/local/bin/ and are overlaid on every
# boot.
export BROWSER=/usr/local/bin/minis-open

# T222: PRoot's link2symlink extension creates .l2s.* sentinel files alongside
# every hardlinked file. uv's default `hardlink` mode tries to re-link these
# sentinels when populating site-packages, which PRoot rejects with EPERM.
# Force uv to symlink package files instead — the sentinels are then never
# touched as link sources. Reported as openminis/openminis#7.
export UV_LINK_MODE=symlink

# Toolchain paths (populated by minis-dev-setup / minis-android-sdk-setup).
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
export PATH="$PATH:/opt/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/35.0.2:/opt/gradle/bin"
for _jdk in /usr/lib/jvm/java-21-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/default-java; do
  if [ -d "$_jdk" ]; then
    export JAVA_HOME="$_jdk"
    export PATH="$JAVA_HOME/bin:$PATH"
    break
  fi
done
unset _jdk
