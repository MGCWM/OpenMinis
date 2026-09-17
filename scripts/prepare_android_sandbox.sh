#!/usr/bin/env bash
# Prepare the Android sandbox assets:
#   Ubuntu 24.04 (noble) arm64 base tarball  →  assets/ubuntu-base.tar.gz
#
# The tarball is Canonical's official ubuntu-base (glibc + apt + bash), not
# Alpine musl. arm64 packages live on ports.ubuntu.com.
#
# PRoot itself is still built by deps/build_proot.sh (NDK), not fetched here.
#
# Usage:
#   ./scripts/prepare_android_sandbox.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/src/android/app/src/main/assets"
mkdir -p "$ASSETS"

UBUNTU_VERSION="${UBUNTU_VERSION:-24.04.3}"
UBUNTU_CODENAME="${UBUNTU_CODENAME:-noble}"
UBUNTU_URLS=(
  "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-${UBUNTU_VERSION}-base-arm64.tar.gz"
  "https://cdimage.ubuntu.com/ubuntu-base/releases/${UBUNTU_VERSION}/release/ubuntu-base-${UBUNTU_VERSION}-base-arm64.tar.gz"
  "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.2-base-arm64.tar.gz"
)

echo "==> Fetching Ubuntu ${UBUNTU_VERSION} (${UBUNTU_CODENAME}) arm64 base"
TMP_UBUNTU="$(mktemp)"
downloaded=0
for url in "${UBUNTU_URLS[@]}"; do
  echo "    trying $url"
  if curl -fL --retry 3 -o "$TMP_UBUNTU" "$url"; then
    downloaded=1
    echo "    got $url"
    break
  fi
done
if [ "$downloaded" -ne 1 ]; then
  echo "ERROR: failed to download ubuntu-base arm64 tarball" >&2
  rm -f "$TMP_UBUNTU"
  exit 1
fi
mv "$TMP_UBUNTU" "$ASSETS/ubuntu-base.tar.gz"
# Drop the previous Alpine asset so a dirty tree cannot ship both.
rm -f "$ASSETS/alpine-minirootfs.tar.gz" "$ASSETS/alpine-minirootfs.tar"

ls -lh "$ASSETS/ubuntu-base.tar.gz"

SDK_TOOLS_VER="${SDK_TOOLS_VER:-35.0.2}"
SDK_TOOLS_ZIP="$ASSETS/android-sdk-tools-aarch64.zip"
SDK_TOOLS_URL="https://github.com/lzhiyong/android-sdk-tools/releases/download/${SDK_TOOLS_VER}/android-sdk-tools-static-aarch64.zip"
if [ ! -f "$SDK_TOOLS_ZIP" ]; then
  echo "==> Fetching aarch64 aapt2/zipalign/adb (${SDK_TOOLS_VER})"
  TMP_SDK="$(mktemp)"
  if curl -fL --retry 3 -o "$TMP_SDK" "$SDK_TOOLS_URL"; then
    mv "$TMP_SDK" "$SDK_TOOLS_ZIP"
  else
    echo "WARNING: aarch64 SDK tools download failed; on-device aapt2 will be missing" >&2
    rm -f "$TMP_SDK"
  fi
fi
if [ -f "$SDK_TOOLS_ZIP" ]; then
  ls -lh "$SDK_TOOLS_ZIP"
fi

echo "==> Android sandbox assets ready (Ubuntu ${UBUNTU_CODENAME})"
